package com.authenza.common.dto;

import com.authenza.common.enums.DBType;

public record TenantRequest(
                String tenantId,
                DBType dbType, // MYSQL, POSTGRES, ORACLE
                String jdbcUrl,
                String username,
                String password,
                String driver,
                String ownerId) {
}