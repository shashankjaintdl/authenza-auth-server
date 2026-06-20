package com.authenza.iam.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed blacklist for revoked sessions.
 *
 * <p>When a session is revoked, its {@code session_id} is written to Redis
 * with a TTL equal to the remaining access-token lifetime (default: 1 hour).
 * The {@link RevokedSessionJwtValidator} checks this store on every API request
 * to reject tokens whose sessions have been invalidated, enabling <em>immediate</em>
 * logout without waiting for the JWT to expire naturally.
 *
 * <p>Key format: {@code authenza:revoked:session:{sessionId}}
 */
@Service
public class RevokedSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RevokedSessionStore.class);
    private static final String PREFIX = "authenza:revoked:session:";

    /**
     * Default TTL for a blacklist entry.
     * Must be at least as long as the access-token TTL so that a token
     * issued just before revocation is still rejected when it arrives.
     * 2 hours gives a comfortable margin above the typical 1-hour token TTL.
     */
    private static final Duration DEFAULT_BLACKLIST_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;

    public RevokedSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Marks a session as revoked in Redis.
     *
     * @param sessionId      the {@code user_session.id} to blacklist
     * @param tokenExpiresAt the access-token's {@code exp} claim — used to
     *                       calculate a precise TTL so the Redis key auto-expires
     *                       once the token can no longer be used anyway
     */
    public void revoke(String sessionId, Instant tokenExpiresAt) {
        Duration ttl = DEFAULT_BLACKLIST_TTL;
        if (tokenExpiresAt != null) {
            Duration remaining = Duration.between(Instant.now(), tokenExpiresAt);
            // Add 30 s buffer to cover clock skew between services
            ttl = remaining.isNegative() ? Duration.ofMinutes(5) : remaining.plusSeconds(30);
        }
        String key = PREFIX + sessionId;
        redis.opsForValue().set(key, "revoked", ttl);
        log.info("[RevokedSessionStore] Blacklisted session {} for {} minutes", sessionId, ttl.toMinutes());
    }

    /**
     * Convenience overload — uses the default 2-hour TTL when the token's
     * expiry is unknown (e.g., during "revoke all sessions" bulk operations).
     */
    public void revoke(String sessionId) {
        revoke(sessionId, null);
    }

    /**
     * Returns {@code true} if the given session ID has been blacklisted.
     *
     * @param sessionId the {@code session_id} claim extracted from the incoming JWT
     */
    public boolean isRevoked(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + sessionId));
    }
}
