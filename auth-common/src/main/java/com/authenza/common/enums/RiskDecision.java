package com.authenza.common.enums;

/**
 * Outcome of an adaptive login risk evaluation.
 * Threshold mapping lives here so scorers and gates share one rule.
 */
public enum RiskDecision {
    ALLOW,
    STEP_UP,
    BLOCK;

    /**
     * Maps a clamped score to a decision using tenant thresholds.
     * {@code score >= block} wins over step-up.
     */
    public static RiskDecision fromScore(int score, int stepUpThreshold, int blockThreshold) {
        if (score >= blockThreshold) {
            return BLOCK;
        }
        if (score >= stepUpThreshold) {
            return STEP_UP;
        }
        return ALLOW;
    }
}
