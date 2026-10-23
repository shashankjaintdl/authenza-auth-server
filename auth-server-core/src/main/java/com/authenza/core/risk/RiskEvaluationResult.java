package com.authenza.core.risk;

import com.authenza.common.enums.RiskDecision;

import java.util.List;

/**
 * Explainable outcome of {@link RiskEvaluationService#evaluate}.
 */
public record RiskEvaluationResult(
        RiskDecision decision,
        int score,
        List<RiskSignal> signals,
        boolean newDeviceDetected
) {
    public static RiskEvaluationResult allowDisabled() {
        return new RiskEvaluationResult(RiskDecision.ALLOW, 0, List.of(), false);
    }

    public boolean requiresStepUp() {
        return decision == RiskDecision.STEP_UP;
    }

    public boolean isBlocked() {
        return decision == RiskDecision.BLOCK;
    }
}
