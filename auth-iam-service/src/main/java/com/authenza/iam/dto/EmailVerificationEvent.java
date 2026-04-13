package com.authenza.iam.dto;

public record EmailVerificationEvent(
        String tenantId,
        String type,
        String recipientEmail,
        String recipientName,
        String token
) {
}
