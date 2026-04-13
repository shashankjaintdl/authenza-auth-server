package com.authenza.notification.config;

import com.authenza.adapter.context.TenantValidationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class NotificationWebMvcConfig implements WebMvcConfigurer {

    private final TenantValidationInterceptor tenantValidationInterceptor;

    public NotificationWebMvcConfig(TenantValidationInterceptor tenantValidationInterceptor) {
        this.tenantValidationInterceptor = tenantValidationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Only apply the tenant validation interceptor to the API endpoints.
        // This ensures the X-Tenant-Id header is parsed before the Controller handles the request.
        registry.addInterceptor(tenantValidationInterceptor)
                .addPathPatterns("/api/v1/settings/**");
    }
}
