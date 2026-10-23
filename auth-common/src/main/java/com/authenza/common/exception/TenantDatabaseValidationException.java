package com.authenza.common.exception;

/**
 * Raised when BYOD database validation or provisioning preflight fails.
 */
public class TenantDatabaseValidationException extends RuntimeException {

    private final String errorCode;

    public TenantDatabaseValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
