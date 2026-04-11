package com.authenza.iam.service;

import com.authenza.common.RedisChannels;
import com.authenza.common.dto.NotificationRequest;
import com.authenza.common.enums.NotificationType;
import com.authenza.common.events.EmailVerificationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;


@Service
public class NotificationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public NotificationEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public void publishVerificationEvent(String tenantId, String email, String name, String token) throws JsonProcessingException {
        EmailVerificationEvent event = new EmailVerificationEvent(
                tenantId,
                NotificationType.VERIFICATION_EMAIL.name(),
                email,
                name,
                token
        );
        // The notification service builds the full URL using tenant-specific base URL config
        String json = objectMapper.writeValueAsString(event);
        redisTemplate.convertAndSend(RedisChannels.NOTIFICATION_EMAIL_VERIFICATION, json);
    }


}
