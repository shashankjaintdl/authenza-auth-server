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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Serves the Thymeleaf Sign Up pages and proxies form submissions
 * to {@code auth-iam-service} via {@link IamServiceClient}.
 *
 * <p>Also exposes lightweight JSON endpoints for real-time email
 * and username availability checks consumed by JavaScript on the
 * registration form.</p>
 */
@Controller
public class RegisterController {

    private static final Logger log = LoggerFactory.getLogger(RegisterController.class);

    private final IamServiceClient iamClient;

    public RegisterController(IamServiceClient iamClient) {
        this.iamClient = iamClient;
    }

    // ─────────────────────────────────────────────
    // Page Routes (Thymeleaf views)
    // ─────────────────────────────────────────────

    /**
     * Renders the registration form.
     */
    @GetMapping("/{tenantId}/register")
    public String showRegistrationForm(@PathVariable String tenantId, Model model, jakarta.servlet.http.HttpServletRequest request) {
        // Force CSRF token and Session creation early to avoid "Cannot create a session after the response has been committed"
        CsrfToken csrfToken =
                (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        model.addAttribute("tenantId", tenantId);
        model.addAttribute("form", new RegistrationForm());
        return "register";
    }

    /**
     * Handles the registration form submission.
     * Proxies the request to auth-iam-service and shows
     * success or re-renders the form with error messages.
     */
    @PostMapping("/{tenantId}/register")
    public String handleRegistration(@PathVariable String tenantId,
                                     @ModelAttribute("form") RegistrationForm form,
                                     Model model,
                                     HttpServletRequest request) {
        // Force CSRF token early
        CsrfToken csrfToken =
                (CsrfToken) request.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        model.addAttribute("tenantId", tenantId);

        // 1. Validate password confirmation locally (never sent to IAM)
        if (form.getPassword() == null || !form.getPassword().equals(form.getConfirmPassword())) {
            model.addAttribute("errorMessage", "Passwords do not match.");
            return "register";
        }

        // 2. Resolve username — if toggle is ON, use email as username
        String username = form.isUseEmailAsUsername()
                ? form.getEmail()
                : form.getPreferredUsername();

        // 3. Build the registration payload for IAM service
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("email", form.getEmail());
        payload.put("preferredUsername", username);
        payload.put("givenName", form.getGivenName());
        payload.put("familyName", form.getFamilyName());
        payload.put("password", form.getPassword());

        // 4. Proxy to auth-iam-service
        ApiResponse<?> response = iamClient.registerUser(tenantId, payload);

        if (response.isSuccess()) {
            // Success — show the "check your email" confirmation
            model.addAttribute("registrationSuccess", true);
            model.addAttribute("registeredEmail", form.getEmail());
            return "register";
        } else {
            // Error — re-render form with the IAM service error message
            model.addAttribute("errorMessage", response.getMessage());
            return "register";
        }
    }

    /**
     * Proxies the email verification link click to auth-iam-service.
     * Shows a success or error page depending on the token validity.
     */
    @GetMapping("/{tenantId}/verify-email")
    public String verifyEmail(@PathVariable String tenantId,
                              @RequestParam("token") String token,
                              Model model) {

        model.addAttribute("tenantId", tenantId);

        ApiResponse<?> response = iamClient.verifyEmail(tenantId, token);

        if (response.isSuccess()) {
            model.addAttribute("verified", true);
            model.addAttribute("message", "Your email has been verified successfully. You can now sign in.");
        } else {
            model.addAttribute("verified", false);
            model.addAttribute("message", response.getMessage());
        }

        return "verify-success";
    }

    // ─────────────────────────────────────────────
    // JSON Endpoints (for AJAX availability checks)
    // ─────────────────────────────────────────────

    /**
     * Real-time email availability check.
     * Called by debounced JavaScript on the registration form.
     */
    @GetMapping("/{tenantId}/api/check-email")
    @ResponseBody
    public Map<String, Object> checkEmailAvailability(
            @PathVariable String tenantId,
            @RequestParam("email") String email) {

        boolean available = iamClient.isEmailAvailable(tenantId, email);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("message", available ? "Email is available" : "This email is already registered");
        return result;
    }

    /**
     * Real-time username availability check.
     * Called by debounced JavaScript on the registration form.
     */
    @GetMapping("/{tenantId}/api/check-username")
    @ResponseBody
    public Map<String, Object> checkUsernameAvailability(
            @PathVariable String tenantId,
            @RequestParam("username") String username) {

        boolean available = iamClient.isUsernameAvailable(tenantId, username);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("message", available ? "Username is available" : "This username is already taken");
        return result;
    }
}
