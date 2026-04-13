package com.authenza.common;

/**
 * Shared Redis Pub/Sub channel name constants.
 * Used by both auth-master-service (publisher) and auth-server-core (subscriber).
 */
public final class RedisChannels {

    private RedisChannels() {
        // Utility class — no instantiation
    }

    /**
     * Channel published to when a new tenant is provisioned.
     * The message payload is a JSON-serialized {@code TenantProvisionedEvent}.
     */
    public static final String TENANT_PROVISIONED = "tenant:provisioned";
    public static final String NOTIFICATION_EMAIL_VERIFICATION = "notification:email-verification";
    public static final String NOTIFICATION_PASSWORD_RESET = "notification:password-reset";

}
