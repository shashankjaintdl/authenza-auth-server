package com.authenza.core.service;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.adapter.cache.TenantSettingsCache;
import com.authenza.core.util.ClientRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Records a {@code user_session} row in the tenant database on every successful
 * login (both plain password login and MFA-completed logins).
 *
 * <p>
 * Session tracking is non-fatal: failures are logged and swallowed so that a
 * DB error never blocks the user from logging in.
 *
 * <p>
 * The tenant-aware {@code RoutingDataSource} is already active when this
 * service
 * is called (set by {@code MultiTenantSecurityFilter}), so {@code JdbcTemplate}
 * automatically writes to the correct tenant database.
 */
@Service
public class SessionRecordingService {

    private static final Logger log = LoggerFactory.getLogger(SessionRecordingService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TenantSettingsCache settingsCache;

    @Value("${authenza.security.session.default-ttl-days:30}")
    private int defaultSessionTtlDays;

    public SessionRecordingService(JdbcTemplate jdbcTemplate, TenantSettingsCache settingsCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsCache = settingsCache;
    }

    /**
     * Records a login session for the given user.
     *
     * <p><b>Upsert behaviour:</b> If a non-revoked session already exists for the
     * same {@code userId + IP + user-agent} combination that was created within the
     * last 24 hours, this method simply refreshes its {@code last_active_at} timestamp
     * instead of inserting a duplicate row.  This prevents phantom sessions from
     * appearing every time the user reloads the page, the OAuth2 flow re-runs, or the
     * Angular app silently renews its token.
     *
     * @param userId  the database PK of the authenticated user
     * @param request the HTTP request — used to extract IP and User-Agent
     * @return the {@code session_token} UUID (new or existing), or {@code null} on failure
     */
    public String recordSession(Long userId, HttpServletRequest request) {
        if (userId == null) {
            log.warn("[Session] Cannot record session — userId is null");
            return null;
        }

        String ipAddress   = ClientRequestUtils.resolveIpAddress(request);
        String userAgent   = ClientRequestUtils.resolveUserAgent(request);
        String deviceName  = ClientRequestUtils.parseDeviceName(userAgent);

        // ── Check for an existing active session from the same device ──────────────
        // "Same device" = same user + IP + user-agent, created within the last 24 h.
        // Using a 24-hour window avoids matching ancient stale rows while still
        // coalescing repeated logins during a normal work session.
        try {
            // No SQL LIMIT — take first row in Java for MySQL/Postgres/MariaDB portability.
            String existingSql = """
                    SELECT id, session_token
                    FROM   user_session
                    WHERE  user_id    = ?
                      AND  ip_address = ?
                      AND  user_agent = ?
                      AND  revoked    = ?
                      AND  expires_at > ?
                    ORDER  BY last_active_at DESC
                    """;

            var rows = jdbcTemplate.query(existingSql,
                    (rs, rowNum) -> new long[]{rs.getLong("id")},
                    userId,
                    ClientRequestUtils.truncate(ipAddress, 100),
                    ClientRequestUtils.truncate(userAgent, 500),
                    Boolean.FALSE,                        // cross-engine boolean parameter
                    java.sql.Timestamp.from(Instant.now()));

            if (!rows.isEmpty()) {
                long existingId = rows.get(0)[0];
                // Reset authorization_id to NULL so the JWT token customizer can re-link this
                // session to the new OAuth2 authorization and embed session_id in the JWT.
                // Without this reset, the old auth_id stays, the WHERE authorization_id IS NULL
                // condition never matches, and session_id is never added to the access token.
                jdbcTemplate.update(
                        "UPDATE user_session SET last_active_at = ?, authorization_id = NULL WHERE id = ?",
                        java.sql.Timestamp.from(Instant.now()), existingId);
                log.debug("[Session] Refreshed existing session {} for user {} ({})", existingId, userId, deviceName);
                // Return the existing token
                return jdbcTemplate.queryForObject(
                        "SELECT session_token FROM user_session WHERE id = ?", String.class, existingId);
            }
        } catch (Exception e) {
            log.warn("[Session] Upsert check failed, will insert new session row: {}", e.getMessage());
        }

        // ── No matching session found — insert a new row ──────────────────────────
        String sessionToken = UUID.randomUUID().toString();

        // Determine session TTL: use tenant override if exists, otherwise platform default
        int ttlDays = defaultSessionTtlDays;
        try {
            String tenantId  = TenantContextHolder.getTenantId();
            String isEnabled = settingsCache.getSetting(tenantId, "session_policy_enabled");

            if ("true".equalsIgnoreCase(isEnabled)) {
                String tenantTtl = settingsCache.getSetting(tenantId, "session_ttl_days");
                if (tenantTtl != null && !tenantTtl.isBlank()) {
                    ttlDays = Integer.parseInt(tenantTtl);
                }
            }
        } catch (Exception e) {
            log.warn("[Session] Failed to parse tenant session_ttl_days override, falling back to default", e);
        }

        Instant expiresAt = Instant.now().plus(Duration.ofDays(ttlDays));

        try {
            jdbcTemplate.update(
                    "INSERT INTO user_session " +
                    "(user_id, session_token, ip_address, user_agent, device_name, created_at, last_active_at, expires_at, revoked) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    userId,
                    sessionToken,
                    ClientRequestUtils.truncate(ipAddress, 100),
                    ClientRequestUtils.truncate(userAgent, 500),
                    ClientRequestUtils.truncate(deviceName, 200),
                    java.sql.Timestamp.from(Instant.now()),
                    java.sql.Timestamp.from(Instant.now()),
                    java.sql.Timestamp.from(expiresAt),
                    false);

            log.info("[Session] Recorded new session for user {} from '{}' ({}) in tenant '{}'",
                    userId, ipAddress, deviceName, TenantContextHolder.getTenantId());

        } catch (Exception e) {
            // Non-fatal — session tracking must never block login
            log.error("[Session] Failed to record session for user {}: {}", userId, e.getMessage());
        }

        return sessionToken;
    }
}
