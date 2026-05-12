package com.authenza.iam.config;

import com.authenza.adapter.context.TenantValidationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers tenant interceptors with Spring MVC for the IAM service.
 *
 * Order:
 * 1. TenantInterceptor — extracts tenantId from header/path and sets
 * TenantContextHolder
 * 2. TenantValidationInterceptor — validates tenantId is present and
 * provisioned
 */
@Configuration
public class IamWebMvcConfig implements WebMvcConfigurer {

    private final TenantValidationInterceptor tenantValidationInterceptor;

    public IamWebMvcConfig(TenantValidationInterceptor tenantValidationInterceptor) {
        this.tenantValidationInterceptor = tenantValidationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantValidationInterceptor)
                // Authenticated API endpoints — require X-Tenant-ID header
                .addPathPatterns("/api/**")
                // Unauthenticated WebAuthn endpoints — also require X-Tenant-ID
                // so the RoutingDataSource can route to the correct tenant DB.
                // The login page JavaScript sends this header on every WebAuthn call.
                .addPathPatterns("/webauthn/**")
                .excludePathPatterns("/actuator/**", "/favicon.ico", "/error");
    }
}
