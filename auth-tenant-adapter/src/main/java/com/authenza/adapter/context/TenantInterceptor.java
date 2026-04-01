package com.authenza.adapter.context;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getServletPath();
        String[] segments = path.split("/");

        // Assumes path starts with /{tenantId}/
        if (segments.length > 1) {
            String tenantId = segments[1];
            TenantContextHolder.setTenantId(tenantId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Crucial: This prevents the next user of this thread
        // from seeing the previous tenant's data.
        TenantContextHolder.clear();
        log.info("Tenant context cleared for thread: {}", Thread.currentThread().getName());
    }
}
