package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;

@Component
public class MultiTenantSecurityFilter extends OncePerRequestFilter {

    private final AuthorizationServerSettings settings;

    public MultiTenantSecurityFilter(AuthorizationServerSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        String tenantId = resolveTenantId(uri);

        if (tenantId != null) {
            // 1. Set DB Context (for your RoutingDataSource/Hibernate)
            TenantContextHolder.setTenantId(tenantId);

            // 2. Set Auth Server Context (for OIDC/OAuth2 Metadata)
            String issuer = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .replacePath(tenantId) // Results in "http://localhost:8081/system-master"
                    .toUriString();

            AuthorizationServerContext authContext = new AuthorizationServerContext() {
                @Override
                public String getIssuer() { return issuer; }

                @Override
                public AuthorizationServerSettings getAuthorizationServerSettings() {
                    return settings;
                }

            };
            AuthorizationServerContextHolder.setContext(authContext);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 3. ALWAYS clear both to prevent thread leakage
            TenantContextHolder.clear();
            AuthorizationServerContextHolder.resetContext();
        }
    }

    private String resolveTenantId(String uri) {
        if (uri == null || uri.equals("/")) return null;
        String[] parts = uri.split("/");
        for (String part : parts) {
            if (!part.isEmpty()) return part; // Returns "system-master"
        }
        return null;
    }
}
