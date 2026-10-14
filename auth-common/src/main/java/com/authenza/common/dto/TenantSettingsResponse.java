package com.authenza.common.dto;

import java.time.Instant;

/**
 * Tenant metadata for portal settings — never includes DB credentials.
 */
public record TenantSettingsResponse(
        String tenantId,
        String ownerId,
        String active,
        String accountType,
        String orgName,
        String employeeRange,
        boolean isDefault,
        String dbType,
        Instant lastAccessedAt
) {
}
