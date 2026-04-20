package com.authenza.core.web;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.dto.ApiResponse;
import com.authenza.core.client.IamServiceClient;
import com.authenza.core.security.MfaAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Handles the TOTP MFA enrollment flow within the login journey.
 *
 * <p>This page is shown when a user whose account has {@code mfa_enabled = true}
 * has never completed setup (i.e. {@code mfa_secret} is null). It lets them:
 * <ol>
 *   <li>View a QR code to scan with Google Authenticator / Authy.</li>
 *   <li>Enter a 6-digit code to confirm their app is in sync.</li>
 *   <li>On success, proceed to the normal TOTP challenge ({@code /mfa-verify}).</li>
 * </ol>
 *
 * <p>Access is session-gated: {@code PENDING_MFA_USERNAME} must be present,
 * meaning the user has already passed the password step.
 */
@Controller
public class MfaSetupController {

    private static final Logger log = LoggerFactory.getLogger(MfaSetupController.class);
    private static final String VIEW = "mfa-setup";

    private final IamServiceClient iamServiceClient;

    public MfaSetupController(IamServiceClient iamServiceClient) {
        this.iamServiceClient = iamServiceClient;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET — show QR code
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders the MFA setup page with a QR code for scanning.
     * Calls the IAM service to generate the TOTP secret (idempotent on re-visit).
     */
    @GetMapping("/{tenantId}/mfa-setup")
    public String showSetup(@PathVariable String tenantId,
                            Model model,
                            HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        // Gate: only allow after successful password step
        if (!hasPendingMfa(session)) {
            return "redirect:/" + tenantId + "/login";
        }

        // Trigger CSRF token early
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrf != null) csrf.getToken();

        String username = (String) session.getAttribute(MfaAuthenticationFilter.PENDING_MFA_USERNAME);
        Long userId     = (Long)   session.getAttribute(MfaAuthenticationFilter.PENDING_MFA_USER_ID);

        // Ensure tenant context is set for the IAM service call
        TenantContextHolder.setTenantId(tenantId);

        // Call IAM to generate / retrieve the TOTP secret + QR code
        ApiResponse<?> setupResponse = iamServiceClient.setupMfa(tenantId, userId);

        if (setupResponse == null || !setupResponse.isSuccess()) {
            String errorMsg = (setupResponse != null && setupResponse.getMessage() != null)
                    ? setupResponse.getMessage()
                    : "Failed to generate MFA setup. Please try again.";
            log.warn("[MFA-SETUP] Setup failed for user '{}': {}", username, errorMsg);
            model.addAttribute("tenantId", tenantId);
            model.addAttribute("error", errorMsg);
            return VIEW;
        }

        // Extract qrCodeUri and secret from the response data map
        Object data = setupResponse.getData();
        String qrCodeUri = null;
        String secret    = null;

        if (data instanceof Map<?, ?> dataMap) {
            qrCodeUri = String.valueOf(dataMap.get("qrCodeUri"));
            secret    = String.valueOf(dataMap.get("secret"));
        }

        model.addAttribute("tenantId", tenantId);
        model.addAttribute("qrCodeUri", qrCodeUri);
        model.addAttribute("secret", secret);
        model.addAttribute("username", username);

        return VIEW;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST — confirm the scanned code
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Receives the 6-digit code from the user's authenticator app.
     * On success, MFA is marked active and the user is forwarded to the
     * TOTP challenge page (which will now have a secret to verify against).
     */
    @PostMapping("/{tenantId}/mfa-setup")
    public String confirmSetup(@PathVariable String tenantId,
                               @RequestParam(value = "code", defaultValue = "") String code,
                               Model model,
                               HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (!hasPendingMfa(session)) {
            return "redirect:/" + tenantId + "/login";
        }

        String username = (String) session.getAttribute(MfaAuthenticationFilter.PENDING_MFA_USERNAME);
        Long userId     = (Long)   session.getAttribute(MfaAuthenticationFilter.PENDING_MFA_USER_ID);

        TenantContextHolder.setTenantId(tenantId);

        // Strip spaces (some users manually type codes with a space in the middle)
        code = code.replaceAll("\\s", "");

        ApiResponse<?> confirmResponse = iamServiceClient.confirmMfa(tenantId, userId, code);

        if (confirmResponse == null || !confirmResponse.isSuccess()) {
            String errorMsg = (confirmResponse != null && confirmResponse.getMessage() != null)
                    ? confirmResponse.getMessage()
                    : "Invalid code. Please try again.";
            log.warn("[MFA-SETUP] Confirm failed for user '{}': {}", username, errorMsg);

            // Keep the QR code visible on re-try by regenerating it
            ApiResponse<?> setupResponse = iamServiceClient.setupMfa(tenantId, userId);
            if (setupResponse != null && setupResponse.isSuccess()) {
                Object data = setupResponse.getData();
                if (data instanceof Map<?, ?> dataMap) {
                    model.addAttribute("qrCodeUri", String.valueOf(dataMap.get("qrCodeUri")));
                    model.addAttribute("secret",    String.valueOf(dataMap.get("secret")));
                }
            }

            model.addAttribute("tenantId", tenantId);
            model.addAttribute("username", username);
            model.addAttribute("error", errorMsg);
            return VIEW;
        }

        log.info("[MFA-SETUP] Enrollment complete for user '{}' in tenant '{}'", username, tenantId);
        // Now that setup is confirmed, send the user to the normal TOTP challenge
        return "redirect:/" + tenantId + "/mfa-verify";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean hasPendingMfa(HttpSession session) {
        return session != null
                && session.getAttribute(MfaAuthenticationFilter.PENDING_MFA_USERNAME) != null;
    }
}
