package com.authenza.adapter.config;

import com.authenza.adapter.routing.TenantRoutingDataSource;
import com.authenza.common.RedisChannels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Configures a Redis Pub/Sub subscriber that listens for
 * {@code tenant:provisioned} events and dynamically registers
 * new tenant DataSources in the {@link TenantRoutingDataSource}.
 */
@Configuration
public class RedisSubscriberConfig {

    @Bean
    public ChannelTopic tenantProvisionedTopic() {
        return new ChannelTopic(RedisChannels.TENANT_PROVISIONED);
    }

    @Bean
    public MessageListenerAdapter tenantListenerAdapter(TenantProvisionedListener listener) {
        // "onMessage" is the default handler method name on MessageListener
        return new MessageListenerAdapter(listener, "onMessage");
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter tenantListenerAdapter,
            ChannelTopic tenantProvisionedTopic) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(tenantListenerAdapter, tenantProvisionedTopic);
        return container;
    }
}
