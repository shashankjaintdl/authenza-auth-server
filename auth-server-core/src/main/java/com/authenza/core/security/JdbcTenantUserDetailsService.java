package com.authenza.core.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A tenant-aware UserDetailsService that fetches users dynamically from the active 
 * tenant database utilizing the underlying multi-tenant RoutingDataSource.
 */
@Service
public class JdbcTenantUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantUserDetailsService(DataSource dataSource) {
        // The dataSource injected here is natively tenant-aware.
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    private static final String USER_QUERY =
            "SELECT u.id, u.preferred_username, u.password, u.status, " +
            "       r.name AS role_name, " +
            "       a.permission AS authority_name " +
            "FROM application_user u " +
            "LEFT JOIN user_role ur ON u.id = ur.user_id " +
            "LEFT JOIN role r ON ur.role_id = r.id " +
            "LEFT JOIN role_authority ra ON r.id = ra.role_id " +
            "LEFT JOIN authority a ON ra.authority_id = a.id " +
            "WHERE u.preferred_username = ? OR u.email = ?";

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        List<UserRecord> records = jdbcTemplate.query(USER_QUERY, (rs, rowNum) -> {
            UserRecord record = new UserRecord();
            record.id = rs.getLong("id");
            record.username = rs.getString("preferred_username");
            record.password = rs.getString("password");
            record.status = rs.getString("status");
            record.roleName = rs.getString("role_name");
            record.authorityName = rs.getString("authority_name");
            return record;
        }, username, username);

        if (records.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        UserRecord base = records.get(0);
        Set<GrantedAuthority> authorities = new HashSet<>();

        for (UserRecord record : records) {
            if (record.roleName != null) {
                // Ensure Roles start with ROLE_ per Spring Security conventions
                String role = record.roleName.startsWith("ROLE_") ? record.roleName : "ROLE_" + record.roleName;
                authorities.add(new SimpleGrantedAuthority(role));
            }
            if (record.authorityName != null && !record.authorityName.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority(record.authorityName));
            }
        }

        boolean enabled = "ACTIVE".equalsIgnoreCase(base.status) || "PENDING_VERIFICATION".equalsIgnoreCase(base.status);
        boolean accountNonLocked = !"LOCKED".equalsIgnoreCase(base.status);
        boolean accountNonExpired = true;
        boolean credentialsNonExpired = true;

        return User.builder()
                .username(base.username)
                .password(base.password)
                .authorities(new ArrayList<>(authorities))
                .disabled(!enabled)
                .accountLocked(!accountNonLocked)
                .accountExpired(!accountNonExpired)
                .credentialsExpired(!credentialsNonExpired)
                .build();
    }

    private static class UserRecord {
        Long id;
        String username;
        String password;
        String status;
        String roleName;
        String authorityName;
    }
}
