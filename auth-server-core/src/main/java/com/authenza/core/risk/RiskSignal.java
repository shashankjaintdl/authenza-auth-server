package com.authenza.core.risk;

import com.authenza.common.enums.RiskSignalId;

/**
 * One evaluated adaptive signal contributing to the risk score.
 */
public record RiskSignal(RiskSignalId id, int weight, String detail) {

    public static RiskSignal of(RiskSignalId id, String detail) {
        return new RiskSignal(id, id.defaultWeight(), detail);
    }

    public static RiskSignal of(RiskSignalId id, int weight, String detail) {
        return new RiskSignal(id, weight, detail);
    }
}
