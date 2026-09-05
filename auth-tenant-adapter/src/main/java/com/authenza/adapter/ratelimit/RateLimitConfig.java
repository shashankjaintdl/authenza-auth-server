package com.authenza.adapter.ratelimit;

import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.lettuce.core.RedisClient;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    @Bean
    public LettuceBasedProxyManager<byte[]> proxyManager(RedisConnectionFactory connectionFactory) {
        if (connectionFactory instanceof LettuceConnectionFactory) {
            RedisClient redisClient = (RedisClient) ((LettuceConnectionFactory) connectionFactory).getNativeClient();
            if (redisClient == null) {
                throw new IllegalStateException("Native RedisClient is null. Ensure LettuceConnectionFactory is properly initialized.");
            }

            ClientSideConfig clientSideConfig = ClientSideConfig
                    .getDefault().
                    withExpirationAfterWriteStrategy(ExpirationAfterWriteStrategy
                            .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10))
                    );

            return LettuceBasedProxyManager.builderFor(redisClient)
                    .withClientSideConfig(clientSideConfig)
                    .build();
        }
        throw new IllegalStateException("RedisConnectionFactory is not an instance of LettuceConnectionFactory");
    }
}
