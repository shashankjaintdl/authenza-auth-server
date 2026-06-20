package com.authenza.core.web;

import com.authenza.core.config.SuperAdminClientProperties;
import jakarta.servlet.http.*;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Set;

@Controller
public class LoginController {

    private final SuperAdminClientProperties superAdminClientProperties;
    private final RegisteredClientRepository registeredClientRepository;
    private final com.authenza.adapter.cache.TenantSettingsCache settingsCache;

    public LoginController(SuperAdminClientProperties superAdminClientProperties,
            RegisteredClientRepository registeredClientRepository,
            com.authenza.adapter.cache.TenantSettingsCache settingsCache) {
        this.superAdminClientProperties = superAdminClientProperties;
        this.registeredClientRepository = registeredClientRepository;
        this.settingsCache = settingsCache;
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

    @org.springframework.beans.factory.annotation.Value("${app.services.tenant-portal-url:http://localhost:4200}")
    private String tenantPortalUrl;

    /**
     * Fallback for lost OAuth2 sessions.
     * If a user sits on the login page for 5 hours, the HTTP session expires, and
     * Spring Security loses the original /authorize request. After successful
     * login,
     * it redirects to /{tenantId}/. This endpoint catches that redirect and bounces
     * the user back to the Angular portal.
     */
    @GetMapping({"/{tenantId}", "/{tenantId}/"})
    public String tenantRootRedirect(@PathVariable String tenantId) {
        return "redirect:" + tenantPortalUrl + "/" + tenantId + "/";
    }

    @GetMapping("/{tenantId}/login")
    public String loginPage(@PathVariable String tenantId,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "logout", required = false) String logout,
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication,
            Model model) {

        // If user is already authenticated and not an anonymous user,
        // redirect them away from the login form to prevent them from seeing it
        // after hitting the 'Back' button from the dashboard.
        if (authentication != null && authentication.isAuthenticated() &&
                !(authentication instanceof AnonymousAuthenticationToken)) {
            return getAuthenticatedUserRedirect(tenantId, request, response);
        }

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

        String webAuthnEnabled = settingsCache.getSetting(tenantId, "webauthn_fingerprint_enabled");
        model.addAttribute("webauthnEnabled", "true".equalsIgnoreCase(webAuthnEnabled));

        return "login";
    }

    private String getAuthenticatedUserRedirect(String tenantId, jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        // 1. Try to find the client_id from the original OAuth2 request in the session
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);

        if (savedRequest != null) {
            String[] clientIds = savedRequest.getParameterValues("client_id");
            if (clientIds != null && clientIds.length > 0) {
                RegisteredClient client = registeredClientRepository.findByClientId(clientIds[0]);
                if (client != null && !client.getRedirectUris().isEmpty()) {
                    // Dynamically use the FIRST registered redirect URI as the base (stripping
                    // path/query)
                    String redirectUri = client.getRedirectUris().iterator().next();
                    String baseUrl = UriComponentsBuilder.fromUriString(redirectUri)
                            .replacePath(null)
                            .replaceQuery(null)
                            .build()
                            .toUriString();

                    return "redirect:" + baseUrl + "/" + tenantId;
                }
            }
        }

        // 2. Fallback: If no client context found, default to system admin behavior
        if ("system-admin".equals(tenantId)) {
            return "redirect:/";
        }

        // 3. Last Resort: Use Referer header (where the user just clicked "Back" from)
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.contains("/login")) {
            return "redirect:" + referer;
        }

        return "redirect:/";
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
