package com.authenza.notification.config;

import com.authenza.common.RedisChannels;
import com.authenza.notification.listener.NotificationEventListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    public static final String NOTIFICATION_TOPIC = "tenant-notifications";

    @Bean
    RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
                                            @Qualifier("listenerAdapter") MessageListenerAdapter listenerAdapter,
                                            @Qualifier("emailVerificationAdapter") MessageListenerAdapter emailVerificationAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic(NOTIFICATION_TOPIC));
        container.addMessageListener(emailVerificationAdapter, new ChannelTopic(RedisChannels.NOTIFICATION_EMAIL_VERIFICATION));
        return container;
    }

    @Bean
    MessageListenerAdapter listenerAdapter(NotificationEventListener receiver) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(receiver, "handleMessage");
        adapter.setSerializer(new StringRedisSerializer());
        return adapter;
    }

    @Bean
    MessageListenerAdapter emailVerificationAdapter(NotificationEventListener receiver) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(receiver, "handleEmailVerification");
        adapter.setSerializer(new StringRedisSerializer());
        return adapter;
    }
}
