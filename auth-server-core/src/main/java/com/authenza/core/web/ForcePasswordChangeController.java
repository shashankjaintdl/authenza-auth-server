package com.authenza.core.web;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.dto.ApiResponse;
import com.authenza.core.client.IamServiceClient;
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

@Controller
public class ForcePasswordChangeController {

    private static final Logger log = LoggerFactory.getLogger(ForcePasswordChangeController.class);
    private static final String VIEW = "force-password-change";

    private final IamServiceClient iamServiceClient;

    public ForcePasswordChangeController(IamServiceClient iamServiceClient) {
        this.iamServiceClient = iamServiceClient;
    }

    @GetMapping("/{tenantId}/force-password-change")
    public String showForcePasswordChangePage(@PathVariable String tenantId,
                            Model model,
                            HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (!hasPendingPasswordChange(session)) {
            return "redirect:/" + tenantId + "/login";
        }

        // Trigger CSRF token early
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrf != null) csrf.getToken();

        String username = (String) session.getAttribute("PENDING_PASSWORD_CHANGE_USERNAME");

        model.addAttribute("tenantId", tenantId);
        model.addAttribute("username", username);

        return VIEW;
    }

    @PostMapping("/{tenantId}/force-password-change")
    public String processForcePasswordChange(@PathVariable String tenantId,
                               @RequestParam(value = "newPassword", defaultValue = "") String newPassword,
                               @RequestParam(value = "confirmPassword", defaultValue = "") String confirmPassword,
                               Model model,
                               HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (!hasPendingPasswordChange(session)) {
            return "redirect:/" + tenantId + "/login";
        }

        String username = (String) session.getAttribute("PENDING_PASSWORD_CHANGE_USERNAME");
        Long userId     = (Long)   session.getAttribute("PENDING_PASSWORD_CHANGE_USER_ID");

        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("tenantId", tenantId);
            model.addAttribute("username", username);
            model.addAttribute("error", "Passwords do not match.");
            return VIEW;
        }

        TenantContextHolder.setTenantId(tenantId);

        ApiResponse<?> response = iamServiceClient.forceChangePassword(tenantId, userId, newPassword);

        if (response == null || !response.isSuccess()) {
            String errorMsg = (response != null && response.getMessage() != null)
                    ? response.getMessage()
                    : "Failed to update password. Please try again.";
            log.warn("[FORCE-PWD-CHANGE] Failed for user '{}': {}", username, errorMsg);

            model.addAttribute("tenantId", tenantId);
            model.addAttribute("username", username);
            model.addAttribute("error", errorMsg);
            return VIEW;
        }

        log.info("[FORCE-PWD-CHANGE] Completed for user '{}' in tenant '{}'", username, tenantId);
        
        // Invalidate the session attributes
        session.removeAttribute("PENDING_PASSWORD_CHANGE_USERNAME");
        session.removeAttribute("PENDING_PASSWORD_CHANGE_TENANT");
        session.removeAttribute("PENDING_PASSWORD_CHANGE_USER_ID");

        // Redirect to login with a success message indicator
        return "redirect:/" + tenantId + "/login?passwordChanged=true";
    }

    private boolean hasPendingPasswordChange(HttpSession session) {
        return session != null
                && session.getAttribute("PENDING_PASSWORD_CHANGE_USERNAME") != null;
    }
}
