package com.authenza.common.enums;

/**
 * Extensible catalog of adaptive risk signals.
 * Phase A implements {@link #NEW_DEVICE}, {@link #NEW_IP}, {@link #RECENT_FAILURES},
 * and {@link #KNOWN_TRUSTED_DEVICE}. Later phases add geo / reputation signals
 * without changing the evaluation pipeline.
 */
public enum RiskSignalId {
    NEW_DEVICE(40),
    NEW_IP(15),
    RECENT_FAILURES(20),
    KNOWN_TRUSTED_DEVICE(-30),
    NEW_COUNTRY(25),
    IMPOSSIBLE_TRAVEL(50),
    TOR_OR_HOSTING(20),
    PASSKEY_KNOWN_CREDENTIAL(-20);

    private final int defaultWeight;

    RiskSignalId(int defaultWeight) {
        this.defaultWeight = defaultWeight;
    }

    public int defaultWeight() {
        return defaultWeight;
    }
}
