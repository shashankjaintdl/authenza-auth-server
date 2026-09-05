package com.authenza.core.web;

import com.authenza.core.security.AuthPendingStateStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the TOTP challenge page {@code /{tenantId}/mfa-verify}.
 *
 * <p>The GET handler gates access: if {@code mfaToken} is missing or has already
 * expired in Redis, the user is redirected back to the login page.
 * This prevents direct navigation to the verification page without first
 * completing the password step.
 *
 * <p>The POST to {@code /{tenantId}/mfa-verify} is handled by
 * {@link com.authenza.core.security.MfaAuthenticationFilter}, not by this controller.
 */
@Controller
public class MfaChallengeController {

    private final AuthPendingStateStore authPendingStateStore;

    public MfaChallengeController(AuthPendingStateStore authPendingStateStore) {
        this.authPendingStateStore = authPendingStateStore;
    }

    /**
     * Renders the TOTP challenge page.
     * Requires a valid {@code mfaToken} request parameter backed by a live Redis key.
     */
    @GetMapping("/{tenantId}/mfa-verify")
    public String showMfaChallenge(@PathVariable String tenantId,
                                   @RequestParam(required = false) String mfaToken,
                                   Model model,
                                   HttpServletRequest request) {
        // Force CSRF token early to avoid session commit issues
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        // Gate: only allow access if a valid pending MFA state exists in Redis
        if (!authPendingStateStore.hasPendingState(AuthPendingStateStore.TYPE_MFA, mfaToken)) {
            return "redirect:/" + tenantId + "/login";
        }

        model.addAttribute("tenantId", tenantId);
        model.addAttribute("mfaToken", mfaToken);
        return "mfa-verify";
    }
}
