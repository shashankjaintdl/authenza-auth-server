package com.authenza.common.constant;

import com.authenza.common.enums.RiskFailMode;

/**
 * Shared {@code tenant_settings} keys for Adaptive / Risk-Based Authentication.
 * Used by auth-server-core (read at login) and auth-iam-service (admin APIs).
 */
public final class AdaptiveAuthSettings {

    private AdaptiveAuthSettings() {
    }

    public static final String ENABLED = "adaptive_auth_enabled";
    public static final String SCORE_STEP_UP = "adaptive_score_step_up";
    public static final String SCORE_BLOCK = "adaptive_score_block";
    public static final String NOTIFY_NEW_DEVICE = "adaptive_notify_new_device";
    public static final String FAIL_MODE = "adaptive_fail_mode";
    public static final String TRUST_DEVICE_DAYS = "adaptive_trust_device_days";
    public static final String NEW_IP_LOOKBACK_DAYS = "adaptive_new_ip_lookback_days";

    /** @see RiskFailMode#OPEN */
    public static final String FAIL_MODE_OPEN = RiskFailMode.OPEN.name();
    /** @see RiskFailMode#CLOSED */
    public static final String FAIL_MODE_CLOSED = RiskFailMode.CLOSED.name();

    public static final int DEFAULT_STEP_UP = 40;
    public static final int DEFAULT_BLOCK = 80;
    public static final int DEFAULT_TRUST_DAYS = 90;
    public static final int DEFAULT_IP_LOOKBACK_DAYS = 30;
}
