package com.authenza.core.risk;

/**
 * Immutable inputs for a login risk evaluation.
 */
public record RiskContext(
        String tenantId,
        Long userId,
        String username,
        String ipAddress,
        String userAgent,
        String secChUa,
        String deviceFingerprint,
        boolean trustedCookieValid,
        int recentFailedAttempts,
        AuthMethod authMethod
) {
    public enum AuthMethod {
        PASSWORD,
        PASSKEY,
        SOCIAL
    }
}
