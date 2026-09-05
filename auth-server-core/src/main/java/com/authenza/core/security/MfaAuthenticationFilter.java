package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.core.service.SessionRecordingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Intercepts {@code POST /{tenantId}/mfa-verify} and completes the multi-step
 * authentication flow after a successful TOTP code submission.
 *
 * <h3>Session attributes</h3>
 * <ul>
 *   <li>{@code PENDING_MFA_USERNAME} — set by the success handler after password
 *       validation when MFA is enabled.</li>
 *   <li>{@code PENDING_MFA_TENANT} — the active tenant ID at the time of password auth.</li>
 * </ul>
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Read {@code mfaToken} from the request parameter; if absent, redirect to login
 *       (session expired or direct navigation).</li>
 *   <li>Look up the encrypted TOTP secret from the DB.</li>
 *   <li>Verify the submitted 6-digit code via {@link TotpService}.</li>
 *   <li>On success — set the full {@link SecurityContext}, clear pending Redis state,
 *       redirect to the saved OAuth2 authorize URL (or tenant root).</li>
 *   <li>On failure — record a brute-force attempt, redirect to
 *       {@code /{tenantId}/mfa-verify?mfaToken=...&error}.</li>
 * </ol>
 */
@Component
public class MfaAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MfaAuthenticationFilter.class);

    private final JdbcTenantUserDetailsService userDetailsService;
    private final TotpService totpService;
    private final BruteForceProtectionService bruteForceProtectionService;
    private final SessionRecordingService sessionRecordingService;
    private final AuthPendingStateStore authPendingStateStore;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public MfaAuthenticationFilter(JdbcTenantUserDetailsService userDetailsService,
                                   TotpService totpService,
                                   BruteForceProtectionService bruteForceProtectionService,
                                   SessionRecordingService sessionRecordingService,
                                   AuthPendingStateStore authPendingStateStore) {
        this.userDetailsService = userDetailsService;
        this.totpService = totpService;
        this.bruteForceProtectionService = bruteForceProtectionService;
        this.sessionRecordingService = sessionRecordingService;
        this.authPendingStateStore = authPendingStateStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only intercept POST to /{tenantId}/mfa-verify
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().endsWith("/mfa-verify");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String tenantId = resolveTenantFromUri(request.getRequestURI());
        String mfaToken = request.getParameter("mfaToken");

        // ── 1. Validate token presence ─────────────────────────────────────────
        if (mfaToken == null || mfaToken.isBlank()) {
            // No mfaToken — direct POST attempt or expired state
            String loginUrl = (tenantId != null) ? "/" + tenantId + "/login" : "/login";
            response.sendRedirect(loginUrl);
            return;
        }

        // ── 2. Retrieve pending state from Redis ──────────────────────────────
        AuthPendingStateStore.PendingState pendingState =
                authPendingStateStore.getAndClearPendingState(AuthPendingStateStore.TYPE_MFA, mfaToken);
        if (pendingState == null) {
            // Expired or not found — redirect to login
            String loginUrl = (tenantId != null) ? "/" + tenantId + "/login?error" : "/login?error";
            response.sendRedirect(loginUrl);
            return;
        }

        String username = pendingState.username();
        tenantId        = pendingState.tenantId() != null ? pendingState.tenantId() : tenantId;

        // Ensure TenantContextHolder is set so the routing datasource resolves correctly
        if (tenantId != null) {
            TenantContextHolder.setTenantId(tenantId);
        }

        String submittedCode = request.getParameter("code");
        if (submittedCode != null) {
            submittedCode = submittedCode.replaceAll("\\s", ""); // strip spaces
        }

        // ── 3. Load user and retrieve TOTP secret ─────────────────────────────
        UserDetails userDetails;
        String encryptedSecret;
        try {
            userDetails = userDetailsService.loadUserByUsername(username);
            encryptedSecret = userDetailsService.loadMfaSecret(username);
        } catch (Exception e) {
            log.error("[MFA] Failed to load user '{}': {}", username, e.getMessage());
            response.sendRedirect("/" + tenantId + "/login?error");
            return;
        }

        if (encryptedSecret == null) {
            // MFA is required but the user has never completed enrollment
            // (e.g. admin set mfa_enabled=true without the user scanning a QR code).
            // Send them to the setup page to enroll their authenticator app.
            log.warn("[MFA] No secret found for user '{}' — redirecting to MFA setup", username);
            response.sendRedirect("/" + tenantId + "/mfa-setup");
            return;
        }

        // ── 4. Verify the TOTP code ────────────────────────────────────────────
        String rawSecret = totpService.decryptSecret(encryptedSecret);
        boolean codeValid = totpService.verifyCode(rawSecret, submittedCode);

        if (!codeValid) {
            log.warn("[MFA] Invalid TOTP code for user '{}' in tenant '{}'", username, tenantId);
            bruteForceProtectionService.recordFailedAttempt(username, tenantId);
            // Re-save the pending state for another attempt with a fresh token
            String newMfaToken = authPendingStateStore.savePendingState(
                    AuthPendingStateStore.TYPE_MFA,
                    new AuthPendingStateStore.PendingState(username, tenantId, pendingState.userId()));
            response.sendRedirect("/" + tenantId + "/mfa-verify?mfaToken=" + newMfaToken + "&error");
            return;
        }

        // ── 5. Code is valid — complete Spring Security authentication ─────────
        bruteForceProtectionService.resetFailedAttempts(username, tenantId);

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authToken);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);

        log.info("[MFA] Authentication complete for user '{}' in tenant '{}'", username, tenantId);

        // Record session for Active Devices tracking (MFA-completed login path)
        sessionRecordingService.recordSession(pendingState.userId(), request);

        // ── 6. Follow saved request (OAuth2 authorize) or fall back ───────────
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);

        if (savedRequest != null) {
            String targetUrl = savedRequest.getRedirectUrl();
            requestCache.removeRequest(request, response);
            response.sendRedirect(targetUrl);
        } else {
            response.sendRedirect("/" + tenantId + "/");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static String resolveTenantFromUri(String uri) {
        if (uri == null || uri.equals("/")) return null;
        String[] parts = uri.split("/");
        for (String part : parts) {
            if (!part.isEmpty()) return part;
        }
        return null;
    }
}
