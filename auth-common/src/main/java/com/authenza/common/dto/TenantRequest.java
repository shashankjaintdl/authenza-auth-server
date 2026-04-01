package com.authenza.common.dto;

import jakarta.validation.constraints.NotBlank;

public record TenantRequest(
     String tenantId,
     DBType dbType,     // MYSQL, POSTGRES, ORACLE
     String jdbcUrl,
     String username,
     String password,
     String driver
) {}