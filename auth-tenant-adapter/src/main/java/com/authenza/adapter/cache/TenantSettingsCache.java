package com.authenza.adapter.cache;

import com.authenza.common.RedisChannels;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * An ultra-fast, in-memory cache for Tenant Settings using Caffeine.
 * Replaces repetitive, synchronous DB hits to `tenant_settings` with 
 * a localized map that refreshes every 5 minutes (or precisely on manual invalidation).
 */
@Service
public class TenantSettingsCache {

    private static final Logger log = LoggerFactory.getLogger(TenantSettingsCache.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    // Cache key: tenantId, Cache value: Map of settings (key -> value)
    private final Cache<String, Map<String, String>> cache;

    public TenantSettingsCache(DataSource dataSource, StringRedisTemplate redisTemplate) {
        // The dataSource is the tenant-aware RoutingDataSource
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.redisTemplate = redisTemplate;
        
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build();
    }

    /**
     * Gets a single setting for a tenant, utilizing the cache.
     * 
     * @param tenantId The active tenant ID
     * @param key The setting key to look up
     * @return The setting value, or null if it doesn't exist
     */
    public String getSetting(String tenantId, String key) {
        Map<String, String> settings = getAllSettings(tenantId);
        return settings.get(key);
    }

    /**
     * Gets all settings for a tenant, loading them from the DB if they aren't cached.
     * 
     * @param tenantId The active tenant ID
     * @return A map containing all settings for this tenant
     */
    public Map<String, String> getAllSettings(String tenantId) {
        return cache.get(tenantId, this::loadFromDatabase);
    }

    /**
     * Wipes the local node's cache and broadcasts the invalidation on Redis 
     * to all other nodes.
     * Called immediately when an admin updates a setting.
     * 
     * @param tenantId The active tenant ID
     */
    public void broadcastInvalidation(String tenantId) {
        invalidateLocal(tenantId);
        redisTemplate.convertAndSend(RedisChannels.TENANT_SETTINGS_INVALIDATED, tenantId);
        log.info("[SettingsCache] Broadcasted invalidation via Redis for tenant '{}'", tenantId);
    }

    /**
     * Invalidates only the local cache. 
     * Called by the Redis subscriber when hearing a broadcast.
     * 
     * @param tenantId The active tenant ID
     */
    public void invalidateLocal(String tenantId) {
        cache.invalidate(tenantId);
        log.info("[SettingsCache] Cleared local cache for tenant '{}'", tenantId);
    }

    private Map<String, String> loadFromDatabase(String tenantId) {
        // Note: The underlying RoutingDataSource relies on TenantContextHolder being active.
        Map<String, String> settings = new HashMap<>();
        try {
            jdbcTemplate.query(
                "SELECT setting_key, setting_value FROM tenant_settings",
                rs -> {
                    settings.put(rs.getString("setting_key"), rs.getString("setting_value"));
                }
            );
            log.debug("[SettingsCache] Loaded {} settings into local cache for tenant '{}'", settings.size(), tenantId);
        } catch (Exception e) {
            log.warn("[SettingsCache] Could not load settings for tenant '{}' from database (Exception: {})", tenantId, e.getMessage());
        }
        return settings;
    }
}
