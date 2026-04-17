package com.authenza.common.events;

/**
 * Event published to Redis when a Tenant Admin invites a new user.
 * Consumed by {@code auth-notification-service} to send the invitation email
 * containing a one-time link to the "Set Password" screen.
 */
public record AdminInviteEvent(
        String tenantId,
        String type,
        String recipientEmail,
        String recipientName,
        String token,
        String invitedByName
) {
}
