package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

public final class TenantAwareAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

    public TenantAwareAuthenticationEntryPoint(String loginFormUrl) {
        super(loginFormUrl);
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
            return loginFormUrl;
        }

        // Provide a fallback to avoid infinite redirect to an encoded literal
        return "/error/invalid-tenant";
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
