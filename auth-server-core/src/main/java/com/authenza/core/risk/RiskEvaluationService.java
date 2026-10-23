package com.authenza.core.risk;

import com.authenza.common.enums.RiskDecision;
import com.authenza.common.enums.RiskSignalId;
import com.authenza.core.risk.signal.RiskSignalEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates pluggable {@link RiskSignalEvaluator} beans into an explainable decision.
 * Scoring math lives in {@link RiskDecisionEngine}; this class only collects signals + fail mode.
 */
@Service
public class RiskEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RiskEvaluationService.class);

    private final AdaptiveAuthPolicy adaptiveAuthPolicy;
    private final List<RiskSignalEvaluator> evaluators;

    public RiskEvaluationService(AdaptiveAuthPolicy adaptiveAuthPolicy,
                                 List<RiskSignalEvaluator> evaluators) {
        this.adaptiveAuthPolicy = adaptiveAuthPolicy;
        this.evaluators = List.copyOf(evaluators);
    }

    public RiskEvaluationResult evaluate(RiskContext context) {
        AdaptiveAuthPolicy.Snapshot policy = adaptiveAuthPolicy.load(context.tenantId());
        if (!policy.enabled()) {
            return RiskEvaluationResult.allowDisabled();
        }

        try {
            List<RiskSignal> signals = collectSignals(context, policy);
            RiskEvaluationResult result = RiskDecisionEngine.toResult(signals, policy);

            log.info("[Risk] tenant={} userId={} score={} decision={} signals={}",
                    context.tenantId(), context.userId(), result.score(), result.decision(),
                    signals.stream().map(s -> s.id().name()).toList());

            return result;
        } catch (Exception e) {
            log.error("[Risk] Evaluation failed for tenant={} userId={}: {}",
                    context.tenantId(), context.userId(), e.getMessage());
            if (policy.failClosed()) {
                return new RiskEvaluationResult(RiskDecision.STEP_UP, policy.stepUpThreshold(),
                        List.of(RiskSignal.of(RiskSignalId.RECENT_FAILURES, 0, "evaluator_error")),
                        false);
            }
            return new RiskEvaluationResult(RiskDecision.ALLOW, 0, List.of(), false);
        }
    }

    public AdaptiveAuthPolicy.Snapshot policy(String tenantId) {
        return adaptiveAuthPolicy.load(tenantId);
    }

    private List<RiskSignal> collectSignals(RiskContext context, AdaptiveAuthPolicy.Snapshot policy) {
        List<RiskSignal> signals = new ArrayList<>(evaluators.size());
        for (RiskSignalEvaluator evaluator : evaluators) {
            evaluator.evaluate(context, policy).ifPresent(signals::add);
        }
        return signals;
    }
}
