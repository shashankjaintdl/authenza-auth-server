package com.authenza.core.risk.signal;

import com.authenza.common.enums.RiskSignalId;
import com.authenza.core.risk.AdaptiveAuthPolicy;
import com.authenza.core.risk.RiskContext;
import com.authenza.core.risk.RiskSignal;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class KnownTrustedDeviceSignalEvaluator extends AbstractRiskSignalEvaluator {

    public KnownTrustedDeviceSignalEvaluator() {
        super(RiskSignalId.KNOWN_TRUSTED_DEVICE);
    }

    @Override
    public Optional<RiskSignal> evaluate(RiskContext context, AdaptiveAuthPolicy.Snapshot policy) {
        if (!context.trustedCookieValid()) {
            return Optional.empty();
        }
        return Optional.of(signal("Valid trusted-device cookie"));
    }
}
