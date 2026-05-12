package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.core.service.SessionRecordingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
 *   <li>Read {@code PENDING_MFA_USERNAME} from session; if absent, redirect to login
 *       (session expired or direct navigation).</li>
 *   <li>Look up the encrypted TOTP secret from the DB.</li>
 *   <li>Verify the submitted 6-digit code via {@link TotpService}.</li>
 *   <li>On success — set the full {@link SecurityContext}, clear pending session
 *       attributes, redirect to the saved OAuth2 authorize URL (or tenant root).</li>
 *   <li>On failure — record a brute-force attempt, redirect to
 *       {@code /{tenantId}/mfa-verify?error}.</li>
 * </ol>
 */
@Component
public class MfaAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MfaAuthenticationFilter.class);

    /** Session attribute keys used to pass pending MFA state between the password and TOTP steps. */
    public static final String PENDING_MFA_USERNAME = "PENDING_MFA_USERNAME";
    public static final String PENDING_MFA_TENANT   = "PENDING_MFA_TENANT";
    public static final String PENDING_MFA_USER_ID  = "PENDING_MFA_USER_ID";

    private final JdbcTenantUserDetailsService userDetailsService;
    private final TotpService totpService;
    private final BruteForceProtectionService bruteForceProtectionService;
    private final SessionRecordingService sessionRecordingService;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public MfaAuthenticationFilter(JdbcTenantUserDetailsService userDetailsService,
                                   TotpService totpService,
                                   BruteForceProtectionService bruteForceProtectionService,
                                   SessionRecordingService sessionRecordingService) {
        this.userDetailsService = userDetailsService;
        this.totpService = totpService;
        this.bruteForceProtectionService = bruteForceProtectionService;
        this.sessionRecordingService = sessionRecordingService;
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

        HttpSession session = request.getSession(false);

        // ── 1. Validate session state ──────────────────────────────────────────
        if (session == null
                || session.getAttribute(PENDING_MFA_USERNAME) == null) {
            // No pending MFA state — session expired or direct POST attempt
            String tenantId = resolveTenantFromUri(request.getRequestURI());
            String loginUrl = (tenantId != null) ? "/" + tenantId + "/login" : "/login";
            response.sendRedirect(loginUrl);
            return;
        }

        String username = (String) session.getAttribute(PENDING_MFA_USERNAME);
        String tenantId = (String) session.getAttribute(PENDING_MFA_TENANT);

        // Ensure TenantContextHolder is set so the routing datasource resolves correctly
        if (tenantId != null) {
            TenantContextHolder.setTenantId(tenantId);
        }

        String submittedCode = request.getParameter("code");
        if (submittedCode != null) {
            submittedCode = submittedCode.replaceAll("\\s", ""); // strip spaces
        }

        // ── 2. Load user and retrieve TOTP secret ─────────────────────────────
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

        // ── 3. Verify the TOTP code ────────────────────────────────────────────
        String rawSecret = totpService.decryptSecret(encryptedSecret);
        boolean codeValid = totpService.verifyCode(rawSecret, submittedCode);

        if (!codeValid) {
            log.warn("[MFA] Invalid TOTP code for user '{}' in tenant '{}'", username, tenantId);
            bruteForceProtectionService.recordFailedAttempt(username, tenantId);
            response.sendRedirect("/" + tenantId + "/mfa-verify?error");
            return;
        }

        // ── 4. Code is valid — complete Spring Security authentication ─────────
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
        Long userId = (Long) session.getAttribute(PENDING_MFA_USER_ID);

        sessionRecordingService.recordSession(userId, request);

        // ── 5. Clear pending session attributes ───────────────────────────────────
        clearPendingMfa(session);

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
    // ─────────────────────────────────────────────────────────────────────────

    private void clearPendingMfa(HttpSession session) {
        session.removeAttribute(PENDING_MFA_USERNAME);
        session.removeAttribute(PENDING_MFA_TENANT);
        session.removeAttribute(PENDING_MFA_USER_ID);
    }

    private static String resolveTenantFromUri(String uri) {
        if (uri == null || uri.equals("/")) return null;
        String[] parts = uri.split("/");
        for (String part : parts) {
            if (!part.isEmpty()) return part;
        }
        return null;
    }
}
