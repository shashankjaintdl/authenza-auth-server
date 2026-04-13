package com.authenza.adapter.context;

import com.authenza.adapter.routing.TenantRoutingDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts the tenant identifier from the {@code X-Tenant-ID} request header,
 * validates that the tenant is provisioned, and sets the TenantContextHolder
 * so the routing DataSource can route to the correct database.
 */
@Component
public class TenantValidationInterceptor implements HandlerInterceptor {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    private static final Logger log = LoggerFactory.getLogger(TenantValidationInterceptor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantRoutingDataSource routingDataSource;

    public TenantValidationInterceptor(TenantRoutingDataSource routingDataSource) {
        this.routingDataSource = routingDataSource;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {

        // 1. Extract tenantId from header
        String tenantId = request.getHeader(TENANT_HEADER);

        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Rejecting request: missing '{}' header for {}", TENANT_HEADER, request.getServletPath());
            sendErrorResponse(response, HttpStatus.BAD_REQUEST,
                    "Missing '" + TENANT_HEADER + "' header. Every API request must identify a tenant.");
            return false;
        }

        tenantId = tenantId.trim();

        // 2. Validate that the tenant is registered (has a provisioned DataSource)
        if (!routingDataSource.isKnownTenant(tenantId)) {
            log.warn("Rejecting request: tenant '{}' is not registered", tenantId);
            sendErrorResponse(response, HttpStatus.NOT_FOUND,
                    "Tenant '" + tenantId + "' does not exist or is not provisioned.");
            return false;
        }

        // 3. All good — set context so the routing DataSource picks the right DB
        TenantContextHolder.setTenantId(tenantId);
        log.debug("Tenant '{}' validated and context set", tenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContextHolder.clear();
    }

    private void sendErrorResponse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("success", false);
        body.put("message", message);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}

