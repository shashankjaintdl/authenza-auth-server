package com.authenza.common.enums;

/**
 * Tenant adaptive-auth behavior when a risk evaluator throws.
 */
public enum RiskFailMode {
    /** Log and treat as ALLOW (default for Phase A). */
    OPEN,
    /** Treat evaluator failure as STEP_UP. */
    CLOSED;

    public static RiskFailMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPEN;
        }
        try {
            return RiskFailMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OPEN;
        }
    }

    public boolean isClosed() {
        return this == CLOSED;
    }
}
