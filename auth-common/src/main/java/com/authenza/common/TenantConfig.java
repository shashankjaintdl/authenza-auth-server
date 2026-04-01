package com.authenza.common;

public record TenantConfig(
        String tenantId,
        String jdbcUrl,
        String username,
        String password,
        String driverClassName
) {}

