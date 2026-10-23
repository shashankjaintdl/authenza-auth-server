package com.authenza.common.enums;

/**
 * Discriminator for {@link com.authenza.common.events.SecurityAlertEvent#alertKind()}.
 * Extensible for Phase B+ alert types without changing the Redis payload shape.
 */
public enum SecurityAlertKind {
    NEW_DEVICE,
    HIGH_RISK_BLOCKED,
    NEW_LOCATION;

    public String wireValue() {
        return name();
    }
}
