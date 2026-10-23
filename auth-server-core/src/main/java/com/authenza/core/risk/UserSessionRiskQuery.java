package com.authenza.core.risk;

import com.authenza.core.util.ClientRequestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Read-only session history queries for adaptive signals (O(1) indexed lookups).
 */
@Repository
public class UserSessionRiskQuery {

    private final JdbcTemplate jdbcTemplate;

    public UserSessionRiskQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasSeenIpWithinDays(Long userId, String ipAddress, int lookbackDays) {
        Instant since = Instant.now().minus(Math.max(1, lookbackDays), ChronoUnit.DAYS);
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(1)
                        FROM   user_session
                        WHERE  user_id    = ?
                          AND  ip_address = ?
                          AND  created_at >= ?
                        """,
                Integer.class,
                userId,
                ClientRequestUtils.truncate(ipAddress, 100),
                Timestamp.from(since));
        return count != null && count > 0;
    }
}
