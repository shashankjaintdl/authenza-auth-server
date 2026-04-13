package com.authenza.core.web;

import com.authenza.common.dto.ApiResponse;
import com.authenza.core.client.IamServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Serves the Thymeleaf "Forgot Password" and "Reset Password" pages
 * and proxies form submissions to {@code auth-iam-service} via
 * {@link IamServiceClient}.
 *
 * <p>This controller handles the complete self-service password recovery
 * flow: requesting a reset link, validating the token, and setting a
 * new password.</p>
 */
@Controller
public class ForgotPasswordController {

    private static final Logger log = LoggerFactory.getLogger(ForgotPasswordController.class);

    private final IamServiceClient iamClient;

    public ForgotPasswordController(IamServiceClient iamClient) {
        this.iamClient = iamClient;
    }

    // ─────────────────────────────────────────────
    // Forgot Password (Request Reset Link)
    // ─────────────────────────────────────────────

    /**
     * Renders the "Forgot Password" form where the user enters their email.
     */
    @GetMapping("/{tenantId}/forgot-password")
    public String showForgotPasswordForm(@PathVariable String tenantId,
                                         Model model,
                                         HttpServletRequest request) {
        forceCsrfToken(request);
        model.addAttribute("tenantId", tenantId);
        return "forgot-password";
    }

    /**
     * Handles the forgot password form submission.
     * Proxies to auth-iam-service, always shows success (anti-enumeration).
     */
    @PostMapping("/{tenantId}/forgot-password")
    public String handleForgotPassword(@PathVariable String tenantId,
                                       @RequestParam("email") String email,
                                       Model model,
                                       HttpServletRequest request) {
        forceCsrfToken(request);
        model.addAttribute("tenantId", tenantId);

        iamClient.requestPasswordReset(tenantId, email);

        // Always show success regardless of whether the email exists
        model.addAttribute("resetRequested", true);
        model.addAttribute("submittedEmail", email);
        return "forgot-password";
    }

    // ─────────────────────────────────────────────
    // Reset Password (Set New Password)
    // ─────────────────────────────────────────────

    /**
     * Renders the "Reset Password" form after the user clicks the email link.
     * First validates the token — if invalid/expired, shows an error.
     */
    @GetMapping("/{tenantId}/reset-password")
    public String showResetPasswordForm(@PathVariable String tenantId,
                                        @RequestParam("token") String token,
                                        Model model,
                                        HttpServletRequest request) {
        forceCsrfToken(request);
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("token", token);
        model.addAttribute("tokenInvalid", false);
        model.addAttribute("resetSuccess", false);

        // Validate the token before showing the form
        ApiResponse<?> response = iamClient.validateResetToken(tenantId, token);

        if (!response.isSuccess()) {
            model.addAttribute("tokenInvalid", true);
            model.addAttribute("errorMessage", response.getMessage());
        }

        return "reset-password";
    }

    /**
     * Handles the reset password form submission.
     * Proxies to auth-iam-service and shows success or error.
     */
    @PostMapping("/{tenantId}/reset-password")
    public String handleResetPassword(@PathVariable String tenantId,
                                      @RequestParam("token") String token,
                                      @RequestParam("newPassword") String newPassword,
                                      @RequestParam("confirmPassword") String confirmPassword,
                                      Model model,
                                      HttpServletRequest request) {
        forceCsrfToken(request);
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("token", token);
        model.addAttribute("tokenInvalid", false);
        model.addAttribute("resetSuccess", false);

        // Validate password confirmation locally
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            model.addAttribute("errorMessage", "Passwords do not match.");
            return "reset-password";
        }

        if (newPassword.length() < 8) {
            model.addAttribute("errorMessage", "Password must be at least 8 characters.");
            return "reset-password";
        }

        // Proxy to auth-iam-service
        ApiResponse<?> response = iamClient.resetPassword(tenantId, token, newPassword);

        if (response.isSuccess()) {
            model.addAttribute("resetSuccess", true);
        } else {
            model.addAttribute("tokenInvalid", true);
            model.addAttribute("errorMessage", response.getMessage());
        }

        return "reset-password";
    }

    /**
     * Forces CSRF token generation early to prevent
     * "Cannot create a session after the response has been committed" errors.
     */
    private void forceCsrfToken(HttpServletRequest request) {
        CsrfToken csrfToken =
                (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
    }
}
