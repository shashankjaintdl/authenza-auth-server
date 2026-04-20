package com.authenza.core.web;

import com.authenza.core.security.MfaAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the TOTP challenge page {@code /{tenantId}/mfa-verify}.
 *
 * <p>The GET handler gates access: if the session does not contain a
 * {@code PENDING_MFA_USERNAME}, the user is redirected back to the login page.
 * This prevents direct navigation to the verification page without first
 * completing the password step.
 *
 * <p>The POST to {@code /{tenantId}/mfa-verify} is handled by
 * {@link MfaAuthenticationFilter}, not by this controller.
 */
@Controller
public class MfaChallengeController {

    /**
     * Renders the TOTP challenge page.
     * Requires a valid {@code PENDING_MFA_USERNAME} in the session.
     */
    @GetMapping("/{tenantId}/mfa-verify")
    public String showMfaChallenge(@PathVariable String tenantId,
                                   Model model,
                                   HttpServletRequest request) {
        // Force CSRF token early to avoid session commit issues
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        // Gate: only allow access if password was already validated
        HttpSession session = request.getSession(false);
        if (session == null
                || session.getAttribute(MfaAuthenticationFilter.PENDING_MFA_USERNAME) == null) {
            return "redirect:/" + tenantId + "/login";
        }

        model.addAttribute("tenantId", tenantId);
        return "mfa-verify";
    }
}
