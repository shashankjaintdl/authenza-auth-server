package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.adapter.cache.TenantSettingsCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A tenant-aware UserDetailsService that fetches users dynamically from the
 * active
 * tenant database utilizing the underlying multi-tenant RoutingDataSource.
 *
 * <p>
 * On every load it checks {@code locked_until}: if the timestamp has passed the
 * account is automatically unlocked before Spring Security evaluates it,
 * providing
 * transparent time-based lockout expiry.
 */
@Service
public class JdbcTenantUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantUserDetailsService.class);

    private final JdbcTemplate jdbcTemplate;
    // Master datasource for Option B login fallback — resolves BCrypt hash
    // for passwordless shadow admin users from global_accounts.
    private final JdbcTemplate masterJdbcTemplate;
    private final BruteForceProtectionService bruteForceProtectionService;
    private final TenantSettingsCache settingsCache;

    public JdbcTenantUserDetailsService(
            DataSource dataSource,
            @Qualifier("masterDataSource") DataSource masterDataSource,
            BruteForceProtectionService bruteForceProtectionService,
            TenantSettingsCache settingsCache) {
        // The primary dataSource is natively tenant-aware (RoutingDataSource).
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        // The master datasource is fixed — always points to the master registry DB.
        this.masterJdbcTemplate = new JdbcTemplate(masterDataSource);
        this.bruteForceProtectionService = bruteForceProtectionService;
        this.settingsCache = settingsCache;
    }

    private static final String USER_QUERY = "SELECT u.id, u.preferred_username, u.password, u.status, u.locked_until, "
            +
            "       u.mfa_enabled, u.mfa_secret, " +
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
            // locked_until may be null for accounts that have never been locked
            Timestamp lockedUntilTs = rs.getTimestamp("locked_until");
            record.lockedUntil = (lockedUntilTs != null) ? lockedUntilTs.toInstant() : null;
            record.mfaEnabled = rs.getBoolean("mfa_enabled");
            record.mfaSecret = rs.getString("mfa_secret");
            record.roleName = rs.getString("role_name");
            record.authorityName = rs.getString("authority_name");
            return record;
        }, username, username);

        if (records.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        UserRecord base = records.get(0);

        // ── Auto-unlock: if locked_until is in the past, transparently restore the
        // account ──
        if ("LOCKED".equalsIgnoreCase(base.status) && base.lockedUntil != null
                && Instant.now().isAfter(base.lockedUntil)) {
            String tenantId = TenantContextHolder.getTenantId();
            bruteForceProtectionService.clearLock(base.username, tenantId);
            // Update local record so the UserDetails object reflects the unlocked state
            base.status = "ACTIVE";
        }

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

        boolean enabled = "ACTIVE".equalsIgnoreCase(base.status)
                || "PENDING_VERIFICATION".equalsIgnoreCase(base.status);
        boolean accountNonLocked = !"LOCKED".equalsIgnoreCase(base.status);
        boolean accountNonExpired = true;
        boolean credentialsNonExpired = true;

        // ── Option B: Passwordless Shadow Admin Login Fallback ──────────────────
        // If the shadow user's password is the sentinel [GLOBAL_ACCOUNT], this is a global admin whose
        // credential is stored exclusively in global_accounts (master DB).
        // We resolve it here so Spring Security can verify it normally.
        String resolvedPassword = base.password;
        if ("[GLOBAL_ACCOUNT]".equals(resolvedPassword)) {
            List<String> globalPwd = masterJdbcTemplate.query(
                "SELECT password_hash FROM global_accounts WHERE email = ? LIMIT 1",
                (rs, i) -> rs.getString("password_hash"),
                username
            );
            if (globalPwd.isEmpty()) {
                throw new UsernameNotFoundException(
                    "Shadow admin '" + username + "' has no password and no global_accounts record.");
            }
            resolvedPassword = globalPwd.get(0);
            log.debug("Option B fallback: resolved BCrypt hash from global_accounts for '{}'", username);
        }
        // ───────────────────────────────────────────────────────────────────────

        return User.builder()
                .username(base.username)
                .password(resolvedPassword)
                .authorities(new ArrayList<>(authorities))
                .disabled(!enabled)
                .accountLocked(!accountNonLocked)
                .accountExpired(!accountNonExpired)
                .credentialsExpired(!credentialsNonExpired)
                .build();
    }

    /**
     * Determines whether MFA is required for the given user, considering
     * both the <strong>tenant-level policy</strong> and the <strong>per-user
     * flag</strong>.
     *
     * <p>
     * Resolution order:
     * <ol>
     * <li>If {@code tenant_settings.mfa_required_for_all = 'true'} → MFA required
     * for everyone.</li>
     * <li>Otherwise falls back to {@code application_user.mfa_enabled} for per-user
     * enforcement.</li>
     * </ol>
     *
     * @param username the username or email
     * @return {@code true} if MFA must be completed before login is finalized
     */
    public boolean isMfaRequired(String username) {
        if (isTenantMfaRequired()) {
            return true;
        }
        // MFA is required if EITHER:
        // (a) mfa_enabled = true → user/admin deliberately opted in, OR
        // (b) mfa_secret IS NOT NULL → a secret was generated but never confirmed
        // (user opened /mfa-setup, setupMfa() reset mfa_enabled to false,
        // then the browser was closed before scanning — we must still force
        // them back to complete setup on next login)
        List<Boolean> result = jdbcTemplate.query(
                "SELECT (mfa_enabled = true OR mfa_secret IS NOT NULL) AS required " +
                        "FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getBoolean("required"),
                username, username);
        return !result.isEmpty() && Boolean.TRUE.equals(result.get(0));
    }

    /**
     * Returns {@code true} only if the user has <strong>fully completed</strong>
     * MFA enrollment — i.e. {@code mfa_enabled = true} AND {@code mfa_secret} is
     * set.
     *
     * <p>
     * This is the definitive routing check used after password verification:
     * <ul>
     * <li>{@code true} → user scanned QR and confirmed a code → send to
     * {@code /mfa-verify}</li>
     * <li>{@code false} → user visited setup page but closed without confirming,
     * OR has never visited setup at all → send back to {@code /mfa-setup}</li>
     * </ul>
     *
     * <p>
     * Using only {@code mfa_secret != null} is not sufficient because
     * {@link com.authenza.iam.service.MfaService#setupMfa} writes the secret
     * <em>before</em> confirmation and resets {@code mfa_enabled} to {@code false}.
     * The secret therefore exists in an "unconfirmed" state until
     * {@code confirmMfa} runs.
     *
     * @param username the username or email
     * @return {@code true} if the user has a confirmed, active TOTP enrollment
     */
    public boolean isMfaFullyEnrolled(String username) {
        List<Boolean> result = jdbcTemplate.query(
                "SELECT (mfa_enabled = true AND mfa_secret IS NOT NULL) AS enrolled " +
                        "FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getBoolean("enrolled"),
                username, username);
        return !result.isEmpty() && Boolean.TRUE.equals(result.get(0));
    }

    /**
     * Determines whether the user is required to change their password
     * before login can be completed.
     */
    public boolean isPasswordChangeRequired(String username) {
        List<Boolean> result = jdbcTemplate.query(
                "SELECT requires_password_change FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getBoolean("requires_password_change"),
                username, username);
        return !result.isEmpty() && Boolean.TRUE.equals(result.get(0));
    }

    /**
     * Reads the tenant-wide MFA enforcement setting from the cache.
     * Returns {@code false} safely if the row is missing (e.g. before V0.0.7
     * migration).
     */
    private boolean isTenantMfaRequired() {
        try {
            String value = settingsCache.getSetting(TenantContextHolder.getTenantId(), "mfa_required_for_all");
            return "true".equalsIgnoreCase(value);
        } catch (Exception e) {
            // Table may not exist yet (pre-migration) — treat as not required
            return false;
        }
    }

    /**
     * Lightweight query to retrieve the encrypted TOTP secret for an MFA check.
     * Called by {@link MfaAuthenticationFilter} after the password step passes.
     *
     * @param username the username or email
     * @return the AES-encrypted secret string, or {@code null} if not set
     */
    public String loadMfaSecret(String username) {
        List<String> result = jdbcTemplate.query(
                "SELECT mfa_secret FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("mfa_secret"),
                username, username);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Looks up the database primary key for a user.
     * Used by {@link com.authenza.core.web.MfaSetupController} to call the IAM
     * service
     * MFA setup/confirm endpoints which require the user ID.
     *
     * @param username the username or email
     * @return the user's {@code id}, or {@code null} if not found
     */
    public Long loadUserId(String username) {
        List<Long> result = jdbcTemplate.query(
                "SELECT id FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                username, username);
        return result.isEmpty() ? null : result.get(0);
    }

    private static class UserRecord {
        Long id;
        String username;
        String password;
        String status;
        Instant lockedUntil;
        boolean mfaEnabled;
        String mfaSecret;
        String roleName;
        String authorityName;
    }
}
