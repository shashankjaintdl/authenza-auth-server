package com.authenza.iam.dto;

import java.time.Instant;

/**
 * Portal DTO for a remembered trusted device (adaptive auth).
 */
public record TrustedDeviceResponse(
        Long id,
        String deviceName,
        String lastIp,
        String lastCountry,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt
) {
}
