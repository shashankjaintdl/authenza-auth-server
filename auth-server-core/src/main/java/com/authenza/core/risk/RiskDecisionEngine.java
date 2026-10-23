package com.authenza.core.risk;

import com.authenza.common.enums.RiskDecision;
import com.authenza.common.enums.RiskSignalId;

import java.util.List;

/**
 * Pure scoring / decision helpers shared by {@link RiskEvaluationService} and tests.
 * Keeps threshold math out of orchestration code so Phase B+ aggregators can swap later.
 */
public final class RiskDecisionEngine {

    public static final int SCORE_MIN = 0;
    public static final int SCORE_MAX = 100;

    private RiskDecisionEngine() {
    }

    public static int clampScore(int raw) {
        return Math.max(SCORE_MIN, Math.min(SCORE_MAX, raw));
    }

    public static int sumWeights(List<RiskSignal> signals) {
        int sum = 0;
        for (RiskSignal signal : signals) {
            sum += signal.weight();
        }
        return sum;
    }

    public static RiskDecision decide(int score, AdaptiveAuthPolicy.Snapshot policy) {
        return RiskDecision.fromScore(score, policy.stepUpThreshold(), policy.blockThreshold());
    }

    public static RiskEvaluationResult toResult(List<RiskSignal> signals, AdaptiveAuthPolicy.Snapshot policy) {
        int score = clampScore(sumWeights(signals));
        RiskDecision decision = decide(score, policy);
        boolean newDevice = signals.stream().anyMatch(s -> s.id() == RiskSignalId.NEW_DEVICE);
        return new RiskEvaluationResult(decision, score, List.copyOf(signals), newDevice);
    }
}
