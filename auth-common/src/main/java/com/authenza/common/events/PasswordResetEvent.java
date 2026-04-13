package com.authenza.common.events;

/**
 * Event published to Redis when a user requests a password reset.
 * Consumed by {@code auth-notification-service} to send the reset email.
 */
public record PasswordResetEvent(
        String tenantId,
        String type,
        String recipientEmail,
        String recipientName,
        String token
) {
}
