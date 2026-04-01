package com.authenza.common.dto;

/**
 * Event record published to Redis when a new tenant is provisioned.
 * Contains all details needed by auth-server-core to create a HikariDataSource
 * and register it in the TenantRoutingDataSource at runtime.
 */
public record TenantProvisionedEvent(
        String tenantId,
        String jdbcUrl,
        String username,
        String password,
        String driverClassName
) {}
