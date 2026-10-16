package com.authenza.master.service;

import com.authenza.common.dto.TenantDatabaseValidationRequest;
import com.authenza.common.dto.TenantDatabaseValidationResponse;
import com.authenza.common.enums.DBType;
import com.authenza.common.exception.TenantDatabaseValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Non-mutating connectivity checks for customer BYOD databases.
 */
@Service
public class TenantDatabasePreflightService {

    private static final Logger log = LoggerFactory.getLogger(TenantDatabasePreflightService.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 8;

    public TenantDatabaseValidationResponse validate(TenantDatabaseValidationRequest request) {
        try {
            ValidatedConnection validated = connect(request);
            return TenantDatabaseValidationResponse.ok(
                    validated.dbType().getValue(),
                    validated.driver(),
                    validated.productName(),
                    validated.productVersion());
        } catch (TenantDatabaseValidationException ex) {
            return TenantDatabaseValidationResponse.fail(ex.getErrorCode(), ex.getMessage());
        }
    }

    /**
     * Same checks as {@link #validate} but throws on failure for provisioning.
     */
    public void assertValid(TenantDatabaseValidationRequest request) {
        TenantDatabaseValidationResponse response = validate(request);
        if (!response.valid()) {
            throw new TenantDatabaseValidationException(
                    response.errorCode() != null ? response.errorCode() : "DB_QUERY_FAILED",
                    response.message() != null ? response.message() : "Database validation failed");
        }
    }

    private ValidatedConnection connect(TenantDatabaseValidationRequest request) {
        if (request == null || !StringUtils.hasText(request.jdbcUrl())) {
            throw new TenantDatabaseValidationException(
                    "INVALID_JDBC_URL", "jdbcUrl is required for BYOD database validation");
        }

        DBType dbType = request.dbType() != null ? request.dbType() : inferDbType(request.jdbcUrl());
        if (!dbType.isByodSupported()) {
            throw new TenantDatabaseValidationException(
                    "UNSUPPORTED_ENGINE",
                    "BYOD currently supports MYSQL, POSTGRES, and MARIADB only. Requested: " + dbType.getValue());
        }

        String jdbcUrl = request.jdbcUrl().trim();
        validateUrlScheme(dbType, jdbcUrl);

        String driver = StringUtils.hasText(request.driver())
                ? request.driver().trim()
                : dbType.getDefaultDriver();

        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new TenantDatabaseValidationException(
                    "DRIVER_MISSING",
                    "JDBC driver not found on classpath: " + driver);
        }

        Properties props = new Properties();
        if (StringUtils.hasText(request.username())) {
            props.setProperty("user", request.username());
        }
        if (request.password() != null) {
            props.setProperty("password", request.password());
        }
        props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000));
        // Postgres uses loginTimeout (seconds)
        props.setProperty("loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));

        try (Connection connection = DriverManager.getConnection(jdbcUrl, props);
             Statement statement = connection.createStatement();
             ResultSet ignored = statement.executeQuery("SELECT 1")) {

            DatabaseMetaData meta = connection.getMetaData();
            String productName = meta != null ? meta.getDatabaseProductName() : null;
            String productVersion = meta != null ? meta.getDatabaseProductVersion() : null;
            log.info("BYOD preflight OK for {} ({} {})", dbType.getValue(), productName, productVersion);
            return new ValidatedConnection(dbType, driver, productName, productVersion);
        } catch (TenantDatabaseValidationException ex) {
            throw ex;
        } catch (java.sql.SQLTimeoutException e) {
            throw new TenantDatabaseValidationException(
                    "DB_UNREACHABLE", "Database connection timed out: " + safeMessage(e));
        } catch (java.sql.SQLException e) {
            String sqlState = e.getSQLState();
            String message = safeMessage(e);
            if (isAuthFailure(sqlState, message)) {
                throw new TenantDatabaseValidationException(
                        "DB_AUTH_FAILED", "Authentication failed for supplied credentials");
            }
            if (isUnreachable(e, message)) {
                throw new TenantDatabaseValidationException(
                        "DB_UNREACHABLE", "Unable to reach database host: " + message);
            }
            throw new TenantDatabaseValidationException(
                    "DB_QUERY_FAILED", "Database validation query failed: " + message);
        } catch (Exception e) {
            throw new TenantDatabaseValidationException(
                    "DB_UNREACHABLE", "Unable to connect to database: " + safeMessage(e));
        }
    }

    private DBType inferDbType(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase();
        if (lower.startsWith("jdbc:postgresql:")) {
            return DBType.POSTGRES_SQL;
        }
        if (lower.startsWith("jdbc:mariadb:")) {
            return DBType.MARIA_DB;
        }
        if (lower.startsWith("jdbc:mysql:")) {
            return DBType.MYSQL;
        }
        return DBType.MYSQL;
    }

    private void validateUrlScheme(DBType dbType, String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase();
        boolean ok = switch (dbType) {
            case MYSQL -> lower.startsWith("jdbc:mysql:");
            case POSTGRES_SQL -> lower.startsWith("jdbc:postgresql:");
            case MARIA_DB -> lower.startsWith("jdbc:mariadb:") || lower.startsWith("jdbc:mysql:");
            default -> false;
        };
        if (!ok) {
            throw new TenantDatabaseValidationException(
                    "INVALID_JDBC_URL",
                    "jdbcUrl scheme does not match dbType " + dbType.getValue() + ": " + jdbcUrl);
        }
    }

    private boolean isAuthFailure(String sqlState, String message) {
        if (sqlState != null && (sqlState.startsWith("28") || "28000".equals(sqlState))) {
            return true;
        }
        String m = message.toLowerCase();
        return m.contains("access denied")
                || m.contains("authentication failed")
                || m.contains("password authentication failed")
                || m.contains("login failed");
    }

    private boolean isUnreachable(Exception e, String message) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.NoRouteToHostException) {
                return true;
            }
            cause = cause.getCause();
        }
        String m = message.toLowerCase();
        return m.contains("connection refused")
                || m.contains("unknown host")
                || m.contains("network is unreachable")
                || m.contains("timed out");
    }

    private String safeMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private record ValidatedConnection(
            DBType dbType,
            String driver,
            String productName,
            String productVersion) {
    }
}
