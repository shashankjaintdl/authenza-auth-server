package com.authenza.common.dto;

/**
 * Safe tenant metadata updates only.
 * Does not allow changing tenantId, JDBC credentials, or db engine.
 */
public record TenantSettingsUpdateRequest(
        String ownerId,
        String accountType,
        String orgName,
        String employeeRange,
        Boolean isDefault
) {
}
