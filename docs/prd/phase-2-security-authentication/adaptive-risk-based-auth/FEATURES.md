# Adaptive Auth — Implemented Terms (Technical Reference)

| Field | Value |
|-------|--------|
| Scope | **Phase A** as shipped in code |
| Parent PRD | [PRD.md](PRD.md) |
| Diagrams | [PHASE-A-DIAGRAMS.md](PHASE-A-DIAGRAMS.md) |
| Design | [`planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md`](../../../../planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md) |
| Package | `com.authenza.core.risk` (+ `*.signal`) · shared enums/constants in `auth-common` |

This document defines **each implemented term**: type, ownership, algorithm, storage, and callers. Prefer this over the design doc when code and design disagree — code is SoT for Phase A.

---

## Index of terms

| Term | Kind | Primary type / artifact |
|------|------|-------------------------|
| [Adaptive Auth](#1-adaptive-auth) | Feature | Feature flag + gate pipeline |
| [AdaptiveAuthSettings](#2-adaptiveauthsettings) | Constants | `tenant_settings` keys |
| [AdaptiveAuthPolicy / Snapshot](#3-adaptiveauthpolicy--snapshot) | Runtime config | Parsed settings |
| [RiskContext](#4-riskcontext) | Input DTO | Evaluation inputs |
| [RiskSignal / RiskSignalId](#5-risksignal--risksignalid) | Signal model | Weighted contribution |
| [RiskSignalEvaluator](#6-risksignalevaluator) | SPI | Pluggable evaluators |
| [NEW_DEVICE](#61-new_device) | Signal | +40 |
| [NEW_IP](#62-new_ip) | Signal | +15 |
| [RECENT_FAILURES](#63-recent_failures) | Signal | +10 / +20 / +30 |
| [KNOWN_TRUSTED_DEVICE](#64-known_trusted_device) | Signal | −30 |
| [Risk score](#7-risk-score) | Derived | Clamped sum `0..100` |
| [RiskDecision](#8-riskdecision) | Enum | `ALLOW` / `STEP_UP` / `BLOCK` |
| [RiskEvaluationResult](#9-riskevaluationresult) | Output DTO | Decision + score + signals |
| [RiskEvaluationService](#10-riskevaluationservice) | Service | Orchestrates score/decision |
| [LoginRiskGate / GateOutcome](#11-loginriskgate--gateoutcome) | Gate | Post-credential enforcement |
| [Step-up](#12-step-up) | Behavior | MFA pending reuse |
| [Device fingerprint](#13-device-fingerprint) | Crypto | HMAC-SHA256 hex |
| [az_trusted_device](#14-az_trusted_device-cookie) | Cookie | Signed trust token |
| [user_trusted_device](#15-user_trusted_device) | Table | Tenant DB trust rows |
| [TrustedDeviceService](#16-trusteddeviceservice) | Facade | Fingerprint + cookie + DB |
| [Fail mode OPEN / CLOSED](#17-fail-mode-open--closed) | Setting | Error behavior |
| [SecurityAlertEvent](#18-securityalertevent) | Redis event | New-device email |
| [ClientRequestUtils](#19-clientrequestutils) | Util | IP / UA / Sec-CH-UA |
| [APIs](#20-apis-phase-a) | HTTP | Settings + devices |

---

## 1. Adaptive Auth

**Definition:** Optional, tenant-scoped login risk evaluation that runs **after** password authentication succeeds and **before** the login is fully established (session / OAuth continue).

**Master gate:** `tenant_settings.adaptive_auth_enabled` (`false` by default). When disabled, `RiskEvaluationService` returns `ALLOW` with score `0` and `LoginRiskGate` is a no-op for risk (returns `proceed(false)`).

**Insertion point (Phase A):** password success path in `SecurityConfig` → `LoginRiskGate.evaluateAfterCredentials(...)`. Passkey/social hooks exist on `RiskContext.AuthMethod` but are not wired as primary Phase A paths.

**Additive:** Does not replace brute-force lockout, captcha, policy MFA, session recording, or session revoke.

---

## 2. AdaptiveAuthSettings

**Type:** `com.authenza.common.constant.AdaptiveAuthSettings`  
**Storage:** rows in tenant DB `tenant_settings` (`setting_key` / `setting_value`), seeded in Liquibase V0.0.12 / init DDL.

| Constant | Key string | Default | Parsed as |
|----------|------------|---------|-----------|
| `ENABLED` | `adaptive_auth_enabled` | `false` | boolean |
| `SCORE_STEP_UP` | `adaptive_score_step_up` | `40` | int |
| `SCORE_BLOCK` | `adaptive_score_block` | `80` | int |
| `NOTIFY_NEW_DEVICE` | `adaptive_notify_new_device` | `true` | boolean |
| `FAIL_MODE` | `adaptive_fail_mode` | `OPEN` | `OPEN` \| `CLOSED` |
| `TRUST_DEVICE_DAYS` | `adaptive_trust_device_days` | `90` | int |
| `NEW_IP_LOOKBACK_DAYS` | `adaptive_new_ip_lookback_days` | `30` | int |

Also defines `FAIL_MODE_OPEN`, `FAIL_MODE_CLOSED`, and numeric `DEFAULT_*` constants used by IAM validation and policy parsing.

**Readers:** `AdaptiveAuthPolicy` via `TenantSettingsCache`.  
**Writers:** IAM `PUT /api/v1/settings/adaptive-auth` (admin).

---

## 3. AdaptiveAuthPolicy / Snapshot

**Type:** `com.authenza.core.risk.AdaptiveAuthPolicy`  
**Role:** Load settings for a `tenantId` once per evaluation and expose an immutable `Snapshot`.

```text
Snapshot {
  enabled: boolean
  stepUpThreshold: int      // adaptive_score_step_up
  blockThreshold: int       // adaptive_score_block
  notifyNewDevice: boolean
  failClosed: boolean       // true iff FAIL_MODE == CLOSED
  trustDeviceDays: int
  newIpLookbackDays: int
}
```

Invalid / blank numeric strings fall back to defaults. Blank boolean → default. Unknown fail mode → not closed (`OPEN`).

---

## 4. RiskContext

**Type:** `com.authenza.core.risk.RiskContext` (record)  
**Role:** Immutable inputs to `RiskEvaluationService.evaluate`.

| Field | Type | Source (Phase A) |
|-------|------|------------------|
| `tenantId` | `String` | Auth tenant resolution |
| `userId` | `Long` | Authenticated user id |
| `username` | `String` | Login username |
| `ipAddress` | `String` | `ClientRequestUtils.resolveIpAddress` |
| `userAgent` | `String` | `ClientRequestUtils.resolveUserAgent` |
| `secChUa` | `String` | `ClientRequestUtils.resolveSecChUa` |
| `deviceFingerprint` | `String` | `TrustedDeviceService.computeFingerprint` |
| `trustedCookieValid` | `boolean` | Cookie HMAC OK **and** active DB row |
| `recentFailedAttempts` | `int` | `BruteForceProtectionService.getFailedAttemptCount` |
| `authMethod` | `AuthMethod` | Phase A: `PASSWORD` |

`AuthMethod`: `PASSWORD` | `PASSKEY` | `SOCIAL` (enum reserved for later wiring).

Built only inside `LoginRiskGate.buildContext`.

---

## 5. RiskSignal / RiskSignalId

### RiskSignal

**Type:** `com.authenza.core.risk.RiskSignal` (record: `id`, `weight`, `detail`)

Factories:

- `RiskSignal.of(id, detail)` → weight = `id.defaultWeight()`
- `RiskSignal.of(id, weight, detail)` → explicit weight (`RECENT_FAILURES`)

`detail` is a short human-readable reason for logs (not shown to end users).

### RiskSignalId

**Type:** `com.authenza.common.enums.RiskSignalId`  
Each enum constant carries a **defaultWeight**.

| Id | defaultWeight | Phase A evaluator |
|----|---------------|-------------------|
| `NEW_DEVICE` | `+40` | `NewDeviceSignalEvaluator` |
| `NEW_IP` | `+15` | `NewIpSignalEvaluator` |
| `RECENT_FAILURES` | `+20` (catalog default; runtime uses tiered weights) | `RecentFailuresSignalEvaluator` |
| `KNOWN_TRUSTED_DEVICE` | `−30` | `KnownTrustedDeviceSignalEvaluator` |
| `NEW_COUNTRY` | `+25` | **Not implemented** (Phase B) |
| `IMPOSSIBLE_TRAVEL` | `+50` | **Not implemented** (Phase B) |
| `TOR_OR_HOSTING` | `+20` | **Not implemented** (Phase C) |
| `PASSKEY_KNOWN_CREDENTIAL` | `−20` | **Not implemented** (Phase C) |

---

## 6. RiskSignalEvaluator

**Type:** `com.authenza.core.risk.signal.RiskSignalEvaluator`  
**Contract:** `Optional<RiskSignal> evaluate(RiskContext context, AdaptiveAuthPolicy.Snapshot policy)`

Spring injects `List<RiskSignalEvaluator>` into `RiskEvaluationService`. Phase A registers exactly the four components below. Empty `Optional` = signal did not fire (no contribution).

### 6.1 `NEW_DEVICE`

**Class:** `NewDeviceSignalEvaluator`  
**Weight:** `+40` (`RiskSignalId.NEW_DEVICE.defaultWeight()`)

**Fires when all are true:**

1. `userId` and `deviceFingerprint` non-null  
2. `trustedCookieValid == false`  
3. `TrustedDeviceRepository.existsActive(userId, fingerprint) == false`

**Does not fire** if cookie is already considered valid (avoids double-counting with `KNOWN_TRUSTED_DEVICE`) or an active trust row exists for that fingerprint.

**Detail example:** `"No matching trusted device"`.

### 6.2 `NEW_IP`

**Class:** `NewIpSignalEvaluator`  
**Weight:** `+15`

**Fires when:**

1. `userId` and non-blank `ipAddress` present  
2. `UserSessionRiskQuery.hasSeenIpWithinDays(userId, ip, policy.newIpLookbackDays()) == false`

**Query:** `COUNT` on `user_session` where `user_id`, `ip_address`, `created_at >= now - lookbackDays`. IP truncated via `ClientRequestUtils.truncate(..., 100)` for column safety.

**Detail example:** `"IP not seen in last 30 days"`.

### 6.3 `RECENT_FAILURES`

**Class:** `RecentFailuresSignalEvaluator`  
**Weight (runtime tiers, not enum default):**

| `context.recentFailedAttempts()` | Weight |
|----------------------------------|--------|
| `<= 0` | signal absent |
| `1..2` | `+10` |
| `3..4` | `+20` |
| `>= 5` | `+30` |

**Input:** failures already resolved into `RiskContext` from `BruteForceProtectionService` (Redis-backed counters shared with lockout).

**Detail example:** `"5 recent failed attempts"`.

### 6.4 `KNOWN_TRUSTED_DEVICE`

**Class:** `KnownTrustedDeviceSignalEvaluator`  
**Weight:** `−30`

**Fires when:** `context.trustedCookieValid() == true`  
(That flag already requires signed cookie match **and** active, non-expired, non-revoked DB row — see §14–§16.)

**Detail example:** `"Valid trusted-device cookie"`.

---

## 7. Risk score

**Definition:** Integer in `[0, 100]` computed by `RiskEvaluationService`:

```text
raw   = sum(signal.weight for each fired signal)
score = clamp(raw, 0, 100)
```

Negative sums clamp to `0`. Values above `100` clamp to `100`.

Logged as structured info: `tenantId`, `userId`, `score`, `decision`, list of fired `RiskSignalId` names (no raw PII beyond ids).

---

## 8. RiskDecision

**Type:** `com.authenza.common.enums.RiskDecision`

| Value | Mapping rule | Enforcement |
|-------|--------------|-------------|
| `ALLOW` | `score < stepUpThreshold` (or adaptive disabled) | Continue login; policy MFA may still apply |
| `STEP_UP` | `stepUpThreshold ≤ score < blockThreshold` | See [Step-up](#12-step-up) |
| `BLOCK` | `score ≥ blockThreshold` | Clear `SecurityContext`; redirect `/{tenant}/login?error` |

Decision function (`RiskEvaluationService.decide`):

```text
if score >= blockThreshold → BLOCK
else if score >= stepUpThreshold → STEP_UP
else → ALLOW
```

---

## 9. RiskEvaluationResult

**Type:** `com.authenza.core.risk.RiskEvaluationResult` (record)

| Field | Meaning |
|-------|---------|
| `decision` | `RiskDecision` |
| `score` | Clamped score |
| `signals` | Immutable list of fired `RiskSignal`s (explainability for logs) |
| `newDeviceDetected` | `true` if any signal has `id == NEW_DEVICE` |

Helpers: `requiresStepUp()`, `isBlocked()`.  
Factory: `allowDisabled()` → `ALLOW`, score `0`, empty signals, `newDeviceDetected=false`.

---

## 10. RiskEvaluationService

**Type:** `com.authenza.core.risk.RiskEvaluationService`  
**API:** `RiskEvaluationResult evaluate(RiskContext context)`

Algorithm:

1. Load `AdaptiveAuthPolicy.Snapshot` for `context.tenantId()`.
2. If `!enabled` → `allowDisabled()`.
3. Else collect signals from all evaluators; compute score; `decide`; set `newDeviceDetected`.
4. On exception: if `failClosed` → synthetic `STEP_UP` at step-up threshold; else `ALLOW` score `0`.

Does **not** perform redirects, MFA, cookies, or email — that is `LoginRiskGate`.

---

## 11. LoginRiskGate / GateOutcome

**Type:** `com.authenza.core.risk.LoginRiskGate`  
**Role:** Thin enforcement wrapper used by `SecurityConfig` / MFA completion so adaptive stays out of filter config.

### `evaluateAfterCredentials(tenantId, username, userId, request, response)`

| Result condition | Behavior | Return |
|------------------|----------|--------|
| Missing tenant/user or adaptive off | No-op | `GateOutcome.proceed(false)` |
| `BLOCK` | Clear security context; `302` login?error | `stop()` |
| `STEP_UP` + policy MFA already required | Collapse into existing MFA | `proceed(notify)` |
| `STEP_UP` + MFA fully enrolled | Pending `TYPE_MFA`; `302` `/mfa-verify` | `stop(notify)` |
| `STEP_UP` + not enrolled | **No MFA setup redirect** | `proceed(notify)` |
| `ALLOW` | Continue | `proceed(newDevice && notifyNewDevice)` |
| Unexpected exception (non-IO) | Log; fail-open | `proceed(false)` |

`notify` = `policy.notifyNewDevice()` when a new/risky path warrants email.

### `onLoginFullyEstablished(tenantId, userId, notifyNewDevice, request, response)`

Called once authentication is complete (password-only path or after MFA/step-up success). Never throws to callers.

1. If adaptive enabled → `TrustedDeviceService.rememberDevice`  
2. If `notifyNewDevice` → `RiskNotificationPublisher.publishNewDeviceAlert`

### GateOutcome

```text
record GateOutcome(boolean halted, boolean notifyNewDevice)
  stop() / stop(notify) / proceed(notify)
```

`halted=true` means the HTTP response was already committed (redirect) and the password success handler must not continue establishing the session.

---

## 12. Step-up

**Definition (Phase A):** Adaptive-driven extra verification that **reuses** the existing MFA challenge pipeline — not a separate pending type.

| Implementation detail | Value |
|-----------------------|-------|
| Pending store | `AuthPendingStateStore` |
| Type constant | `TYPE_MFA` (`"mfa"`) — same as policy MFA |
| Redirect | `/{tenantId}/mfa-verify?mfaToken=...` |
| Enrollment gate | Only if `JdbcTenantUserDetailsService.isMfaFullyEnrolled(username)` |
| Setup URL | **Never** used for adaptive-only STEP_UP |

`PendingState` carries `notifyNewDevice` so MFA completion can still trigger the security email after verify.

**Collapse rule:** If `isMfaRequired(username)` already, adaptive does not open a second challenge; it returns `proceed(notify)` and lets the normal MFA branch run.

---

## 13. Device fingerprint

**Type:** `com.authenza.core.risk.DeviceFingerprintService`  
**Algorithm:** `HMAC-SHA256` → lowercase hex string.

**Material:**

```text
tenantId | userId | normalize(User-Agent) | secChUa
```

(`|` separators; null strings → `""`)

**Secret:** `app.adaptive.device-hmac-secret` with fallback `${app.oauth-tx.hmac-secret}` (hex-encoded key bytes).

**Properties:**

- Tenant pepper in material → fingerprint not portable across tenants for the same UA.  
- Server-side only — no canvas / JS fingerprint.  
- Stable for same user + UA + Sec-CH-UA; changes when browser UA changes.

Exposed via `TrustedDeviceService.computeFingerprint`.

---

## 14. `az_trusted_device` cookie

**Constant:** `TrustedDeviceCookieService.COOKIE_NAME = "az_trusted_device"`  
**Class:** `com.authenza.core.risk.TrustedDeviceCookieService`

### Format

```text
Base64Url(JSON payload) + "." + Base64Url(HMAC-SHA256(payloadB64))
```

Payload (`TrustedDeviceToken`):

```text
{ tenantId, userId, fingerprint, issuedAt, expiresAt }
```

### Cookie attributes

`Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=<trust seconds>`

### Validation (`readValidCookie`)

1. Split signature; constant-time HMAC compare  
2. Decode JSON; reject if expired  
3. Match `tenantId`, `userId`, `fingerprint` to expected values  

`clearCookie` sets `Max-Age=0`.

**Important:** Cookie validity alone is insufficient for trust — see next section.

---

## 15. `user_trusted_device`

**Storage:** Tenant schema table (Liquibase). FK → `application_user(id)` ON DELETE CASCADE.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT PK | |
| `user_id` | BIGINT | |
| `device_fingerprint` | VARCHAR(128) | HMAC hex |
| `device_name` | VARCHAR(200) | Parsed from UA |
| `last_ip` | VARCHAR(45) | |
| `last_country` | VARCHAR(2) | Reserved (Phase B geo); nullable |
| `created_at` | DATETIME | |
| `last_seen_at` | DATETIME | Updated on upsert |
| `expires_at` | DATETIME | `now + trustDeviceDays` |
| `revoked` | BOOLEAN | Soft revoke |

Indexes: `(user_id, device_fingerprint)`, `(user_id, revoked, expires_at)`.

**Active row:** `revoked = false` AND `expires_at > now` (as enforced by repository queries).

Written by auth-server-core at login; listed/revoked by IAM (`TrustedDeviceAdminService`) against the same table.

---

## 16. TrustedDeviceService

**Type:** `com.authenza.core.risk.TrustedDeviceService`  
**Facade over:** `DeviceFingerprintService`, `TrustedDeviceCookieService`, `TrustedDeviceRepository`, `AdaptiveAuthPolicy`.

| Method | Behavior |
|--------|----------|
| `computeFingerprint` | Delegates to fingerprint service |
| `isTrustedCookieValid` | Cookie verify **AND** `repository.existsActive` — revoke in DB invalidates trust even if cookie remains |
| `rememberDevice` | Upsert DB row + write cookie; swallows errors (must not break login) |
| `listDevices` / `revokeDevice` | Used from core/tests; portal path goes through IAM admin service |

**Trusted device ≠ session:** revoking a trust row does not call session invalidate. Immediate logout remains session revoke APIs / UI.

---

## 17. Fail mode OPEN / CLOSED

**Setting:** `adaptive_fail_mode`  
**Parsed:** `AdaptiveAuthPolicy.Snapshot.failClosed` (`true` only for `CLOSED`).

| Mode | `RiskEvaluationService` on evaluator exception |
|------|------------------------------------------------|
| `OPEN` (default) | Return `ALLOW`, score `0`, empty signals |
| `CLOSED` | Return `STEP_UP` at `stepUpThreshold` with a synthetic signal detail `"evaluator_error"` |

**Additionally:** `LoginRiskGate` catch-all always fail-opens (`proceed(false)`) on unexpected non-IO errors so Adaptive never takes down password login hard.

---

## 18. SecurityAlertEvent

**Type:** `com.authenza.common.events.SecurityAlertEvent`  
**Channel:** `RedisChannels.NOTIFICATION_SECURITY_ALERT` (`notification:security-alert`)  
**Publisher:** `RiskNotificationPublisher`  
**Consumer:** `auth-notification-service` → template `security-alert.html`

| Field | Role |
|-------|------|
| `tenantId` | Tenant context |
| `contextType` | Routing / template context |
| `recipientEmail` / `recipientName` | User lookup at publish time |
| `deviceName` | Parsed UA label |
| `ipAddress` | Client IP |
| `alertKind` | Distinguishes alert flavors |

Published from `LoginRiskGate.onLoginFullyEstablished` when `notifyNewDevice` is true. Does not include passwords, MFA secrets, or session tokens.

---

## 19. ClientRequestUtils

**Type:** `com.authenza.core.util.ClientRequestUtils`  
Shared helpers used by the risk gate and fingerprinting:

- Resolve client IP (proxy-aware as implemented)  
- Resolve / normalize User-Agent  
- Resolve `Sec-CH-UA`  
- Parse human-readable `deviceName`  
- Truncate strings for DB column limits  

Keeps IP/UA parsing out of signal evaluators.

---

## 20. APIs (Phase A)

### Adaptive settings (IAM)

| Method | Path | Auth |
|--------|------|------|
| `GET` | `/api/v1/settings/adaptive-auth` | Tenant admin |
| `PUT` | `/api/v1/settings/adaptive-auth` | Tenant admin; body map of allowed keys only |

Validation: booleans for enable/notify; `OPEN`/`CLOSED` for fail mode; positive ints for scores/days (as coded in `TenantSettingsController`).

### Trusted devices (IAM)

| Method | Path |
|--------|------|
| `GET` | `/api/v1/users/{userId}/trusted-devices` |
| `DELETE` | `/api/v1/users/{userId}/trusted-devices/{deviceId}` |

DTO: `TrustedDeviceResponse` (id, device name, last IP, timestamps, etc.). Soft-revokes the `user_trusted_device` row.

---

## 21. Related classes (quick map)

```text
SecurityConfig / MfaAuthenticationFilter
        │
        ▼
LoginRiskGate
   ├── RiskContextFactory
   ├── RiskEvaluationService ──► RiskDecisionEngine
   │         └── RiskSignalEvaluator[] (AbstractRiskSignalEvaluator)
   ├── StepUpCapabilityResolver (MfaStepUpCapabilityResolver)
   ├── AdaptiveAuthPolicy (RiskFailMode)
   ├── TrustedDeviceService ──► HmacSigner / Cookie / Repository
   └── RiskNotificationPublisher (SecurityAlertKind) ──► Redis
```

### Extensibility hooks (Phase A refactor)

| Hook | Add Phase B/C by… |
|------|-------------------|
| `RiskSignalEvaluator` / `AbstractRiskSignalEvaluator` | New `@Component` bean — scoring loop unchanged |
| `StepUpCapabilityResolver` / `StepUpAction` | New action + resolver branch (e.g. email OTP) |
| `RiskDecision.fromScore` / `RiskDecisionEngine` | Shared threshold math; swap aggregator later |
| `RiskFailMode` / `SecurityAlertKind` | Enum values instead of magic strings |
| `RiskContextFactory.fromRequest(..., AuthMethod)` | Passkey / social login contexts |
| `HmacSigner` | Shared HMAC for fingerprints / cookies |

---

## 22. Explicitly not implemented (terms in design only)

| Term | Status |
|------|--------|
| `login_risk_event` table / admin risk viewer | Phase B+ |
| GeoIP / `NEW_COUNTRY` / `IMPOSSIBLE_TRAVEL` | Phase B |
| Email-OTP step-up for unenrolled users | Later |
| Separate `TYPE_STEP_UP` pending type | Not used — MFA type reused |
| Continuous Access Evaluation | Phase D+ |
| Client canvas fingerprint | Out of scope |

---

## Related docs

- [PRD.md](PRD.md) — goals / acceptance  
- [PHASE-A-DIAGRAMS.md](PHASE-A-DIAGRAMS.md) — architecture / flow / sequence  
- [Design](../../../../planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md) — broader phases B–D+  
