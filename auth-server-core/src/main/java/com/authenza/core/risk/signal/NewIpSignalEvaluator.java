package com.authenza.core.risk.signal;

import com.authenza.common.enums.RiskSignalId;
import com.authenza.core.risk.AdaptiveAuthPolicy;
import com.authenza.core.risk.RiskContext;
import com.authenza.core.risk.RiskSignal;
import com.authenza.core.risk.UserSessionRiskQuery;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class NewIpSignalEvaluator extends AbstractRiskSignalEvaluator {

    private final UserSessionRiskQuery sessionRiskQuery;

    public NewIpSignalEvaluator(UserSessionRiskQuery sessionRiskQuery) {
        super(RiskSignalId.NEW_IP);
        this.sessionRiskQuery = sessionRiskQuery;
    }

    @Override
    public Optional<RiskSignal> evaluate(RiskContext context, AdaptiveAuthPolicy.Snapshot policy) {
        if (context.userId() == null || context.ipAddress() == null || context.ipAddress().isBlank()) {
            return Optional.empty();
        }
        if (sessionRiskQuery.hasSeenIpWithinDays(
                context.userId(), context.ipAddress(), policy.newIpLookbackDays())) {
            return Optional.empty();
        }
        return Optional.of(signal("IP not seen in last " + policy.newIpLookbackDays() + " days"));
    }
}
