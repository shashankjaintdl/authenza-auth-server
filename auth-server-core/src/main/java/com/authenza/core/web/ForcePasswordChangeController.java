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

@Controller
public class ForcePasswordChangeController {

    private static final Logger log = LoggerFactory.getLogger(ForcePasswordChangeController.class);
    private static final String VIEW = "force-password-change";

    private final IamServiceClient iamServiceClient;
    private final AuthPendingStateStore authPendingStateStore;

    public ForcePasswordChangeController(IamServiceClient iamServiceClient,
                                         AuthPendingStateStore authPendingStateStore) {
        this.iamServiceClient = iamServiceClient;
        this.authPendingStateStore = authPendingStateStore;
    }

    @GetMapping("/{tenantId}/force-password-change")
    public String showForcePasswordChangePage(@PathVariable String tenantId,
                            @RequestParam(required = false) String pwdToken,
                            Model model,
                            HttpServletRequest request) {

        if (!authPendingStateStore.hasPendingState(AuthPendingStateStore.TYPE_PWD_CHANGE, pwdToken)) {
            return "redirect:/" + tenantId + "/login";
        }

        // Trigger CSRF token early
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrf != null) csrf.getToken();

        // Peek at state without consuming it (needed for the POST step)
        AuthPendingStateStore.PendingState pendingState =
                authPendingStateStore.getAndClearPendingState(AuthPendingStateStore.TYPE_PWD_CHANGE, pwdToken);
        if (pendingState == null) {
            return "redirect:/" + tenantId + "/login";
        }

        // Re-save so the POST handler can still read it
        String newPwdToken = authPendingStateStore.savePendingState(AuthPendingStateStore.TYPE_PWD_CHANGE, pendingState);

        model.addAttribute("tenantId", tenantId);
        model.addAttribute("pwdToken", newPwdToken);
        model.addAttribute("username", pendingState.username());

        return VIEW;
    }

    @PostMapping("/{tenantId}/force-password-change")
    public String processForcePasswordChange(@PathVariable String tenantId,
                               @RequestParam(required = false) String pwdToken,
                               @RequestParam(value = "newPassword", defaultValue = "") String newPassword,
                               @RequestParam(value = "confirmPassword", defaultValue = "") String confirmPassword,
                               Model model,
                               HttpServletRequest request) {

        AuthPendingStateStore.PendingState pendingState =
                authPendingStateStore.getAndClearPendingState(AuthPendingStateStore.TYPE_PWD_CHANGE, pwdToken);

        if (pendingState == null) {
            return "redirect:/" + tenantId + "/login";
        }

        String username = pendingState.username();
        Long userId     = pendingState.userId();

        if (!newPassword.equals(confirmPassword)) {
            // Re-save state for retry
            String retryToken = authPendingStateStore.savePendingState(AuthPendingStateStore.TYPE_PWD_CHANGE, pendingState);
            model.addAttribute("tenantId", tenantId);
            model.addAttribute("pwdToken", retryToken);
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

            // Re-save state so user can retry
            String retryToken = authPendingStateStore.savePendingState(AuthPendingStateStore.TYPE_PWD_CHANGE, pendingState);
            model.addAttribute("tenantId", tenantId);
            model.addAttribute("pwdToken", retryToken);
            model.addAttribute("username", username);
            model.addAttribute("error", errorMsg);
            return VIEW;
        }

        log.info("[FORCE-PWD-CHANGE] Completed for user '{}' in tenant '{}'", username, tenantId);

        // State was already consumed by getAndClearPendingState — no further cleanup needed.
        return "redirect:/" + tenantId + "/login?passwordChanged=true";
    }
}
