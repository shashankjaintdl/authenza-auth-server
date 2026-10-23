# Chapter 14 — Where to change what

| | |
|--|--|
| Part | [V — Going further](README.md) |
| Previous | [← Part opener](README.md) |
| Next | [Docs reading path →](15-docs-reading-path.md) |

---

Cheat sheet: feature area → module → starting classes. Use with the [Glossary](../00-glossary.md) so you change the right **term**.

| If you need to change… | Start here | Related terms |
|------------------------|------------|---------------|
| Password success order / redirects | `SecurityConfig` | Form login, GateOutcome |
| MFA verify / enroll pages | `Mfa*Controller`, `MfaAuthenticationFilter` | Policy MFA, pending state |
| Brute-force / lockout | `BruteForceProtectionService` | RECENT_FAILURES |
| Adaptive scoring | `RiskEvaluationService`, `signal/*` | RiskSignal, score, decision |
| Adaptive enforcement | `LoginRiskGate`, `stepup/*` | STEP_UP, StepUpAction |
| Trust cookie / fingerprint | `TrustedDevice*`, `DeviceFingerprintService`, `HmacSigner` | Trusted device |
| Adaptive settings API | IAM `TenantSettingsController` | AdaptiveAuthSettings |
| Device revoke API | IAM `TrustedDeviceController` | user_trusted_device |
| Security email | `RiskNotificationPublisher`, notification listener | SecurityAlertEvent |
| Tenant provision | `auth-master-service` | Control plane, BYOD |
| Schema / seeds | `auth-tenant-schema` Liquibase | tenant_settings, trust table |
| Shared enums / Redis names | `auth-common` | RiskDecision, RedisChannels |

## Safety rules (don’t break existing flows)

1. Adaptive **off** must leave password → MFA → OIDC unchanged.  
2. Never force MFA **setup** from Adaptive alone (Phase A).  
3. Prefer fail-open at the gate for unexpected errors.  
4. Generic login errors — do not expose signal ids to the browser.  
5. Revoke trust ≠ revoke session.

## Lab

Pick a small task (e.g. “log signal ids at DEBUG only”) and list the **one** class you would touch before coding.

---

← [Part V](README.md) · [Chapter 15 — Docs reading path →](15-docs-reading-path.md)
