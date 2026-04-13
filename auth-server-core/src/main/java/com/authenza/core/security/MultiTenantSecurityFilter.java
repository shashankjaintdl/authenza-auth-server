package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.adapter.routing.TenantRoutingDataSource;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class MultiTenantSecurityFilter extends OncePerRequestFilter {

    private final AuthorizationServerSettings settings;
    private final TenantRoutingDataSource routingDataSource;

    public MultiTenantSecurityFilter(AuthorizationServerSettings settings,
                                     TenantRoutingDataSource routingDataSource) {
        this.settings = settings;
        this.routingDataSource = routingDataSource;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();

        // Skip validation for error pages and static resources to avoid redirect loops
        if (uri.startsWith("/error") || uri.startsWith("/images") || uri.startsWith("/css") || uri.startsWith("/js") || uri.startsWith("/favicon.ico")) {
            filterChain.doFilter(request, response);
            return;
        }

        String tenantId = resolveTenantId(uri);

        if (tenantId != null) {

            // Validate that this tenant actually exists in the routing datasource
            if (!routingDataSource.isKnownTenant(tenantId)) {
                String message = URLEncoder.encode(
                        "Tenant '" + tenantId + "' does not exist or has not been provisioned.",
                        StandardCharsets.UTF_8);
                response.sendRedirect("/error/invalid-tenant?message=" + message);
                return;
            }

            // 1. Set DB Context (for your RoutingDataSource/Hibernate)
            TenantContextHolder.setTenantId(tenantId);

            // 2. Set Auth Server Context (for OIDC/OAuth2 Metadata)
            String issuer = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .replacePath(tenantId)
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
            if (!part.isEmpty()) return part;
        }
        return null;
    }
}
