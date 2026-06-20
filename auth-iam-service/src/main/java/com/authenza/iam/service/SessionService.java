package com.authenza.iam.service;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.exception.ResourceNotFoundException;
import com.authenza.iam.dto.SessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Manages active user sessions stored in the {@code user_session} table.
 *
 * <p>Session revocation:
 * <ol>
 *   <li>Deletes the {@code oauth2_authorization} row — blocks refresh-token reuse.</li>
 *   <li>Writes the {@code session_id} to the Redis blacklist via {@link RevokedSessionStore}
 *       — causes the IAM service to reject the still-live access token on its next request,
 *       giving <em>immediate</em> forced-logout without waiting for token expiry.</li>
 * </ol>
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RevokedSessionStore revokedSessionStore;

    public SessionService(JdbcTemplate jdbcTemplate, RevokedSessionStore revokedSessionStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.revokedSessionStore = revokedSessionStore;
    }

    // ─────────────────────────────────────────────
    // Query
    // ─────────────────────────────────────────────

    /**
     * Returns all non-revoked sessions for the given user.
     *
     * @param userId the user's database PK
     * @return a list of active {@link SessionResponse} DTOs, newest first
     */
    public List<SessionResponse> listActiveSessions(Long userId) {
        String sql = """
                SELECT id, device_name, ip_address, user_agent, created_at, last_active_at
                FROM   user_session
                WHERE  user_id  = ?
                  AND  revoked  = false
                  AND  (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                ORDER  BY created_at DESC
                """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    SessionResponse s = new SessionResponse();
                    s.setId(rs.getLong("id"));
                    s.setDeviceName(rs.getString("device_name"));
                    s.setIpAddress(rs.getString("ip_address"));
                    s.setUserAgent(rs.getString("user_agent"));
                    s.setCreatedAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant() : null);
                    s.setLastActiveAt(rs.getTimestamp("last_active_at") != null
                            ? rs.getTimestamp("last_active_at").toInstant() : null);
                    return s;
                },
                userId);
    }

    // ─────────────────────────────────────────────
    // Revocation
    // ─────────────────────────────────────────────

    /**
     * Revokes a specific session.
     *
     * <ol>
     *   <li>Verifies the session belongs to {@code userId}.</li>
     *   <li>Marks the {@code user_session} row as revoked.</li>
     *   <li>Deletes the linked {@code oauth2_authorization} row to block refresh-token reuse.</li>
     *   <li>Writes the {@code session_id} to Redis so the access token is rejected immediately.</li>
     * </ol>
     */
    @Transactional
    public void revokeSession(Long userId, Long sessionId) {
        // Fetch auth ID + access-token expiry before revoking
        String fetchSql = """
                SELECT authorization_id, expires_at FROM user_session
                WHERE  id      = ?
                  AND  user_id = ?
                  AND  revoked = false
                """;

        var rows = jdbcTemplate.query(fetchSql,
                (rs, rowNum) -> new Object[]{
                        rs.getString("authorization_id"),
                        rs.getTimestamp("expires_at")
                },
                sessionId, userId);

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Session not found or already revoked (id=" + sessionId + ").");
        }

        String authorizationId = (String) rows.get(0)[0];
        java.sql.Timestamp expiresAt = (java.sql.Timestamp) rows.get(0)[1];

        // Mark session as revoked
        jdbcTemplate.update(
                "UPDATE user_session SET revoked = true, revoked_at = ?, authorization_id = NULL WHERE id = ?",
                java.sql.Timestamp.from(Instant.now()), sessionId);

        // Delete the OAuth2 authorization to block refresh-token reuse.
        // NOTE: We clear authorization_id BEFORE this delete so that the JWT
        // token customizer can re-link the new oauth2_authorization to this
        // (now revoked) row on a silent re-auth page reload. The revoked row's
        // numeric id will then be embedded as session_id in the new JWT, and the
        // Redis blacklist check in RevokedSessionJwtValidator will reject it → 401.
        if (authorizationId != null) {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM oauth2_authorization WHERE id = ?", authorizationId);
            log.info("[Session] Deleted oauth2_authorization '{}' for session {} (user {})",
                    authorizationId, sessionId, userId);
            if (deleted == 0) {
                log.debug("[Session] No oauth2_authorization row found for id '{}'", authorizationId);
            }
        }

        // Blacklist the session_id in Redis so the active access token is rejected immediately
        Instant tokenExpiry = (expiresAt != null) ? expiresAt.toInstant() : null;
        revokedSessionStore.revoke(sessionId.toString(), tokenExpiry);

        log.info("[Session] Revoked session {} for user {} in tenant '{}'",
                sessionId, userId, TenantContextHolder.getTenantId());
    }

    /**
     * Revokes <em>all</em> active sessions for a user — "Sign out of all devices".
     */
    @Transactional
    public int revokeAllSessions(Long userId) {
        // Collect active session IDs and authorization IDs before revoking
        var rows = jdbcTemplate.query(
                "SELECT id, authorization_id FROM user_session WHERE user_id = ? AND revoked = false",
                (rs, rowNum) -> new Object[]{rs.getLong("id"), rs.getString("authorization_id")},
                userId);

        // Mark all revoked
        int count = jdbcTemplate.update(
                "UPDATE user_session SET revoked = true, revoked_at = ? WHERE user_id = ? AND revoked = false",
                java.sql.Timestamp.from(Instant.now()), userId);

        for (Object[] row : rows) {
            Long sid = (Long) row[0];
            String authId = (String) row[1];

            // Blacklist each session_id in Redis
            revokedSessionStore.revoke(sid.toString());

            // Delete OAuth2 authorization
            if (authId != null && !authId.isBlank()) {
                jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE id = ?", authId);
            }
        }

        log.info("[Session] Revoked {} session(s) for user {} in tenant '{}'",
                count, userId, TenantContextHolder.getTenantId());
        return count;
    }
}
