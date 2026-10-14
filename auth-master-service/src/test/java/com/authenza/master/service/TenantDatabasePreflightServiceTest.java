package com.authenza.master.service;

import com.authenza.common.dto.TenantDatabaseValidationRequest;
import com.authenza.common.dto.TenantDatabaseValidationResponse;
import com.authenza.common.enums.DBType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TenantDatabasePreflightServiceTest {

    private final TenantDatabasePreflightService service = new TenantDatabasePreflightService();

    @Test
    void rejectsUnsupportedEngine() {
        TenantDatabaseValidationResponse response = service.validate(
                new TenantDatabaseValidationRequest(
                        DBType.ORACLE,
                        "jdbc:oracle:thin:@localhost:1521:ORCL",
                        "user",
                        "pass",
                        null));

        assertFalse(response.valid());
        assertEquals("UNSUPPORTED_ENGINE", response.errorCode());
    }

    @Test
    void rejectsBlankJdbcUrl() {
        TenantDatabaseValidationResponse response = service.validate(
                new TenantDatabaseValidationRequest(DBType.MYSQL, "  ", "user", "pass", null));

        assertFalse(response.valid());
        assertEquals("INVALID_JDBC_URL", response.errorCode());
    }

    @Test
    void rejectsMismatchedUrlScheme() {
        TenantDatabaseValidationResponse response = service.validate(
                new TenantDatabaseValidationRequest(
                        DBType.POSTGRES_SQL,
                        "jdbc:mysql://localhost:3306/acme",
                        "user",
                        "pass",
                        null));

        assertFalse(response.valid());
        assertEquals("INVALID_JDBC_URL", response.errorCode());
    }

    @Test
    void rejectsUnreachableHost() {
        TenantDatabaseValidationResponse response = service.validate(
                new TenantDatabaseValidationRequest(
                        DBType.POSTGRES_SQL,
                        "jdbc:postgresql://127.0.0.1:1/does_not_exist",
                        "user",
                        "pass",
                        null));

        assertFalse(response.valid());
        // Unreachable host surfaces as DB_UNREACHABLE or DB_QUERY_FAILED depending on driver timing.
        String code = response.errorCode();
        assertFalse(response.valid());
        org.junit.jupiter.api.Assertions.assertTrue(
                "DB_UNREACHABLE".equals(code) || "DB_QUERY_FAILED".equals(code) || "DB_AUTH_FAILED".equals(code),
                "Unexpected error code: " + code);
    }
}
