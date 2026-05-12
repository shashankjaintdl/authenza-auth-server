package com.authenza.iam.dto;

import java.time.Instant;

/**
 * A safe, read-only view of a registered passkey, returned to the "Manage Passkeys" UI.
 * The public key bytes and credential ID are intentionally omitted for client-side safety.
 */
public record PasskeyResponse(
        Long id,
        String displayName,
        String aaguid,
        String transports,
        Boolean userVerified,
        Instant createdAt,
        Instant lastUsedAt
) {}
