package com.authenza.adapter.config;

import com.authenza.adapter.cache.TenantSettingsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@code tenant:settings-invalidated} Redis Pub/Sub messages
 * and clears the local Caffeine cache for that tenant.
 *
 * <p>This guarantees that if an admin updates a policy in auth-iam-service, 
 * all running instances of auth-server-core (or any other subscribing service) 
 * immediately drop the stale cache for that tenant.</p>
 */
@Component
public class TenantSettingsInvalidatedListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(TenantSettingsInvalidatedListener.class);

    private final TenantSettingsCache settingsCache;

    public TenantSettingsInvalidatedListener(TenantSettingsCache settingsCache) {
        this.settingsCache = settingsCache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String tenantId = new String(message.getBody());
            log.info("Received tenant-settings-invalidated event for tenant: {}", tenantId);

            // Invalidate simply local JVM to force the next DB fetch on this node
            settingsCache.invalidateLocal(tenantId);
        } catch (Exception e) {
            log.error("Failed to process tenant-settings-invalidated event", e);
        }
    }
}
