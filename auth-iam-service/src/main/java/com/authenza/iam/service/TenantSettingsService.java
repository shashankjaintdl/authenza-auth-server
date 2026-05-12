package com.authenza.iam.service;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.adapter.cache.TenantSettingsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages key-value settings stored in the {@code tenant_settings} table.
 *
 * <p>
 * Each tenant has its own isolated settings stored in the routing datasource,
 * meaning settings are always scoped to the active {@link TenantContextHolder}
 * tenant.
 *
 * <h3>Supported settings</h3>
 * <ul>
 * <li>{@code mfa_required_for_all} — {@code "true"} / {@code "false"} —
 * enforces TOTP MFA for every user in the tenant regardless of per-user
 * flags.</li>
 * </ul>
 */
@Service
public class TenantSettingsService {

    private static final Logger log = LoggerFactory.getLogger(TenantSettingsService.class);

    public static final String MFA_REQUIRED_FOR_ALL = "mfa_required_for_all";

    private final JdbcTemplate jdbcTemplate;
    private final TenantSettingsCache settingsCache;

    public TenantSettingsService(DataSource dataSource, TenantSettingsCache settingsCache) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.settingsCache = settingsCache;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all tenant settings as a key-value map.
     */
    public Map<String, String> getAllSettings() {
        return settingsCache.getAllSettings(TenantContextHolder.getTenantId());
    }

    /**
     * Reads a single setting by key.
     *
     * @param key the setting key (e.g. {@code "mfa_required_for_all"})
     * @return the value string, or {@code null} if not set
     */
    public String getSetting(String key) {
        return settingsCache.getSetting(TenantContextHolder.getTenantId(), key);
    }

    /**
     * Returns whether TOTP MFA is required for all users in this tenant.
     */
    public boolean isMfaRequiredForAll() {
        return "true".equalsIgnoreCase(getSetting(MFA_REQUIRED_FOR_ALL));
    }

    /**
     * Enables or disables the tenant-wide MFA enforcement policy.
     *
     * <p>
     * When set to {@code true}, every user who logs in will be challenged
     * for a TOTP code regardless of their individual {@code mfa_enabled} flag.
     * Users who have never enrolled will be redirected to the setup page first.
     *
     * @param required {@code true} to enforce MFA for all users; {@code false} to
     *                 disable
     */
    @Transactional
    public void setMfaRequiredForAll(boolean required) {
        String value = required ? "true" : "false";
        int updated = jdbcTemplate.update(
                "UPDATE tenant_settings SET setting_value = ?, updated_at = ? " +
                        "WHERE setting_key = ?",
                value, Instant.now(), MFA_REQUIRED_FOR_ALL);

        if (updated == 0) {
            // Row missing (shouldn't happen after V0.0.7 migration) — insert it
            jdbcTemplate.update(
                    "INSERT INTO tenant_settings (setting_key, setting_value) VALUES (?, ?)",
                    MFA_REQUIRED_FOR_ALL, value);
        }
        
        String tenantId = TenantContextHolder.getTenantId();
        settingsCache.broadcastInvalidation(tenantId);

        log.info("[SETTINGS] mfa_required_for_all set to '{}' for tenant '{}'",
                value, tenantId);
    }

    /**
     * Upserts an arbitrary setting key.
     *
     * @param key   setting key
     * @param value new value
     */
    @Transactional
    public void setSetting(String key, String value) {
        int updated = jdbcTemplate.update(
                "UPDATE tenant_settings SET setting_value = ?, updated_at = ? WHERE setting_key = ?",
                value, Instant.now(), key);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO tenant_settings (setting_key, setting_value) VALUES (?, ?)",
                    key, value);
        }
        
        String tenantId = TenantContextHolder.getTenantId();
        settingsCache.broadcastInvalidation(tenantId);
        
        log.info("[SETTINGS] '{}' = '{}' for tenant '{}'", key, value, tenantId);
    }
}
