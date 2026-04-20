package com.authenza.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Tenant-aware brute-force login protection service.
 *
 * <p>Strategy:
 * <ol>
 *   <li><strong>Attempt counter</strong> — stored in Redis with a TTL equal to the lockout
 *       duration. Key pattern: {@code bf:{tenantId}:{username}}</li>
 *   <li><strong>Account lock</strong> — when the counter reaches {@code maxAttempts}, the
 *       user's row in {@code application_user} is updated:
 *       {@code status = 'LOCKED'} and {@code locked_until = NOW() + lockDuration}.</li>
 *   <li><strong>Auto-unlock</strong> — if {@code locked_until} is in the past when
 *       {@link JdbcTenantUserDetailsService} loads the user, it calls
 *       {@link #clearLock} to transparently restore the account.</li>
 *   <li><strong>Reset on success</strong> — a successful authentication deletes the Redis
 *       key and resets the DB counters.</li>
 * </ol>
 *
 * <p>The tenant-scoped Redis key prevents bleed between tenants sharing the same Redis
 * instance.
 */
@Service
public class BruteForceProtectionService {

    private static final Logger log = LoggerFactory.getLogger(BruteForceProtectionService.class);

    /** Redis key prefix for per-tenant, per-user attempt counters. */
    private static final String KEY_PREFIX = "bf:";

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final BruteForceProperties properties;

    public BruteForceProtectionService(StringRedisTemplate redisTemplate,
                                       DataSource dataSource,
                                       BruteForceProperties properties) {
        this.redisTemplate = redisTemplate;
        // The injected DataSource is the tenant-routing datasource — it routes
        // all JDBC operations to the correct tenant DB via TenantContextHolder.
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Records a failed login attempt for the given user within the given tenant.
     *
     * <p>When the attempt count reaches {@code maxAttempts}, the account is locked
     * in the DB and the method returns {@code true} to signal that the caller
     * should redirect to the {@code ?locked} page instead of {@code ?error}.
     *
     * @param username the username or email that was used in the failed attempt
     * @param tenantId the active tenant identifier
     * @return {@code true} if the account was just locked by this call,
     *         {@code false} if it is still within the warning window
     */
    public boolean recordFailedAttempt(String username, String tenantId) {
        String key = buildKey(tenantId, username);
        long duration = properties.getLockDurationMinutes();

        // Increment attempt counter in Redis; set TTL on first INCR so
        // the key expires automatically after the lockout window.
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1) {
            // First failure in this window — start the TTL clock
            redisTemplate.expire(key, duration, TimeUnit.MINUTES);
        }

        log.debug("[BruteForce] tenant={} username={} attempts={}", tenantId, username, attempts);

        if (attempts != null && attempts >= properties.getMaxAttempts()) {
            lockAccountInDb(username, tenantId, duration);
            log.warn("[BruteForce] Account LOCKED — tenant={} username={} after {} attempts",
                    tenantId, username, attempts);
            return true; // caller should redirect to ?locked
        }

        return false; // caller should redirect to ?error as usual
    }

    /**
     * Resets the failed-attempt counter for the given user on successful login.
     * Also clears any stale lock in the DB (in case they were unlocked by TTL expiry).
     *
     * @param username the authenticated username
     * @param tenantId the active tenant identifier
     */
    public void resetFailedAttempts(String username, String tenantId) {
        String key = buildKey(tenantId, username);
        redisTemplate.delete(key);
        clearLock(username, tenantId);
        log.debug("[BruteForce] Counters reset — tenant={} username={}", tenantId, username);
    }

    /**
     * Removes the lock from a user account in the DB (sets status back to ACTIVE,
     * resets {@code failed_login_attempts} to 0, clears {@code locked_until}).
     *
     * <p>Called automatically by {@link JdbcTenantUserDetailsService} when
     * {@code locked_until} has expired, and by {@link #resetFailedAttempts} on
     * successful login. Can also be called by an admin unlock flow.
     *
     * @param username the username to unlock
     * @param tenantId the active tenant identifier
     */
    public void clearLock(String username, String tenantId) {
        int updated = jdbcTemplate.update(
                "UPDATE application_user " +
                "SET status = 'ACTIVE', failed_login_attempts = 0, locked_until = NULL " +
                "WHERE (preferred_username = ? OR email = ?) AND status = 'LOCKED'",
                username, username);

        if (updated > 0) {
            log.info("[BruteForce] Account UNLOCKED — tenant={} username={}", tenantId, username);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Persists the lock into the DB for the given username within the current tenant context. */
    private void lockAccountInDb(String username, String tenantId, long durationMinutes) {
        Instant lockedUntil = Instant.now().plus(Duration.ofMinutes(durationMinutes));
        jdbcTemplate.update(
                "UPDATE application_user " +
                "SET status = 'LOCKED', locked_until = ?, failed_login_attempts = ? " +
                "WHERE preferred_username = ? OR email = ?",
                java.sql.Timestamp.from(lockedUntil),
                properties.getMaxAttempts(),
                username,
                username);
    }

    /** Builds a tenant-scoped Redis key to prevent cross-tenant bleed. */
    private String buildKey(String tenantId, String username) {
        return KEY_PREFIX + tenantId + ":" + username.toLowerCase();
    }
}
