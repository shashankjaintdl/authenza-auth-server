package com.authenza.core.risk;

import com.authenza.core.util.ClientRequestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TrustedDeviceRepository {

    private final JdbcTemplate jdbcTemplate;

    public TrustedDeviceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsActive(Long userId, String fingerprint) {
        // EXISTS + LIMIT 1 avoids full COUNT scans on (user_id, fingerprint)
        List<Integer> hit = jdbcTemplate.query(
                """
                        SELECT 1
                        FROM   user_trusted_device
                        WHERE  user_id = ?
                          AND  device_fingerprint = ?
                          AND  revoked = ?
                          AND  expires_at > ?
                        LIMIT  1
                        """,
                (rs, rowNum) -> 1,
                userId,
                fingerprint,
                Boolean.FALSE,
                Timestamp.from(Instant.now()));
        return !hit.isEmpty();
    }

    public void upsertTrustedDevice(Long userId, String fingerprint, String deviceName,
                                    String ipAddress, Instant expiresAt) {
        Instant now = Instant.now();
        Optional<Long> existingId = findActiveId(userId, fingerprint);
        if (existingId.isPresent()) {
            jdbcTemplate.update(
                    """
                            UPDATE user_trusted_device
                            SET    last_seen_at = ?, last_ip = ?, device_name = ?, expires_at = ?, revoked = ?
                            WHERE  id = ?
                            """,
                    Timestamp.from(now),
                    ClientRequestUtils.truncate(ipAddress, 45),
                    ClientRequestUtils.truncate(deviceName, 200),
                    Timestamp.from(expiresAt),
                    Boolean.FALSE,
                    existingId.get());
            return;
        }
        jdbcTemplate.update(
                """
                        INSERT INTO user_trusted_device
                        (user_id, device_fingerprint, device_name, last_ip, last_country,
                         created_at, last_seen_at, expires_at, revoked)
                        VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?)
                        """,
                userId,
                fingerprint,
                ClientRequestUtils.truncate(deviceName, 200),
                ClientRequestUtils.truncate(ipAddress, 45),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                Boolean.FALSE);
    }

    public List<TrustedDeviceRow> listActive(Long userId) {
        return jdbcTemplate.query(
                """
                        SELECT id, device_fingerprint, device_name, last_ip, last_country,
                               created_at, last_seen_at, expires_at, revoked
                        FROM   user_trusted_device
                        WHERE  user_id = ?
                          AND  revoked = ?
                          AND  expires_at > ?
                        ORDER BY last_seen_at DESC
                        """,
                (rs, rowNum) -> new TrustedDeviceRow(
                        rs.getLong("id"),
                        rs.getString("device_fingerprint"),
                        rs.getString("device_name"),
                        rs.getString("last_ip"),
                        rs.getString("last_country"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("revoked")
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

    private Optional<Long> findActiveId(Long userId, String fingerprint) {
        List<Long> ids = jdbcTemplate.query(
                """
                        SELECT id FROM user_trusted_device
                        WHERE  user_id = ? AND device_fingerprint = ?
                        ORDER BY id DESC
                        """,
                (rs, rowNum) -> rs.getLong("id"),
                userId, fingerprint);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public record TrustedDeviceRow(
            long id,
            String fingerprint,
            String deviceName,
            String lastIp,
            String lastCountry,
            Instant createdAt,
            Instant lastSeenAt,
            Instant expiresAt,
            boolean revoked
    ) {
    }
}
