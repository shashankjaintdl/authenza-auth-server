# Adaptive Auth — Phase A Diagrams

| Field | Value |
|-------|--------|
| Scope | **Phase A (MVP)** only — `NEW_DEVICE`, `NEW_IP`, `RECENT_FAILURES`; no GeoIP |
| Status | Shipped |
| Parent PRD | [Adaptive / Risk-Based Auth PRD](PRD.md) |
| Feature explanation | [FEATURES.md](FEATURES.md) — detailed behavior of each capability |
| Design | [`planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md`](../../../../planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md) §14 Phase A |
| Architecture | [`docs/ARCHITECTURE.md`](../../../ARCHITECTURE.md) §7 |

> **Read first:** [FEATURES.md](FEATURES.md) explains signals, decisions, trusted devices, step-up rules, email, and settings in product language. This file is the visual companion (architecture / flow / sequence).

---

## 1. Architecture (Phase A)

Components involved after password credentials succeed. Adaptive is **additive**: brute-force, captcha, policy MFA, and OAuth authorize stay unchanged when the flag is off.

```mermaid
flowchart TB
  subgraph Clients
    Browser["Browser / Tenant Portal"]
  end

  subgraph AuthCore["auth-server-core"]
    SC["SecurityConfig<br/>password success handler"]
    Gate["LoginRiskGate"]
    Eval["RiskEvaluationService"]
    Policy["AdaptiveAuthPolicy<br/>tenant_settings"]
    Signals["Signal evaluators<br/>NEW_DEVICE · NEW_IP · RECENT_FAILURES · KNOWN_TRUSTED_DEVICE"]
    Trust["TrustedDeviceService<br/>fingerprint + cookie"]
    Pending["AuthPendingStateStore<br/>TYPE_MFA"]
    BF["BruteForceProtectionService"]
    NotifyPub["RiskNotificationPublisher"]
    MFA["MFA verify / policy MFA path"]
  end

  subgraph IAM["auth-iam-service"]
    SettingsAPI["GET/PUT adaptive-auth settings"]
    DevicesAPI["Trusted devices list / revoke"]
  end

  subgraph Data
    TenantDB[(Tenant DB<br/>user_trusted_device<br/>user_session<br/>tenant_settings)]
    Redis[(Redis<br/>pending MFA · BF counters · pub/sub)]
  end

  subgraph Notify["auth-notification-service"]
    Listener["SecurityAlertEvent listener"]
    Mail["security-alert.html email"]
  end

  Browser --> SC
  SC --> Gate
  Gate --> Eval
  Gate --> Trust
  Gate --> Pending
  Gate --> NotifyPub
  Eval --> Policy
  Eval --> Signals
  Signals --> Trust
  Signals --> BF
  Trust --> TenantDB
  Policy --> TenantDB
  BF --> Redis
  Pending --> Redis
  NotifyPub --> Redis
  Redis --> Listener --> Mail
  Gate --> MFA
  Browser --> SettingsAPI
  Browser --> DevicesAPI
  SettingsAPI --> TenantDB
  DevicesAPI --> TenantDB
```

### Module responsibilities (Phase A)

| Component | Role |
|-----------|------|
| `LoginRiskGate` | Post-credential gate: evaluate → BLOCK / STEP_UP / proceed; post-login trust + notify |
| `RiskEvaluationService` | Sum signal weights → clamp 0..100 → ALLOW / STEP_UP / BLOCK |
| Signal evaluators | Pluggable Phase A signals only (no Geo) |
| `TrustedDeviceService` | HMAC fingerprint, `az_trusted_device` cookie, DB trust row |
| `AuthPendingStateStore` | Reuses `TYPE_MFA` for adaptive step-up (no separate token type) |
| `RiskNotificationPublisher` | Redis → notification service for new-device / security alert |
| IAM APIs | Admin toggle + user device revoke |

---

## 2. Decision flow (Phase A)

High-level login path after **password OK**. Fail-open: unexpected evaluator/gate errors → continue normal login.

```mermaid
flowchart TD
  Start([Password / credentials OK]) --> Enabled{adaptive_auth_enabled?}
  Enabled -->|No| PolicyMFA[Existing MFA policy only]
  Enabled -->|Yes| BuildCtx[Build RiskContext<br/>IP · UA · fingerprint · cookie · failures]
  BuildCtx --> Score[Collect signals · sum weights · clamp]
  Score --> Dec{Decision}

  Dec -->|score ≥ block| Block[BLOCK<br/>clear SecurityContext<br/>redirect login?error]
  Dec -->|score ≥ step_up| StepUp{STEP_UP handling}
  Dec -->|else| Allow[ALLOW]

  StepUp -->|policy MFA already required| Collapse[Collapse into policy MFA<br/>proceed + notify flag]
  StepUp -->|TOTP fully enrolled| Challenge[Save TYPE_MFA pending<br/>redirect /mfa-verify]
  StepUp -->|not enrolled| SkipSetup[Do NOT force MFA setup<br/>ALLOW + notify if configured]

  Allow --> Cont[Continue login / OAuth]
  Collapse --> Cont
  SkipSetup --> Cont
  Challenge --> MfaOk{MFA verify OK?}
  MfaOk -->|Yes| Cont
  MfaOk -->|No| Fail[Stay on challenge / fail]

  Cont --> Established[onLoginFullyEstablished]
  Established --> Remember[Remember trusted device<br/>cookie + DB]
  Established --> Email{notifyNewDevice?}
  Email -->|Yes| Pub[Publish security alert email]
  Email -->|No| Done([Session established])
  Remember --> Done
  Pub --> Done
  Block --> Stop([Login halted])
```

### Phase A signal weights (design defaults)

| Signal | Effect | Typical weight |
|--------|--------|----------------|
| `NEW_DEVICE` | No trusted match / no prior fingerprint | +40 |
| `NEW_IP` | IP not seen in lookback window | +15 |
| `RECENT_FAILURES` | BF failure density | +10..30 |
| `KNOWN_TRUSTED_DEVICE` | Valid trust cookie + fingerprint | −30 |

Thresholds (tenant settings): `adaptive_score_step_up` default **40**, `adaptive_score_block` default **80**.

---

## 3. Sequence — password login with STEP_UP (enrolled MFA)

Happy path when adaptive is on, score requires step-up, and the user has TOTP enrolled.

```mermaid
sequenceDiagram
  actor User
  participant Browser
  participant SC as SecurityConfig
  participant Gate as LoginRiskGate
  participant Eval as RiskEvaluationService
  participant Sig as SignalEvaluators
  participant Trust as TrustedDeviceService
  participant BF as BruteForceProtectionService
  participant Store as AuthPendingStateStore
  participant MFA as MfaChallenge / Filter
  participant Redis
  participant DB as Tenant DB
  participant Pub as RiskNotificationPublisher
  participant Ntfy as notification-service

  User->>Browser: Submit username / password
  Browser->>SC: POST /{tenant}/login
  SC->>SC: Authenticate password OK

  SC->>Gate: evaluateAfterCredentials(tenant, user, request)
  Gate->>Trust: computeFingerprint + isTrustedCookieValid
  Gate->>BF: getFailedAttemptCount
  Gate->>Eval: evaluate(RiskContext)
  Eval->>Sig: NEW_DEVICE / NEW_IP / RECENT_FAILURES / KNOWN_TRUSTED
  Sig->>DB: session / trusted_device lookups
  Sig-->>Eval: RiskSignal[]
  Eval-->>Gate: STEP_UP + score + newDevice?

  alt MFA already required by policy
    Gate-->>SC: proceed(notify)
    Note over SC: Existing MFA path handles challenge
  else Fully enrolled — adaptive step-up
    Gate->>Store: savePendingState(TYPE_MFA, …)
    Store->>Redis: pending MFA token
    Gate-->>Browser: 302 /{tenant}/mfa-verify?mfaToken=
    User->>Browser: Enter TOTP
    Browser->>MFA: Verify code
    MFA->>Store: getAndClearPendingState
    MFA->>SC: Complete authentication
  else Not enrolled
    Gate-->>SC: proceed(notify) — no setup forced
  end

  SC->>Gate: onLoginFullyEstablished(…)
  Gate->>Trust: rememberDevice (cookie + DB)
  Trust->>DB: upsert user_trusted_device
  opt notifyNewDevice
    Gate->>Pub: publishNewDeviceAlert
    Pub->>Redis: SecurityAlertEvent
    Redis->>Ntfy: deliver security-alert email
    Ntfy-->>User: Email
  end
  SC-->>Browser: Continue OAuth / home
```

---

## 4. Sequence — ALLOW on known trusted device

```mermaid
sequenceDiagram
  actor User
  participant Browser
  participant SC as SecurityConfig
  participant Gate as LoginRiskGate
  participant Eval as RiskEvaluationService
  participant Trust as TrustedDeviceService

  User->>Browser: Login (az_trusted_device present)
  Browser->>SC: Password OK
  SC->>Gate: evaluateAfterCredentials
  Gate->>Trust: cookie valid for fingerprint
  Gate->>Eval: evaluate (KNOWN_TRUSTED_DEVICE −30)
  Eval-->>Gate: ALLOW (score below step_up)
  Gate-->>SC: proceed(false)
  Note over SC: Policy MFA only if tenant/user requires it
  SC->>Gate: onLoginFullyEstablished
  Gate->>Trust: refresh last_seen / cookie TTL
  SC-->>Browser: Session / authorize continue
```

---

## 5. Sequence — BLOCK

```mermaid
sequenceDiagram
  actor User
  participant Browser
  participant SC as SecurityConfig
  participant Gate as LoginRiskGate
  participant Eval as RiskEvaluationService

  User->>Browser: Login
  Browser->>SC: Password OK
  SC->>Gate: evaluateAfterCredentials
  Gate->>Eval: evaluate
  Eval-->>Gate: BLOCK (score ≥ block threshold)
  Gate->>Gate: clear SecurityContext
  Gate-->>Browser: 302 /{tenant}/login?error
  Note over Browser: Generic error — no signal detail
```

---

## 6. Sequence — revoke trusted device (portal)

Revoke removes **trust**, not the active session. Immediate logout remains session revoke.

```mermaid
sequenceDiagram
  actor User
  participant Portal as Tenant Portal
  participant IAM as TrustedDeviceController
  participant DB as Tenant DB
  participant Core as TrustedDeviceService next login

  User->>Portal: Security → Devices → Revoke
  Portal->>IAM: DELETE /trusted-devices/{id}
  IAM->>DB: revoked = true
  IAM-->>Portal: 204 / OK

  Note over User,Core: Later login on same browser
  User->>Core: Password OK + old cookie
  Core->>DB: trust row revoked / missing
  Core-->>Core: NEW_DEVICE fires · cookie ignored
```

---

## 7. Out of Phase A (not in these diagrams)

- GeoIP: `NEW_COUNTRY`, `IMPOSSIBLE_TRAVEL`
- `login_risk_event` audit table / admin viewer
- Email-OTP step-up for unenrolled users
- Social / passkey evaluation hooks
- Fail-closed as default (tenant can set later; Phase A gate still fail-open on unexpected errors)
- Continuous mid-session re-score (CAE)

See design doc §14 Phases B / C / D+.
