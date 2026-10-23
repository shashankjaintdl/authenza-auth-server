package com.authenza.core.risk;

import com.authenza.common.RedisChannels;
import com.authenza.common.enums.NotificationType;
import com.authenza.common.enums.SecurityAlertKind;
import com.authenza.common.events.SecurityAlertEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Publishes new-device security alert emails via Redis (non-blocking for login).
 */
@Service
public class RiskNotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(RiskNotificationPublisher.class);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public RiskNotificationPublisher(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void publishNewDeviceAlert(String tenantId, Long userId, String deviceName, String ipAddress) {
        try {
            UserContact contact = loadContact(userId);
            if (contact == null || contact.email() == null || contact.email().isBlank()) {
                log.warn("[RiskNotify] No email for userId={} — skip new-device alert", userId);
                return;
            }
            SecurityAlertEvent event = new SecurityAlertEvent(
                    tenantId,
                    NotificationType.SECURITY_ALERT.name(),
                    contact.email(),
                    contact.displayName(),
                    deviceName,
                    ipAddress,
                    SecurityAlertKind.NEW_DEVICE.wireValue());
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(RedisChannels.NOTIFICATION_SECURITY_ALERT, json);
            log.info("[RiskNotify] Published NEW_DEVICE alert tenant={} userId={}", tenantId, userId);
        } catch (Exception e) {
            log.error("[RiskNotify] Failed to publish alert for userId={}: {}", userId, e.getMessage());
        }
    }

    private UserContact loadContact(Long userId) {
        List<UserContact> rows = jdbcTemplate.query(
                """
                        SELECT email, preferred_username, display_name
                        FROM   application_user
                        WHERE  id = ?
                        """,
                (rs, rowNum) -> new UserContact(
                        rs.getString("email"),
                        firstNonBlank(rs.getString("display_name"), rs.getString("preferred_username"))
                ),
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private record UserContact(String email, String displayName) {
    }
}
