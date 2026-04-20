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
 * <p>Session revocation deletes the corresponding {@code oauth2_authorization} row
 * (if any), immediately preventing refresh-token reuse. Access tokens remain
 * technically valid until they expire (typically 5–60 min) unless token
 * introspection is enabled on resource servers.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final JdbcTemplate jdbcTemplate;

    public SessionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
     *   <li>Verifies the session belongs to {@code userId} (prevents cross-user revocation).</li>
     *   <li>Marks the {@code user_session} row as revoked.</li>
     *   <li>Deletes the linked {@code oauth2_authorization} row to block refresh-token reuse.</li>
     * </ol>
     *
     * @param userId    the user who owns the session
     * @param sessionId the {@code user_session.id} to revoke
     * @throws ResourceNotFoundException if the session does not exist or belongs to another user
     */
    @Transactional
    public void revokeSession(Long userId, Long sessionId) {
        // Fetch the authorization_id before revoking (needed for cascade delete)
        String fetchSql = """
                SELECT authorization_id FROM user_session
                WHERE  id      = ?
                  AND  user_id = ?
                  AND  revoked = false
                """;

        List<String> authIds = jdbcTemplate.query(fetchSql,
                (rs, rowNum) -> rs.getString("authorization_id"),
                sessionId, userId);

        if (authIds.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Session not found or already revoked (id=" + sessionId + ").");
        }

        // Mark session as revoked
        jdbcTemplate.update(
                "UPDATE user_session SET revoked = true, revoked_at = ? WHERE id = ?",
                Instant.now(), sessionId);

        // Delete the OAuth2 authorization to block refresh-token reuse
        String authorizationId = authIds.get(0);
        if (authorizationId != null) {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM oauth2_authorization WHERE id = ?", authorizationId);
            log.info("[Session] Deleted oauth2_authorization '{}' for session {} (user {})",
                    authorizationId, sessionId, userId);
            if (deleted == 0) {
                log.debug("[Session] No oauth2_authorization row found for id '{}' — may have already expired",
                        authorizationId);
            }
        }

        log.info("[Session] Revoked session {} for user {} in tenant '{}'",
                sessionId, userId, TenantContextHolder.getTenantId());
    }

    /**
     * Revokes <em>all</em> active sessions for a user — equivalent to
     * "Sign out of all devices".
     *
     * @param userId the user whose sessions should be revoked
     * @return the number of sessions revoked
     */
    @Transactional
    public int revokeAllSessions(Long userId) {
        // Collect active authorization IDs before revoking
        List<String> authIds = jdbcTemplate.query(
                "SELECT authorization_id FROM user_session WHERE user_id = ? AND revoked = false",
                (rs, rowNum) -> rs.getString("authorization_id"),
                userId);

        // Mark all user_session rows as revoked in one statement
        int count = jdbcTemplate.update(
                "UPDATE user_session SET revoked = true, revoked_at = ? WHERE user_id = ? AND revoked = false",
                Instant.now(), userId);

        // Delete all linked oauth2_authorization rows
        authIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .forEach(id -> jdbcTemplate.update(
                        "DELETE FROM oauth2_authorization WHERE id = ?", id));

        log.info("[Session] Revoked {} session(s) for user {} in tenant '{}'",
                count, userId, TenantContextHolder.getTenantId());

        return count;
    }
}
