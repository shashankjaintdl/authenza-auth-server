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
 * A tenant-aware {@link UserDetailsService} that implements a two-tier identity architecture:
 *
 * <h2>Tier 1 — Centralized Authentication (Primary Path)</h2>
 * <p>
 * Credentials (password hash, global account status) are resolved from the
 * <strong>master database</strong> ({@code global_accounts} joined with {@code tenant_memberships}).
 * This is the primary authentication path for all globally-registered users (admins,
 * collaborators, SaaS operators). Tenant RBAC roles and permissions are still loaded from
 * the active tenant's local {@code application_user}, {@code user_role}, and
 * {@code role_authority} tables.
 *
 * <h2>Tier 2 — Local Fallback (Backward-Compatible Path)</h2>
 * <p>
 * If a user is not found in {@code global_accounts} for the active tenant, the service
 * transparently falls back to the legacy behavior — authenticating entirely against the
 * active tenant's local {@code application_user} table. This ensures zero disruption
 * for existing tenant-local users.
 *
 * <h2>Auto-Unlock</h2>
 * <p>
 * On every load the service checks {@code locked_until}: if the timestamp is in the past
 * the account is automatically unlocked before Spring Security evaluates it, providing
 * transparent time-based lockout expiry.
 *
 * <h2>Option B — Shadow Admin Sentinel (Legacy)</h2>
 * <p>
 * Shadow admin records in local tenant DBs may carry the sentinel password
 * {@code [GLOBAL_ACCOUNT]}. The centralized path supersedes this mechanism, but the
 * sentinel fallback is preserved in {@link #loadFromLocalAccount} for backward compatibility
 * with any existing shadow records not yet covered by a {@code tenant_membership} row.
 */
@Service
public class JdbcTenantUserDetailsService implements UserDetailsService {

    // ── SQL: Step 1 — Resolve centralized credentials + validate tenant membership ──
    // Matches the user's email against global_accounts and confirms they have
    // an active tenant_memberships row for the current tenant.
    private static final String GLOBAL_ACCOUNT_MEMBERSHIP_QUERY =
            "SELECT ga.id AS global_id, ga.email, ga.password_hash, ga.status AS global_status " +
                    "FROM global_accounts ga " +
                    "INNER JOIN tenant_memberships tm ON ga.id = tm.account_id " +
                    "WHERE ga.email = ? AND tm.tenant_id = ? " +
                    "LIMIT 1";

    // ── SQL: Step 2a — Load tenant RBAC + per-account state for a global user ──
    // Password is intentionally excluded here — it comes from global_accounts.
    private static final String LOCAL_RBAC_QUERY =
            "SELECT u.id, u.preferred_username, u.status, u.locked_until, " +
                    "       u.mfa_enabled, u.mfa_secret, " +
                    "       r.name AS role_name, " +
                    "       a.permission AS authority_name " +
                    "FROM application_user u " +
                    "LEFT JOIN user_role ur ON u.id = ur.user_id " +
                    "LEFT JOIN role r ON ur.role_id = r.id " +
                    "LEFT JOIN role_authority ra ON r.id = ra.role_id " +
                    "LEFT JOIN authority a ON ra.authority_id = a.id " +
                    "WHERE u.preferred_username = ? OR u.email = ?";

    // ── SQL: Step 2b — Full local query for the legacy fallback path ──
    // Includes password so non-global users authenticate normally.
    private static final String USER_QUERY =
            "SELECT u.id, u.preferred_username, u.password, u.status, u.locked_until, " +
                    "       u.mfa_enabled, u.mfa_secret, " +
                    "       r.name AS role_name, " +
                    "       a.permission AS authority_name " +
                    "FROM application_user u " +
                    "LEFT JOIN user_role ur ON u.id = ur.user_id " +
                    "LEFT JOIN role r ON ur.role_id = r.id " +
                    "LEFT JOIN role_authority ra ON r.id = ra.role_id " +
                    "LEFT JOIN authority a ON ra.authority_id = a.id " +
                    "WHERE u.preferred_username = ? OR u.email = ?";

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantUserDetailsService.class);

    private final JdbcTemplate jdbcTemplate;
    // Master datasource — always points to the auth-master registry DB.
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

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String tenantId = TenantContextHolder.getTenantId();

        // ── Step 1: Centralized global_accounts lookup + tenant membership validation ──
        List<GlobalAccountRecord> globalRecords = masterJdbcTemplate.query(
                GLOBAL_ACCOUNT_MEMBERSHIP_QUERY,
                (rs, rowNum) -> {
                    GlobalAccountRecord record = new GlobalAccountRecord();
                    record.globalId = rs.getLong("global_id");
                    record.email = rs.getString("email");
                    record.passwordHash = rs.getString("password_hash");
                    record.globalStatus = rs.getString("global_status");
                    return record;
                },
                username, tenantId);

        if (!globalRecords.isEmpty()) {
            // ── Primary path: user is a globally-registered account with tenant membership ──
            return loadFromCentralizedAccount(globalRecords.get(0), username, tenantId);
        }

        // ── Step 2: Fallback to local application_user for legacy / tenant-local users ──
        log.debug("[Auth] User '{}' not found in global_accounts for tenant '{}'. Falling back to local lookup.",
                username, tenantId);
        return loadFromLocalAccount(username, tenantId);
    }

    /**
     * Builds {@link UserDetails} for a globally-registered user.
     *
     * <ul>
     *   <li>Password hash is sourced from {@code global_accounts.password_hash} (master DB).</li>
     *   <li>Account lock state ({@code locked_until}) is sourced from the local tenant shadow record.</li>
     *   <li>Global {@code SUSPENDED} status overrides local {@code ACTIVE}.</li>
     *   <li>RBAC roles and permissions are loaded from the active tenant DB.</li>
     * </ul>
     */
    private UserDetails loadFromCentralizedAccount(GlobalAccountRecord globalAccount, String username, String tenantId) {
        log.debug("[Auth] Centralized path: resolving credentials from global_accounts for user '{}' in tenant '{}'",
                username, tenantId);

        // Load RBAC + local account state from the active tenant DB
        List<UserRecord> localRecords = jdbcTemplate.query(LOCAL_RBAC_QUERY, (rs, rowNum) -> {
            UserRecord record = new UserRecord();
            record.id = rs.getLong("id");
            record.username = rs.getString("preferred_username");
            record.status = rs.getString("status");
            Timestamp lockedUntilTs = rs.getTimestamp("locked_until");
            record.lockedUntil = (lockedUntilTs != null) ? lockedUntilTs.toInstant() : null;
            record.mfaEnabled = rs.getBoolean("mfa_enabled");
            record.mfaSecret = rs.getString("mfa_secret");
            record.roleName = rs.getString("role_name");
            record.authorityName = rs.getString("authority_name");
            return record;
        }, username, username);

        // Determine effective account status:
        //   - Global SUSPENDED overrides everything (platform-wide ban).
        //   - Otherwise, defer to local status for tenant-scoped lock state.
        String effectiveStatus = globalAccount.globalStatus;
        Instant lockedUntil = null;

        if (!localRecords.isEmpty()) {
            UserRecord localBase = localRecords.get(0);
            lockedUntil = localBase.lockedUntil;
            // Respect local LOCKED state unless global has already suspended the account
            if ("ACTIVE".equalsIgnoreCase(effectiveStatus) && "LOCKED".equalsIgnoreCase(localBase.status)) {
                effectiveStatus = "LOCKED";
            }
        }

        // ── Auto-unlock: if locked_until is in the past, transparently restore the account ──
        if ("LOCKED".equalsIgnoreCase(effectiveStatus) && lockedUntil != null && Instant.now().isAfter(lockedUntil)) {
            bruteForceProtectionService.clearLock(username, tenantId);
            effectiveStatus = "ACTIVE";
            log.debug("[Auth] Auto-unlock applied for global user '{}' in tenant '{}'", username, tenantId);
        }

        Set<GrantedAuthority> authorities = buildAuthorities(localRecords);

        boolean enabled = "ACTIVE".equalsIgnoreCase(effectiveStatus)
                || "PENDING_VERIFICATION".equalsIgnoreCase(effectiveStatus);
        boolean accountNonLocked = !"LOCKED".equalsIgnoreCase(effectiveStatus);

        return User.builder()
                .username(username)
                .password(globalAccount.passwordHash)
                .authorities(new ArrayList<>(authorities))
                .disabled(!enabled)
                .accountLocked(!accountNonLocked)
                .accountExpired(false)
                .credentialsExpired(false)
                .build();
    }

    /**
     * Builds {@link UserDetails} for a legacy tenant-local user (backward-compatible fallback).
     *
     * <p>All fields including password are sourced from the active tenant's
     * {@code application_user} table. Preserves Option B (shadow admin sentinel
     * {@code [GLOBAL_ACCOUNT]}) for any existing shadow records not yet covered
     * by a {@code tenant_memberships} entry.
     */
    private UserDetails loadFromLocalAccount(String username, String tenantId) {
        List<UserRecord> records = jdbcTemplate.query(USER_QUERY, (rs, rowNum) -> {
            UserRecord record = new UserRecord();
            record.id = rs.getLong("id");
            record.username = rs.getString("preferred_username");
            record.password = rs.getString("password");
            record.status = rs.getString("status");
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

        // ── Auto-unlock: if locked_until is in the past, transparently restore the account ──
        if ("LOCKED".equalsIgnoreCase(base.status) && base.lockedUntil != null
                && Instant.now().isAfter(base.lockedUntil)) {
            bruteForceProtectionService.clearLock(base.username, tenantId);
            base.status = "ACTIVE";
        }

        // ── Option B: Passwordless Shadow Admin Login Fallback (legacy support) ────────────
        // Shadow admin records created before tenant_memberships existed carry the sentinel
        // [GLOBAL_ACCOUNT]. Resolve their hash from global_accounts as a bridge mechanism
        // until those records are migrated to a formal tenant_membership row.
        String resolvedPassword = base.password;
        if ("[GLOBAL_ACCOUNT]".equals(resolvedPassword)) {
            List<String> globalPwd = masterJdbcTemplate.query(
                    "SELECT password_hash FROM global_accounts WHERE email = ? LIMIT 1",
                    (rs, i) -> rs.getString("password_hash"),
                    username);
            if (globalPwd.isEmpty()) {
                throw new UsernameNotFoundException(
                        "Shadow admin '" + username + "' has no password and no global_accounts record.");
            }
            resolvedPassword = globalPwd.get(0);
            log.debug("[Auth] Option B fallback: resolved BCrypt hash from global_accounts for '{}'", username);
        }
        // ──────────────────────────────────────────────────────────────────────────────────

        Set<GrantedAuthority> authorities = buildAuthorities(records);

        boolean enabled = "ACTIVE".equalsIgnoreCase(base.status)
                || "PENDING_VERIFICATION".equalsIgnoreCase(base.status);
        boolean accountNonLocked = !"LOCKED".equalsIgnoreCase(base.status);

        return User.builder()
                .username(base.username)
                .password(resolvedPassword)
                .authorities(new ArrayList<>(authorities))
                .disabled(!enabled)
                .accountLocked(!accountNonLocked)
                .accountExpired(false)
                .credentialsExpired(false)
                .build();
    }

    /**
     * Builds the full {@link GrantedAuthority} set from tenant RBAC records,
     * adding standard OIDC scopes required by Spring Authorization Server.
     */
    private Set<GrantedAuthority> buildAuthorities(List<UserRecord> records) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        for (UserRecord record : records) {
            if (record.roleName != null) {
                // Ensure roles start with ROLE_ per Spring Security conventions
                String role = record.roleName.startsWith("ROLE_") ? record.roleName : "ROLE_" + record.roleName;
                authorities.add(new SimpleGrantedAuthority(role));
            }
            if (record.authorityName != null && !record.authorityName.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority(record.authorityName));
            }
        }
        // Add standard OIDC scopes as authorities so Spring Authorization Server
        // grants them during the token exchange.
        authorities.add(new SimpleGrantedAuthority("SCOPE_openid"));
        authorities.add(new SimpleGrantedAuthority("SCOPE_profile"));
        authorities.add(new SimpleGrantedAuthority("SCOPE_offline_access"));
        return authorities;
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
     * <p>This method works for both globally-registered and tenant-local users, since
     * MFA enrollment state is stored in the tenant's local {@code application_user} record.
     *
     * @param username the username or email
     * @return {@code true} if MFA must be completed before login is finalized
     */
    public boolean isMfaRequired(String username) {
        boolean tenantMfa = isTenantMfaRequired();
        log.debug("[MFA-DEBUG] isTenantMfaRequired={} for tenant '{}'",
                tenantMfa, TenantContextHolder.getTenantId());
        if (tenantMfa) {
            log.debug("[MFA-DEBUG] MFA required for '{}' via tenant policy", username);
            return true;
        }
        // MFA is required ONLY if mfa_enabled = true (user opted in or admin enabled it).
        // An orphan secret (mfa_secret IS NOT NULL but mfa_enabled = false) is from an
        // abandoned setup and must NOT block login — it will be cleaned up on next
        // successful non-MFA login via clearOrphanMfaSecret().
        List<Boolean> result = jdbcTemplate.query(
                "SELECT mfa_enabled AS required " +
                        "FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getBoolean("required"),
                username, username);
        boolean userMfa = !result.isEmpty() && Boolean.TRUE.equals(result.get(0));
        log.debug("[MFA-DEBUG] mfa_enabled={} for user '{}' in tenant '{}'",
                userMfa, username, TenantContextHolder.getTenantId());
        return userMfa;
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
     * <p>This method works for both globally-registered and tenant-local users, since
     * MFA enrollment state is stored in the tenant's local {@code application_user} record.
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
     *
     * <p>This method queries the local tenant {@code application_user} table only,
     * since password-change enforcement is a tenant-scoped policy.
     */
    public boolean isPasswordChangeRequired(String username) {
        List<Boolean> result = jdbcTemplate.query(
                "SELECT requires_password_change FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getBoolean("requires_password_change"),
                username, username);
        return !result.isEmpty() && Boolean.TRUE.equals(result.get(0));
    }

    /**
     * Reverse-lookup: resolves the {@code preferred_username} for a given user
     * database ID.
     *
     * <p>
     * Used by {@link com.authenza.core.web.WebAuthnBridgeController} to convert the
     * {@code userId} from the bridge token back into a username so that
     * {@link #loadUserByUsername(String)} can load the full {@link UserDetails}.
     * </p>
     *
     * @param userId the database primary key of the user (local {@code application_user.id})
     * @return the {@code preferred_username}, or {@code null} if no user found
     */
    public String loadUsernameById(Long userId) {
        List<String> result = jdbcTemplate.query(
                "SELECT preferred_username FROM application_user WHERE id = ?",
                (rs, rowNum) -> rs.getString("preferred_username"),
                userId);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Clears an orphan {@code mfa_secret} left over from an abandoned MFA setup.
     *
     * <p>Called after a successful non-MFA login when {@code mfa_enabled = false}
     * but a stale secret exists in the DB. This keeps the database clean and
     * prevents ghost secrets from causing confusion in future setups.
     *
     * @param username the username or email of the user
     */
    public void clearOrphanMfaSecret(String username) {
        int updated = jdbcTemplate.update(
                "UPDATE application_user SET mfa_secret = NULL " +
                "WHERE (preferred_username = ? OR email = ?) " +
                "AND mfa_enabled = false AND mfa_secret IS NOT NULL",
                username, username);
        if (updated > 0) {
            log.info("[MFA] Cleared orphan mfa_secret for user '{}' on successful non-MFA login", username);
        }
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
     * <p>MFA secrets are stored in the tenant's local {@code application_user} record
     * for both globally-registered and tenant-local users.
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
     * Looks up the local database primary key for a user from the active tenant's
     * {@code application_user} table.
     *
     * <p>Used by {@link com.authenza.core.web.MfaSetupController} to call the IAM
     * service MFA setup/confirm endpoints which require the user ID.
     *
     * <p>For globally-registered users this returns the local shadow record's ID
     * (not the {@code global_accounts.id}), since MFA operations target the
     * tenant-scoped record.
     *
     * @param username the username or email
     * @return the user's local {@code application_user.id}, or {@code null} if not found
     */
    public Long loadUserId(String username) {
        List<Long> result = jdbcTemplate.query(
                "SELECT id FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                username, username);
        return result.isEmpty() ? null : result.get(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner record types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lightweight projection of a {@code global_accounts + tenant_memberships} join result.
     */
    private static class GlobalAccountRecord {
        Long globalId;
        String email;
        String passwordHash;
        String globalStatus;
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
