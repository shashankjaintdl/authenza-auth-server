package com.authenza.common.events;

public record EmailVerificationEvent(
        String tenantId,
        String type,
        String recipientEmail,
        String recipientName,
        String token
) {
}
