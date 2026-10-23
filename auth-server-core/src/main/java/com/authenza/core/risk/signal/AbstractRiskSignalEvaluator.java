package com.authenza.core.risk.signal;

import com.authenza.common.enums.RiskSignalId;
import com.authenza.core.risk.RiskSignal;

/**
 * Base for Spring-registered signal beans. Phase B/C evaluators extend this
 * to inherit {@link #signalId()} and {@link RiskSignal} factories.
 * Lambdas / tests may still implement {@link RiskSignalEvaluator} directly.
 */
public abstract class AbstractRiskSignalEvaluator implements RiskSignalEvaluator {

    private final RiskSignalId signalId;

    protected AbstractRiskSignalEvaluator(RiskSignalId signalId) {
        this.signalId = signalId;
    }

    @Override
    public final RiskSignalId signalId() {
        return signalId;
    }

    protected final RiskSignal signal(String detail) {
        return RiskSignal.of(signalId, detail);
    }

    protected final RiskSignal signal(int weight, String detail) {
        return RiskSignal.of(signalId, weight, detail);
    }
}
