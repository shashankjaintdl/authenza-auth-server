package com.authenza.common;

public record TenantConfig(
        String tenantId,
        String jdbcUrl,
        String username,
        String password,
        String driverClassName,
        String active
) {
    public TenantConfig(String tenantId, String jdbcUrl, String username, String password, String driverClassName) {
        this(tenantId, jdbcUrl, username, password, driverClassName, "ACTIVE");
    }
}
