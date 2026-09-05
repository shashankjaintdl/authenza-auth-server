package com.authenza.core.web;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.dto.ApiResponse;
import com.authenza.core.client.IamServiceClient;
import com.authenza.core.security.AuthPendingStateStore;
import jakarta.servlet.http.HttpServletRequest;
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
 * <p>Access is gated by a {@code mfaToken} query parameter backed by a Redis key,
 * meaning the user has already passed the password step.
 */
@Controller
public class MfaSetupController {

    private static final Logger log = LoggerFactory.getLogger(MfaSetupController.class);
    private static final String VIEW = "mfa-setup";

    private final IamServiceClient iamServiceClient;
    private final AuthPendingStateStore authPendingStateStore;

    public MfaSetupController(IamServiceClient iamServiceClient,
                               AuthPendingStateStore authPendingStateStore) {
        this.iamServiceClient = iamServiceClient;
        this.authPendingStateStore = authPendingStateStore;
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
                            @RequestParam(required = false) String mfaToken,
                            Model model,
                            HttpServletRequest request) {

        // Gate: only allow after successful password step
        if (!authPendingStateStore.hasPendingState(AuthPendingStateStore.TYPE_MFA, mfaToken)) {
            return "redirect:/" + tenantId + "/login";
        }

        // Trigger CSRF token early
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrf != null) csrf.getToken();

        // Peek at pending state without consuming it (hasPendingState already confirmed it exists)
        AuthPendingStateStore.PendingState pendingState =
                authPendingStateStore.getAndClearPendingState(AuthPendingStateStore.TYPE_MFA, mfaToken);
        if (pendingState == null) {
            return "redirect:/" + tenantId + "/login";
        }

        // Re-save the state so /mfa-setup POST and subsequent /mfa-verify still work
        String newMfaToken = authPendingStateStore.savePendingState(AuthPendingStateStore.TYPE_MFA, pendingState);

        String username = pendingState.username();
        Long userId     = pendingState.userId();

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
            model.addAttribute("mfaToken", newMfaToken);
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
        model.addAttribute("mfaToken", newMfaToken);
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
                               @RequestParam(required = false) String mfaToken,
                               @RequestParam(value = "code", defaultValue = "") String code,
                               Model model,
                               HttpServletRequest request) {

        // Gate: only allow after successful password step
        if (!authPendingStateStore.hasPendingState(AuthPendingStateStore.TYPE_MFA, mfaToken)) {
            return "redirect:/" + tenantId + "/login";
        }

        AuthPendingStateStore.PendingState pendingState =
                authPendingStateStore.getAndClearPendingState(AuthPendingStateStore.TYPE_MFA, mfaToken);
        if (pendingState == null) {
            return "redirect:/" + tenantId + "/login";
        }

        String username = pendingState.username();
        Long userId     = pendingState.userId();

        TenantContextHolder.setTenantId(tenantId);

        // Strip spaces (some users manually type codes with a space in the middle)
        code = code.replaceAll("\\s", "");

        ApiResponse<?> confirmResponse = iamServiceClient.confirmMfa(tenantId, userId, code);

        if (confirmResponse == null || !confirmResponse.isSuccess()) {
            String errorMsg = (confirmResponse != null && confirmResponse.getMessage() != null)
                    ? confirmResponse.getMessage()
                    : "Invalid code. Please try again.";
            log.warn("[MFA-SETUP] Confirm failed for user '{}': {}", username, errorMsg);

            // Re-save the state so the user can retry
            String retryToken = authPendingStateStore.savePendingState(AuthPendingStateStore.TYPE_MFA, pendingState);

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
            model.addAttribute("mfaToken", retryToken);
            model.addAttribute("username", username);
            model.addAttribute("error", errorMsg);
            return VIEW;
        }

        // Re-save state so the subsequent mfa-verify step can read it
        String newMfaToken = authPendingStateStore.savePendingState(AuthPendingStateStore.TYPE_MFA, pendingState);

        log.info("[MFA-SETUP] Enrollment complete for user '{}' in tenant '{}'", username, tenantId);
        // Now that setup is confirmed, send the user to the normal TOTP challenge
        return "redirect:/" + tenantId + "/mfa-verify?mfaToken=" + newMfaToken;
    }
}
