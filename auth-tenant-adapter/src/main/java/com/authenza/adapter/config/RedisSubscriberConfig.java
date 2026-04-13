package com.authenza.adapter.config;

import com.authenza.common.RedisChannels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes to the {@code tenant:provisioned} Redis Pub/Sub channel so that
 * every microservice that includes auth-tenant-adapter will automatically
 * register new tenant DataSources at runtime — without a restart.
 */
@Configuration
public class RedisSubscriberConfig {

    @Bean
    RedisMessageListenerContainer tenantProvisionedListenerContainer(
            RedisConnectionFactory connectionFactory,
            TenantProvisionedListener tenantProvisionedListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Wire the listener directly — no MessageListenerAdapter needed since
        // TenantProvisionedListener already implements MessageListener
        container.addMessageListener(
                tenantProvisionedListener,
                new ChannelTopic(RedisChannels.TENANT_PROVISIONED)
        );

        return container;
    }
}
