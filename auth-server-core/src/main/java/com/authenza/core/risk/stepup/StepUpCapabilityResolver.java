package com.authenza.core.risk.stepup;

/**
 * Resolves which step-up channel to use for a username after adaptive STEP_UP.
 * Isolated so Phase B+ can add email-OTP / passkey without changing {@code LoginRiskGate}.
 */
@FunctionalInterface
public interface StepUpCapabilityResolver {

    StepUpAction resolve(String username);
}
