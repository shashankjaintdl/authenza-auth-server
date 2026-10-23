package com.authenza.iam.service;

import com.authenza.iam.dto.TrustedDeviceResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * IAM-side trusted-device list/revoke for the tenant portal.
 * Shares the {@code user_trusted_device} table written by auth-server-core at login.
 */
@Service
public class TrustedDeviceAdminService {

    private final JdbcTemplate jdbcTemplate;

    public TrustedDeviceAdminService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public List<TrustedDeviceResponse> listActive(Long userId) {
        return jdbcTemplate.query(
                """
                        SELECT id, device_name, last_ip, last_country,
                               created_at, last_seen_at, expires_at
                        FROM   user_trusted_device
                        WHERE  user_id = ?
                          AND  revoked = ?
                          AND  expires_at > ?
                        ORDER BY last_seen_at DESC
                        """,
                (rs, rowNum) -> new TrustedDeviceResponse(
                        rs.getLong("id"),
                        rs.getString("device_name"),
                        rs.getString("last_ip"),
                        rs.getString("last_country"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("last_seen_at")),
                        toInstant(rs.getTimestamp("expires_at"))
                ),
                userId,
                Boolean.FALSE,
                Timestamp.from(Instant.now()));
    }

    public boolean revoke(Long userId, Long deviceId) {
        int updated = jdbcTemplate.update(
                """
                        UPDATE user_trusted_device
                        SET    revoked = ?
                        WHERE  id = ? AND user_id = ? AND revoked = ?
                        """,
                Boolean.TRUE, deviceId, userId, Boolean.FALSE);
        return updated > 0;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
