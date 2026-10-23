package com.authenza.core.risk.stepup;

/**
 * How {@link com.authenza.core.risk.LoginRiskGate} should enforce a STEP_UP decision.
 * New challenge channels (email OTP, passkey) add enum values + resolver branches.
 */
public enum StepUpAction {
    /** Tenant/user MFA policy already requires MFA — do not open a second challenge. */
    COLLAPSE_INTO_POLICY_MFA,
    /** User has a usable TOTP authenticator — redirect to existing MFA verify. */
    CHALLENGE_MFA_VERIFY,
    /**
     * No enrolled authenticator — Phase A allows login (optional security email).
     * Later: EMAIL_OTP_CHALLENGE.
     */
    ALLOW_WITHOUT_ENROLLMENT
}
