package com.authenza.core.web;

import com.authenza.core.security.JdbcTenantUserDetailsService;
import com.authenza.core.service.SessionRecordingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Completes the FIDO2/WebAuthn passwordless login flow inside {@code auth-server-core}.
 *
 * <p><b>Why does this controller exist?</b><br>
 * The WebAuthn cryptographic ceremony (challenge generation and assertion verification)
 * runs entirely in {@code auth-iam-service} (port 8082) because that is where the
 * FIDO2 library ({@code yubico/webauthn-server-core}) and the credential database live.
 * However, the OAuth2 / OIDC session that the browser needs to complete the login lives
 * in {@code auth-server-core} (port 8081) as a Spring Security {@code SecurityContext}
 * stored in the HTTP session.</p>
 *
 * <p><b>The Bridge Pattern</b><br>
 * After {@code auth-iam-service} successfully verifies a passkey assertion, it issues a
 * short-lived (60-second) HMAC-SHA256 signed <em>bridge token</em> containing the
 * {@code userId} and {@code tenantId}. The login page JavaScript POSTs this token here.
 * This controller:</p>
 * <ol>
 *   <li>Validates the HMAC signature using the shared secret.</li>
 *   <li>Checks the 60-second TTL to reject replayed tokens.</li>
 *   <li>Loads the user's {@link UserDetails} from the tenant DB.</li>
 *   <li>Creates a Spring Security {@link UsernamePasswordAuthenticationToken} and
 *       stores it in the HTTP session — exactly the same state that would result from
 *       a successful username/password form submission.</li>
 *   <li>Records the session for Active Devices tracking.</li>
 *   <li>Redirects to the pending OAuth2 {@code /authorize} saved request (if present)
 *       or falls back to {@code /{tenantId}/} to complete the OIDC code flow.</li>
 * </ol>
 *
 * <p><b>Security</b>: The bridge token is validated with a constant-time HMAC comparison
 * to prevent timing attacks. The shared secret must never be transmitted over the network.</p>
 */
@Controller
public class WebAuthnBridgeController {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnBridgeController.class);
    private static final long TOKEN_TTL_MS = 60_000; // Must match WebAuthnBridgeTokenService

    private final JdbcTenantUserDetailsService userDetailsService;
    private final SessionRecordingService sessionRecordingService;
    private final byte[] secretKeyBytes;

    public WebAuthnBridgeController(
            JdbcTenantUserDetailsService userDetailsService,
            SessionRecordingService sessionRecordingService,
            @Value("${app.webauthn.bridge-secret}") String bridgeSecret) {
        this.userDetailsService = userDetailsService;
        this.sessionRecordingService = sessionRecordingService;
        this.secretKeyBytes = HexFormat.of().parseHex(bridgeSecret);
    }

    /**
     * Accepts a signed bridge token from the login page JavaScript, validates it,
     * establishes a Spring Security session, and redirects to complete the OIDC flow.
     *
     * <p>This endpoint is {@code permitAll()} — it is the equivalent of the login form
     * POST endpoint but for WebAuthn. The bridge token itself is the authentication proof.</p>
     *
     * @param tenantId    the tenant the user is logging into (from the URL path)
     * @param bridgeToken the HMAC-SHA256 signed token issued by auth-iam-service
     * @param request     the HTTP request (used to resolve the saved OAuth2 authorize request)
     * @param response    the HTTP response (used for redirect)
     * @return a redirect to the pending OAuth2 authorize URL or /{tenantId}/
     */
    @PostMapping("/{tenantId}/webauthn/bridge")
    public String completeBridgeLogin(
            @PathVariable String tenantId,
            @RequestParam("bridgeToken") String bridgeToken,
            HttpServletRequest request,
            HttpServletResponse response) {

        // ── 1. Validate the bridge token ────────────────────────────────────────
        BridgeClaims claims;
        try {
            claims = validateToken(bridgeToken, tenantId);
        } catch (IllegalArgumentException e) {
            log.warn("[WebAuthn Bridge] Token validation failed for tenant={}: {}", tenantId, e.getMessage());
            return "redirect:/" + tenantId + "/login?error";
        }

        // ── 2. Load user details from tenant DB ──────────────────────────────────
        // We look up by userId. JdbcTenantUserDetailsService's loadUserId is based on username,
        // so we use the JDBC template to do a reverse lookup from ID → username.
        String username;
        try {
            username = userDetailsService.loadUsernameById(claims.userId());
            if (username == null) {
                log.warn("[WebAuthn Bridge] No user found for userId={} in tenant={}", claims.userId(), tenantId);
                return "redirect:/" + tenantId + "/login?error";
            }
        } catch (Exception e) {
            log.error("[WebAuthn Bridge] Failed to load user for userId={}: {}", claims.userId(), e.getMessage());
            return "redirect:/" + tenantId + "/login?error";
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        // ── 3. Check account state (locked, disabled) ────────────────────────────
        if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
            log.warn("[WebAuthn Bridge] Account not active for userId={}, tenant={}", claims.userId(), tenantId);
            return "redirect:/" + tenantId + "/login?locked";
        }

        // ── 3.5 Force Password Change Gate ─────────────────────────────────────────
        // Even if they log in with a passkey, if an admin set a temporary password and
        // requires it to be changed, we must block full authentication and force the change.
        if (userDetailsService.isPasswordChangeRequired(username)) {
            log.info("[WebAuthn Bridge] Passkey user {} needs password change. Redirecting...", username);
            
            HttpSession forceChangeSession = request.getSession(true);
            // Ensure no lingering security context
            forceChangeSession.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            
            forceChangeSession.setAttribute("PENDING_PASSWORD_CHANGE_USERNAME", username);
            forceChangeSession.setAttribute("PENDING_PASSWORD_CHANGE_TENANT", tenantId);
            forceChangeSession.setAttribute("PENDING_PASSWORD_CHANGE_USER_ID", claims.userId());

            return "redirect:/" + tenantId + "/force-password-change";
        }

        // ── 4. Establish Spring Security session ────────────────────────────────
        // This is the same as what Spring's form login does after password verification.
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken
                .authenticated(userDetails, null, userDetails.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        // Persist the SecurityContext into the HTTP session so subsequent requests
        // on this session are treated as authenticated.
        HttpSession session = request.getSession(true);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        // ── 5. Record session for Active Devices ────────────────────────────────
        try {
            sessionRecordingService.recordSession(claims.userId(), request);
        } catch (Exception e) {
            // Non-fatal — session tracking failure must not block login
            log.warn("[WebAuthn Bridge] Session recording failed: {}", e.getMessage());
        }

        log.info("[WebAuthn Bridge] Successfully established session for userId={}, tenant={}",
                claims.userId(), tenantId);

        // ── 6. Redirect to complete the OAuth2 authorize flow ───────────────────
        // If the user arrived via an OAuth2 /authorize redirect (normal OIDC flow),
        // Spring will have saved the original /authorize URL in the session.
        // Following it completes the authorization code exchange and issues tokens.
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            return "redirect:" + savedRequest.getRedirectUrl();
        }

        // No pending authorize request — user navigated directly to the login page.
        return "redirect:/" + tenantId + "/";
    }

    // ── Token Validation (mirrors WebAuthnBridgeTokenService in auth-iam-service) ──

    private BridgeClaims validateToken(String token, String expectedTenantId) {
        String[] parts = token.split("\\.");
        if (parts.length != 2) throw new IllegalArgumentException("Malformed token.");

        String payload = new String(
                Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String receivedSig = parts[1];

        String[] fields = payload.split(":");
        if (fields.length != 3) throw new IllegalArgumentException("Malformed token payload.");

        long expiry = Long.parseLong(fields[2]);
        if (System.currentTimeMillis() > expiry)
            throw new IllegalArgumentException("Bridge token has expired.");

        String tokenTenantId = fields[1];
        if (!expectedTenantId.equals(tokenTenantId))
            throw new IllegalArgumentException("Tenant mismatch in bridge token.");

        // Constant-time HMAC verification
        String expectedSig = sign(payload);
        if (!constantTimeEquals(expectedSig, receivedSig))
            throw new IllegalArgumentException("Bridge token signature invalid.");

        return new BridgeClaims(Long.parseLong(fields[0]), tokenTenantId);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKeyBytes, "HmacSHA256"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed.", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }

    private record BridgeClaims(Long userId, String tenantId) {}
}
