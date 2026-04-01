package com.authenza.master.service;

import com.authenza.common.RedisChannels;
import com.authenza.common.dto.TenantProvisionedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes a {@link TenantProvisionedEvent} to Redis Pub/Sub
 * so that auth-server-core can dynamically register the new
 * tenant's DataSource at runtime — without a restart.
 */
@Service
public class TenantEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TenantEventPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TenantEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Publishes a tenant-provisioned event after a new tenant is
     * successfully onboarded (saved to master DB + Liquibase migration complete).
     */
    public void publishTenantProvisioned(TenantProvisionedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(RedisChannels.TENANT_PROVISIONED, json);
            log.info("Published tenant-provisioned event for tenant '{}'", event.tenantId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize TenantProvisionedEvent for tenant '{}'",
                    event.tenantId(), e);
        }
    }
}
