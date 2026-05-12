package com.authenza.common.dto;

import com.authenza.common.enums.DBType;
import jakarta.validation.constraints.NotBlank;

public record TenantRequest(
                String tenantId,
                DBType dbType, // MYSQL, POSTGRES, ORACLE
                String jdbcUrl,
                String username,
                String password,
                String driver,
                @NotBlank(message = "ownerId is mandatory")
                String ownerId,
                @NotBlank(message = "accountType is mandatory. Use PERSONAL or COMPANY")
                String accountType, // PERSONAL | COMPANY
                String orgName,     // required if accountType = COMPANY
                String employeeRange, // e.g. "1-10", "11-50", "51-200", "200+"
                boolean isDefault) {
}