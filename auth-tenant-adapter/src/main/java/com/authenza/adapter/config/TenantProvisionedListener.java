package com.authenza.adapter.config;

import com.authenza.adapter.routing.TenantRoutingDataSource;
import com.authenza.common.TenantConfig;
import com.authenza.common.dto.TenantProvisionedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@code tenant:provisioned} Redis Pub/Sub messages
 * and dynamically registers the new tenant's DataSource in
 * the {@link TenantRoutingDataSource}.
 *
 * <p>This eliminates the need to restart auth-server-core after
 * provisioning a new tenant via auth-master-service.</p>
 */
@Component
public class TenantProvisionedListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisionedListener.class);

    private final TenantLoaderService tenantLoaderService;
    private final ObjectMapper objectMapper;

    public TenantProvisionedListener(TenantLoaderService tenantLoaderService) {
        this.tenantLoaderService = tenantLoaderService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody());
            log.info("Received tenant-provisioned event: {}", json);

            TenantProvisionedEvent event = objectMapper.readValue(json, TenantProvisionedEvent.class);

            // Skip the system-master tenant — it's already handled at startup
            if ("system-admin".equals(event.tenantId())) {
                log.info("Skipping system-master tenant — already registered at startup.");
                return;
            }

            // Convert to TenantConfig and register the DataSource
            TenantConfig config = new TenantConfig(
                    event.tenantId(),
                    event.jdbcUrl(),
                    event.username(),
                    event.password(),
                    event.driverClassName()
            );

            tenantLoaderService.registerTenantDataSource(config);
            log.info("Dynamically registered DataSource for tenant '{}' — no restart needed!",
                    event.tenantId());

        } catch (Exception e) {
            log.error("Failed to process tenant-provisioned event", e);
        }
    }
}
