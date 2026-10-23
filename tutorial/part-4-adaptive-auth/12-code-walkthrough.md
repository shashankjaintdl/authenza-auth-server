# Chapter 12 — Code walkthrough

| | |
|--|--|
| Part | [IV — Adaptive Auth](README.md) |
| Previous | [← Concepts](11-adaptive-concepts.md) |
| Next | [Portal & ops →](13-portal-and-ops.md) |

---

## Call chain (password success)

```text
SecurityConfig (password OK)
  → LoginRiskGate.evaluateAfterCredentials
       → RiskContextFactory.fromPasswordLogin
       → RiskEvaluationService.evaluate
            → AdaptiveAuthPolicy.load (tenant_settings)
            → each RiskSignalEvaluator
            → RiskDecisionEngine.toResult
       → switch decision:
            BLOCK   → redirect login?error, GateOutcome.stop()
            STEP_UP → StepUpCapabilityResolver → MFA verify or proceed
            ALLOW   → GateOutcome.proceed(notify?)
  → (if not halted) policy MFA / session as before
  → onLoginFullyEstablished → rememberDevice + optional SecurityAlertEvent
```

## Package map

| Type | Package / class | Term it implements |
|------|-----------------|-------------------|
| Gate | `LoginRiskGate` | Post-credential enforcement |
| Context | `RiskContext` / `RiskContextFactory` | Evaluation inputs |
| Score | `RiskEvaluationService`, `RiskDecisionEngine` | Score + decision |
| SPI | `RiskSignalEvaluator`, `AbstractRiskSignalEvaluator` | Pluggable signals |
| Step-up | `StepUpCapabilityResolver`, `StepUpAction` | How to challenge |
| Trust | `TrustedDeviceService`, cookie + repository | Fingerprint / cookie / DB |
| Crypto helper | `HmacSigner` | Shared HMAC |
| Notify | `RiskNotificationPublisher` | `SecurityAlertKind` |
| Shared enums | `RiskDecision`, `RiskFailMode`, `RiskSignalId` | Catalogs in `auth-common` |

## Extensibility (no generics required for Phase B)

**Example — add `NEW_COUNTRY` later:**

1. Implement `AbstractRiskSignalEvaluator` with `RiskSignalId.NEW_COUNTRY`.  
2. Register `@Component`.  
3. Scoring loop unchanged.

**Example — email OTP step-up later:**

1. Add `StepUpAction.EMAIL_OTP_CHALLENGE`.  
2. Teach `StepUpCapabilityResolver` / handler.  
3. Keep `LoginRiskGate` switch exhaustive.

Generics (`SignedTokenCodec<T>`, etc.) are documented as **later** in the design doc — not needed to extend signals.

## Lab (read-only)

Open in IDE:

1. `LoginRiskGate`  
2. One evaluator, e.g. `NewDeviceSignalEvaluator`  
3. `SecurityConfig` call site around `evaluateAfterCredentials`  

Trace one ALLOW and one STEP_UP path mentally using Chapter 11 examples.

---

← [Ch 11](11-adaptive-concepts.md) · [Chapter 13 — Portal & ops →](13-portal-and-ops.md)
