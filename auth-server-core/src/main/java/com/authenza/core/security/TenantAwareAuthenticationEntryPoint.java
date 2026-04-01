package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

public class TenantAwareAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

    public TenantAwareAuthenticationEntryPoint(String loginFormUrl) {
        super(loginFormUrl);
    }

    @Override
    protected String buildRedirectUrlToLoginPage(HttpServletRequest request,
                                                 HttpServletResponse response,
                                                 AuthenticationException authException) {
        // Retrieve the validated tenantId from your context holder
        String tenantId = TenantContextHolder.getTenantId();

        // Get the base template, e.g., "/{tenantId}/login"
        String loginFormUrl = getLoginFormUrl();

        if (tenantId != null) {
            // Manually swap the placeholder for the real ID
            return loginFormUrl.replace("{tenantId}", tenantId);
        }

        // Provide a fallback to avoid redirecting to an encoded literal
        return "/error/invalid-tenant";
    }
}
