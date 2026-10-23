package com.authenza.core.risk.signal;

import com.authenza.common.enums.RiskSignalId;
import com.authenza.core.risk.AdaptiveAuthPolicy;
import com.authenza.core.risk.RiskContext;
import com.authenza.core.risk.RiskSignal;
import com.authenza.core.risk.TrustedDeviceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class NewDeviceSignalEvaluator extends AbstractRiskSignalEvaluator {

    private final TrustedDeviceRepository trustedDeviceRepository;

    public NewDeviceSignalEvaluator(TrustedDeviceRepository trustedDeviceRepository) {
        super(RiskSignalId.NEW_DEVICE);
        this.trustedDeviceRepository = trustedDeviceRepository;
    }

    @Override
    public Optional<RiskSignal> evaluate(RiskContext context, AdaptiveAuthPolicy.Snapshot policy) {
        if (context.userId() == null || context.deviceFingerprint() == null) {
            return Optional.empty();
        }
        if (context.trustedCookieValid()) {
            return Optional.empty();
        }
        if (trustedDeviceRepository.existsActive(context.userId(), context.deviceFingerprint())) {
            return Optional.empty();
        }
        return Optional.of(signal("No matching trusted device"));
    }
}
