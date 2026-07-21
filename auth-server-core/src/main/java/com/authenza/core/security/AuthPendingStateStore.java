package com.authenza.core.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed store for temporary authentication states that bridge
 * multi-step login flows (MFA verification and forced password changes).
 *
 * <p>Replaces session-local {@code HttpSession} attributes so that login flows
 * work correctly in a horizontally-scaled cluster where the browser's follow-up
 * request can land on a different pod than the initial authentication.
 *
 * <p>Key schema: {@code auth:pending:<type>:<token>}
 * <ul>
 *   <li>MFA pending: {@code auth:pending:mfa:<token>}</li>
 *   <li>Password change pending: {@code auth:pending:pwd-change:<token>}</li>
 * </ul>
 *
 * <p>All keys are written with a 5-minute TTL and are deleted atomically on read.
 */
@Service
public class AuthPendingStateStore {

    private static final Logger log = LoggerFactory.getLogger(AuthPendingStateStore.class);

    private static final String KEY_PREFIX  = "auth:pending:";
    private static final Duration STATE_TTL = Duration.ofMinutes(5);

    /** Pending state type for MFA TOTP verification. */
    public static final String TYPE_MFA        = "mfa";
    /** Pending state type for forced password change after login. */
    public static final String TYPE_PWD_CHANGE = "pwd-change";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AuthPendingStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Saves a pending authentication state to Redis.
     *
     * @param type  the state type (use {@link #TYPE_MFA} or {@link #TYPE_PWD_CHANGE})
     * @param state the state payload to store
     * @return the generated secure token that keys this state (to be passed to the UI)
     */
    public String savePendingState(String type, PendingState state) {
        String token = UUID.randomUUID().toString();
        String key   = KEY_PREFIX + type + ":" + token;
        try {
            String json = objectMapper.writeValueAsString(state);
            redis.opsForValue().set(key, json, STATE_TTL);
            log.debug("[AuthPendingStateStore] Saved pending state type={} for user={}", type, state.username());
        } catch (Exception e) {
            log.error("[AuthPendingStateStore] Failed to save pending state type={}: {}", type, e.getMessage());
            throw new RuntimeException("Failed to store pending authentication state.", e);
        }
        return token;
    }

    /**
     * Retrieves and atomically removes a pending authentication state from Redis.
     *
     * @param type  the state type (use {@link #TYPE_MFA} or {@link #TYPE_PWD_CHANGE})
     * @param token the token returned by {@link #savePendingState}
     * @return the {@link PendingState}, or {@code null} if expired or not found
     */
    public PendingState getAndClearPendingState(String type, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key  = KEY_PREFIX + type + ":" + token;
        String json = redis.opsForValue().getAndDelete(key);
        if (json == null) {
            log.warn("[AuthPendingStateStore] Pending state not found or expired for type={}, token={}", type, token);
            return null;
        }
        try {
            return objectMapper.readValue(json, PendingState.class);
        } catch (Exception e) {
            log.error("[AuthPendingStateStore] Failed to deserialize pending state type={}: {}", type, e.getMessage());
            throw new RuntimeException("Failed to deserialize pending authentication state.", e);
        }
    }

    /**
     * Checks whether a pending state exists in Redis without consuming it.
     *
     * @param type  the state type
     * @param token the token returned by {@link #savePendingState}
     * @return {@code true} if the key still exists in Redis
     */
    public boolean hasPendingState(String type, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + type + ":" + token));
    }

    /**
     * Payload stored in Redis for a pending multi-step authentication.
     *
     * @param username the user's login email/username
     * @param tenantId the active tenant at the time of initial password authentication
     * @param userId   the database ID of the user
     */
    public record PendingState(String username, String tenantId, Long userId) {
        @JsonCreator
        public PendingState(
                @JsonProperty("username") String username,
                @JsonProperty("tenantId") String tenantId,
                @JsonProperty("userId")   Long userId) {
            this.username = username;
            this.tenantId = tenantId;
            this.userId   = userId;
        }
    }
}
