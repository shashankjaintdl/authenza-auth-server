package com.authenza.core.risk.signal;

import com.authenza.common.enums.RiskSignalId;
import com.authenza.core.risk.AdaptiveAuthPolicy;
import com.authenza.core.risk.RiskContext;
import com.authenza.core.risk.RiskSignal;

import java.util.Optional;

/**
 * Pluggable risk signal. New Phase B/C signals implement this interface
 * (preferably via {@link AbstractRiskSignalEvaluator}) and register as Spring beans —
 * no changes to the scoring loop required.
 */
public interface RiskSignalEvaluator {

    /**
     * Catalog id for metrics / ordering. Default {@code null} keeps lambdas working in tests.
     */
    default RiskSignalId signalId() {
        return null;
    }

    /**
     * @return signal contribution when the condition fires; empty when not applicable
     */
    Optional<RiskSignal> evaluate(RiskContext context, AdaptiveAuthPolicy.Snapshot policy);
}
