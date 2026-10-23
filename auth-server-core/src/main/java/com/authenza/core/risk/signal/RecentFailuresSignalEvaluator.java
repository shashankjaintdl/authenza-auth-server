package com.authenza.core.risk.signal;

import com.authenza.common.enums.RiskSignalId;
import com.authenza.core.risk.AdaptiveAuthPolicy;
import com.authenza.core.risk.RiskContext;
import com.authenza.core.risk.RiskSignal;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RecentFailuresSignalEvaluator extends AbstractRiskSignalEvaluator {

    private static final int WEIGHT_LOW = 10;
    private static final int WEIGHT_MID = 20;
    private static final int WEIGHT_HIGH = 30;

    public RecentFailuresSignalEvaluator() {
        super(RiskSignalId.RECENT_FAILURES);
    }

    @Override
    public Optional<RiskSignal> evaluate(RiskContext context, AdaptiveAuthPolicy.Snapshot policy) {
        int failures = context.recentFailedAttempts();
        if (failures <= 0) {
            return Optional.empty();
        }
        int weight = weightFor(failures);
        return Optional.of(signal(weight, failures + " recent failed attempts"));
    }

    /** Tiered weights — exposed for unit tests / future tuning tables. */
    static int weightFor(int failures) {
        if (failures >= 5) {
            return WEIGHT_HIGH;
        }
        if (failures >= 3) {
            return WEIGHT_MID;
        }
        return WEIGHT_LOW;
    }
}
