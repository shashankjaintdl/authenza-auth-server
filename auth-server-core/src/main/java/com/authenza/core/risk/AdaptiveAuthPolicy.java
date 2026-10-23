package com.authenza.core.risk;

import com.authenza.adapter.cache.TenantSettingsCache;
import com.authenza.common.constant.AdaptiveAuthSettings;
import com.authenza.common.enums.RiskFailMode;
import org.springframework.stereotype.Component;

/**
 * Loads and parses adaptive auth tenant settings with safe defaults.
 */
@Component
public class AdaptiveAuthPolicy {

    private final TenantSettingsCache settingsCache;

    public AdaptiveAuthPolicy(TenantSettingsCache settingsCache) {
        this.settingsCache = settingsCache;
    }

    public Snapshot load(String tenantId) {
        RiskFailMode failMode = RiskFailMode.from(
                settingsCache.getSetting(tenantId, AdaptiveAuthSettings.FAIL_MODE));
        return new Snapshot(
                parseBoolean(settingsCache.getSetting(tenantId, AdaptiveAuthSettings.ENABLED), false),
                parseInt(settingsCache.getSetting(tenantId, AdaptiveAuthSettings.SCORE_STEP_UP),
                        AdaptiveAuthSettings.DEFAULT_STEP_UP),
                parseInt(settingsCache.getSetting(tenantId, AdaptiveAuthSettings.SCORE_BLOCK),
                        AdaptiveAuthSettings.DEFAULT_BLOCK),
                parseBoolean(settingsCache.getSetting(tenantId, AdaptiveAuthSettings.NOTIFY_NEW_DEVICE), true),
                failMode,
                parseInt(settingsCache.getSetting(tenantId, AdaptiveAuthSettings.TRUST_DEVICE_DAYS),
                        AdaptiveAuthSettings.DEFAULT_TRUST_DAYS),
                parseInt(settingsCache.getSetting(tenantId, AdaptiveAuthSettings.NEW_IP_LOOKBACK_DAYS),
                        AdaptiveAuthSettings.DEFAULT_IP_LOOKBACK_DAYS)
        );
    }

    private static boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw);
    }

    private static int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record Snapshot(
            boolean enabled,
            int stepUpThreshold,
            int blockThreshold,
            boolean notifyNewDevice,
            RiskFailMode failMode,
            int trustDeviceDays,
            int newIpLookbackDays
    ) {
        public boolean failClosed() {
            return failMode != null && failMode.isClosed();
        }
    }
}
