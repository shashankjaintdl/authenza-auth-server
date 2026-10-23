package com.authenza.common.dto;

import com.authenza.common.enums.DBType;

/**
 * Non-mutating BYOD connectivity check request.
 */
public record TenantDatabaseValidationRequest(
        DBType dbType,
        String jdbcUrl,
        String username,
        String password,
        String driver
) {
}
