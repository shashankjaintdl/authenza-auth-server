package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class TenantAwareAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareAuthenticationEntryPoint.class);

    /**
     * Injected after construction (the entry point is created with {@code new} inside
     * {@link SecurityConfig} rather than as a Spring bean, so setter injection is used).
     */
    private OAuthTransactionTokenService transactionTokenService;

    public TenantAwareAuthenticationEntryPoint(String loginFormUrl) {
        super(loginFormUrl);
    }

    /**
     * Called by {@link SecurityConfig} after construction to wire in the signing service.
     * When set, every redirect from {@code /oauth2/authorize} to the login page will carry
     * a signed {@code tx} token so the OAuth2 context survives session expiry.
     */
    public void setTransactionTokenService(OAuthTransactionTokenService svc) {
        this.transactionTokenService = svc;
    }

    @Override
    protected String buildRedirectUrlToLoginPage(HttpServletRequest request,
                                                 HttpServletResponse response,
                                                 AuthenticationException authException) {

        // 1. Try the thread-local context first (set by MultiTenantSecurityFilter)
        String tenantId = TenantContextHolder.getTenantId();

        // 2. Fallback: extract from the original request URI
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = resolveTenantFromUri(request.getRequestURI());
        }

        if (tenantId != null && !tenantId.isBlank()) {
            // e.g. "/{tenantId}/login"  →  "/customer2/login"
            String loginFormUrl = getLoginFormUrl().replace("{tenantId}", tenantId);

            // Append a signed tx token when redirecting from /oauth2/authorize so the
            // full OAuth2 context (client_id, redirect_uri, scope, state, PKCE) survives
            // the login form even after the HTTP session has completely expired.
            if (transactionTokenService != null && isOAuthAuthorizeRequest(request)) {
                try {
                    String tx = transactionTokenService.signContext(request, tenantId);
                    String encodedTx = URLEncoder.encode(tx, StandardCharsets.UTF_8);
                    log.debug("[EntryPoint] Appending tx token for tenant={}", tenantId);
                    return loginFormUrl + "?tx=" + encodedTx;
                } catch (Exception e) {
                    // Non-fatal: fall through to plain login URL without tx
                    log.warn("[EntryPoint] Failed to sign tx token, proceeding without it: {}", e.getMessage());
                }
            }

            return loginFormUrl;
        }

        // Provide a fallback to avoid infinite redirect to an encoded literal
        return "/error/invalid-tenant";
    }

    /**
     * Returns {@code true} if the request URI contains {@code /oauth2/authorize},
     * indicating this is an OAuth2 authorization flow redirect — not a direct login visit.
     */
    private static boolean isOAuthAuthorizeRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/oauth2/authorize");
    }

    private String resolveTenantFromUri(String uri) {
        if (uri == null || uri.equals("/")) return null;
        String[] parts = uri.split("/");
        for (String part : parts) {
            if (!part.isEmpty()) return part;
        }
        return null;
    }
}
