package com.authenza.core.config;

import com.authenza.adapter.context.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    // Spring will automatically inject the TenantInterceptor bean
    private final TenantInterceptor tenantInterceptor;

    public WebMvcConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Apply to ALL paths so the database is always switched correctly
        // before any Repository or Auth Server logic runs.
        registry.addInterceptor(tenantInterceptor)
                .excludePathPatterns("/{tenantId}/login", "/{tenantId}/oauth2/**")
                .excludePathPatterns("/{tenantId}/register", "/{tenantId}/verify-email", "/{tenantId}/api/**")
                .addPathPatterns("/**")
                // .excludePathPatterns("/login")
                // Exclude static assets
                .excludePathPatterns("/favicon.ico", "/error", "/error/**", "/images/**", "/css/**", "/js/**", "/public/**");
    }
}