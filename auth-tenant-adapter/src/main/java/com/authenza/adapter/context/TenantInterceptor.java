package com.authenza.adapter.context;

import com.authenza.adapter.routing.TenantRoutingDataSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Extracts the tenant identifier from the URL path (/{tenantId}/...),
 * validates that the tenant is provisioned in the routing DataSource,
 * and sets the TenantContextHolder for the duration of the request.
 *
 * <p>
 * If the tenant is unknown or missing, the user is redirected to
 * the error page at {@code /error/invalid-tenant}.
 * </p>
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    private final TenantRoutingDataSource routingDataSource;

    public TenantInterceptor(TenantRoutingDataSource routingDataSource) {
        this.routingDataSource = routingDataSource;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getServletPath();
        String[] segments = path.split("/");

        // Path format expected: /{tenantId}/...
        // segments[0] = "" (before leading slash), segments[1] = tenantId
        if (segments.length < 2 || segments[1].isBlank()) {
            // No tenant segment found — allow through (root paths etc.)
            return true;
        }

        String tenantId = segments[1];

        // Validate that the tenant is actually provisioned
        if (!routingDataSource.isKnownTenant(tenantId)) {
            log.warn("Rejecting request for unknown tenant '{}' at path: {}", tenantId, path);
            response.sendRedirect(request.getContextPath() + "/error/invalid-tenant");
            return false;
        }

        // Tenant is valid — set context so the routing DataSource picks the right DB
        TenantContextHolder.setTenantId(tenantId);
        log.debug("Tenant context set to '{}' for path: {}", tenantId, path);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        // Clear tenant context to prevent thread-local leaks
        TenantContextHolder.clear();
        log.debug("Tenant context cleared for thread: {}", Thread.currentThread().getName());
    }
}
