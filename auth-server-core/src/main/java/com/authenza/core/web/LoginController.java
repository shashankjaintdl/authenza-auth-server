package com.authenza.core.web;

import com.authenza.core.config.SuperAdminClientProperties;
import com.authenza.core.security.OAuthTransactionTokenService;
import com.authenza.core.security.OAuthTransactionContext;
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

import java.util.Optional;
import java.util.Set;

@Controller
public class LoginController {

    private final SuperAdminClientProperties superAdminClientProperties;
    private final RegisteredClientRepository registeredClientRepository;
    private final com.authenza.adapter.cache.TenantSettingsCache settingsCache;
    private final OAuthTransactionTokenService oauthTransactionTokenService;

    public LoginController(SuperAdminClientProperties superAdminClientProperties,
            RegisteredClientRepository registeredClientRepository,
            com.authenza.adapter.cache.TenantSettingsCache settingsCache,
            OAuthTransactionTokenService oauthTransactionTokenService) {
        this.superAdminClientProperties    = superAdminClientProperties;
        this.registeredClientRepository   = registeredClientRepository;
        this.settingsCache                 = settingsCache;
        this.oauthTransactionTokenService  = oauthTransactionTokenService;
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
            @RequestParam(name = "error",           required = false) String error,
            @RequestParam(name = "logout",          required = false) String logout,
            @RequestParam(name = "session_expired", required = false) String sessionExpired,
            @RequestParam(name = "tx",              required = false) String tx,
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

        boolean isSessionExpired = sessionExpired != null || request.getParameter("session_expired") != null;

        // Verify tx token if provided, preserving OAuth2 context across session boundaries
        boolean hasTx = tx != null && !tx.isBlank() &&
                oauthTransactionTokenService.verifyContext(tx, tenantId).isPresent();

        model.addAttribute("tenantId",      tenantId);
        model.addAttribute("sessionExpired", isSessionExpired);
        model.addAttribute("tx",            hasTx ? tx : null);   // passed to hidden form field in login.html

        String webAuthnEnabled = settingsCache.getSetting(tenantId, "webauthn_fingerprint_enabled");
        String globalWebAuthnEnabled = settingsCache.getSetting(tenantId, "global_webauthn_enabled");
        boolean isPasskeyEnabled = "true".equalsIgnoreCase(webAuthnEnabled) || "true".equalsIgnoreCase(globalWebAuthnEnabled);
        model.addAttribute("webauthnEnabled", isPasskeyEnabled);

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

        // 2. Stateless tx token check: if an already authenticated user visits /login?tx=..., replay authorize request
        String tx = request.getParameter("tx");
        if (tx != null && !tx.isBlank()) {
            Optional<OAuthTransactionContext> ctxOpt = oauthTransactionTokenService.verifyContext(tx, tenantId);
            if (ctxOpt.isPresent()) {
                String authorizeUrl = oauthTransactionTokenService.buildAuthorizeUrl(ctxOpt.get());
                return "redirect:" + authorizeUrl;
            }
        }

        // 3. Fallback: If no client context found, redirect to portal dashboard
        if ("system-admin".equals(tenantId)) {
            return "redirect:" + tenantPortalUrl + "/" + tenantId + "/dashboard";
        }

        // 3. Last Resort: Use Referer header (where the user just clicked "Back" from)
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.contains("/login")) {
            return "redirect:" + referer;
        }

        return "redirect:" + tenantPortalUrl + "/" + tenantId + "/dashboard";
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

    @GetMapping("/error/tenant-suspended")
    public String tenantSuspendedPage(
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "message", required = false) String message,
            Model model) {
        model.addAttribute("title", "Workspace Suspended");
        model.addAttribute("error", "Workspace Suspended");
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("message",
                message != null ? message
                        : (tenantId != null
                            ? "The workspace '" + tenantId + "' is currently suspended or inactive. Please contact your organization administrator or support to restore access."
                            : "This workspace is currently suspended or inactive. Please contact your organization administrator to restore access."));
        model.addAttribute("errorCode", "TENANT_SUSPENDED");
        model.addAttribute("status", 403);
        return "error-page";
    }
}
