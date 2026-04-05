package com.authenza.core.web;

import com.authenza.core.config.SuperAdminClientProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Set;

@Controller
public class LoginController {

    private final SuperAdminClientProperties superAdminClientProperties;

    public LoginController(SuperAdminClientProperties superAdminClientProperties) {
        this.superAdminClientProperties = superAdminClientProperties;
    }

    /**
     * Microsoft-style OAuth2 flow: when user hits the base URL (e.g.
     * localhost:8081/),
     * redirect to the master tenant's OAuth2 authorize endpoint using the
     * super-admin first-party client. The authorization server validates the
     * request, shows the login page, and after authentication issues an
     * authorization code back to the client's redirect URI.
     */
    @GetMapping("/")
    public String rootRedirect() {
        String tenantId = superAdminClientProperties.getTenantId();
        String clientId = superAdminClientProperties.getClientId();

        // Use the first-party super-admin client's redirect URI
        Set<String> redirectUris = superAdminClientProperties.getRedirectUris();
        String redirectUri = redirectUris.iterator().next();

        // Build scopes as space-separated string
        String scope = String.join(" ", superAdminClientProperties.getScopes());

        String authorizeUrl = UriComponentsBuilder
                .fromPath("/" + tenantId + "/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("scope", scope)
                .queryParam("redirect_uri", redirectUri)
                .build()
                .toUriString();

        return "redirect:" + authorizeUrl;
    }

    @GetMapping("/{tenantId}/login")
    public String loginPage(@PathVariable String tenantId,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "logout", required = false) String logout,
            HttpServletRequest request,
            Model model) {

        // Allow ?error and ?logout — these mean the user already went through
        // the OAuth2 authorize flow and is returning after a failed attempt or logout
        if (error == null && logout == null) {
            // Check for a pending OAuth2 authorization request in the session.
            // This is set when /oauth2/authorize redirects an unauthenticated user.
            HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
            SavedRequest savedRequest = requestCache.getRequest(request, null);

            if (savedRequest == null) {
                // No pending OAuth2 request — user accessed login page directly
                model.addAttribute("title", "Unauthorized Access");
                model.addAttribute("message",
                        "Login page can only be accessed through a valid OAuth2 client authorization flow. " +
                                "Please use an authorized client application to initiate login.");
                model.addAttribute("errorCode", "MISSING_AUTH_REQUEST");
                return "error-page";
            }
        }

        model.addAttribute("tenantId", tenantId);
        return "login";
    }

    @GetMapping("/error/invalid-tenant")
    public String invalidTenantPage(
            @RequestParam(name = "message", required = false) String message,
            Model model) {
        model.addAttribute("title", "Invalid Tenant");
        model.addAttribute("message",
                message != null ? message
                        : "No valid tenant was found in the request. Please check the URL and try again.");
        model.addAttribute("errorCode", "TENANT_NOT_FOUND");
        return "error-page";
    }
}
