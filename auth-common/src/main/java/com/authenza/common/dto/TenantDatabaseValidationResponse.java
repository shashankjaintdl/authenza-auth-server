package com.authenza.common.dto;

/**
 * Result of {@code POST /api/v1/admin/tenant/validate-db}.
 */
public record TenantDatabaseValidationResponse(
        boolean valid,
        String dbType,
        String resolvedDriver,
        String productName,
        String productVersion,
        String errorCode,
        String message
) {
    public static TenantDatabaseValidationResponse ok(
            String dbType,
            String resolvedDriver,
            String productName,
            String productVersion) {
        return new TenantDatabaseValidationResponse(
                true, dbType, resolvedDriver, productName, productVersion, null, null);
    }

    public static TenantDatabaseValidationResponse fail(String errorCode, String message) {
        return new TenantDatabaseValidationResponse(
                false, null, null, null, null, errorCode, message);
    }
}
