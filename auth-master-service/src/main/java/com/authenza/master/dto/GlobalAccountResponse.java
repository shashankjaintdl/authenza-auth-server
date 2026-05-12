package com.authenza.master.dto;

import java.time.Instant;

/**
 * Safe read-only view of a {@code GlobalAccount}. Never exposes the password hash.
 */
public record GlobalAccountResponse(
        Long id,
        String email,
        String givenName,
        String familyName,
        String status,
        String platformRole,   // populated when returned from a tenant membership query
        Instant createdAt
) {}
