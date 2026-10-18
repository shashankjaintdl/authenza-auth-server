# Adaptive / Risk-Based Authentication — Design Document

| Field | Value |
|-------|--------|
| Status | Phase A implemented (MVP) |
| Roadmap | [`planning/ROADMAP.md`](ROADMAP.md) → Phase 2 → Adaptive / Risk-Based Authentication |
| Owner | Authenza Platform |
| Scope | Multi-tenant login risk scoring + progressive challenges + security alerts |
| Out of scope (v1) | Full fraud ML platform, third-party risk vendors as hard deps, device attestation beyond WebAuthn, continuous evaluation, SIEM case management |
| Enterprise posture | Design is **enterprise-aware** (tenant policy, audit, step-up, fail mode). Full Auth0/Okta-class risk-ops is **documented below as Phase D+**, not required for MVP. |

---

## 1. Problem

Password + MFA alone treat every login the same. Attack patterns Authenza already partially sees (new IP, new device, impossible travel, credential stuffing after brute-force) still often complete the normal flow once credentials are valid.

**Goal:** After password (or passkey) succeeds, evaluate **login risk** and choose an action:

| Risk | Action |
|------|--------|
| Low | Continue (respect existing MFA policy only) |
| Medium | Step-up challenge (MFA / email OTP / passkey) **even if** tenant MFA is optional |
| High | Block or require MFA + notify user; optional admin alert |
| New device / new IP | Always emit “New device / new location” email (configurable) |

This must be **tenant-scoped**, **Redis-safe across pods**, and reuse existing MFA / session / notification infrastructure.

---

## 2. Principles

1. **Additive, not replacing** — Brute-force lockout, captcha, MFA, WebAuthn, and rate limits stay. Adaptive auth runs *after* credential success and *alongside* MFA policy.
2. **Fail-open vs fail-closed is tenant-configurable** — Default: fail-open on GeoIP / Redis errors (log + treat as medium), with a “strict” tenant toggle for high-security tenants.
3. **Explainable scores** — Persist signal breakdown so support and audit can answer “why was MFA required?”
4. **No PII in Redis keys** — Use hashed identifiers / opaque tokens; store rich detail in tenant DB.
5. **DB-per-tenant** — Risk history and trusted devices live in the tenant schema; platform defaults in `tenant_settings` / future `global_system_config`.

---

## 3. Current building blocks (reuse)

| Component | Reuse how |
|-----------|-----------|
| `SessionRecordingService` | Already captures IP, User-Agent, `device_name` on success → trust baseline + “known device” signals |
| `user_session` table | Historical IPs / devices per user |
| `BruteForceProtectionService` | Recent failure density as a risk signal |
| `MfaAuthenticationFilter` + `AuthPendingStateStore` | Same pending-state pattern for **step-up** after password |
| `auth-notification-service` | “New device detected” / high-risk login emails |
| `tenant_settings` | Feature flags + thresholds per tenant |
| Redis | Short-lived risk evaluation cache, step-up tokens, device fingerprint salt |

---

## 4. High-level flow

```
┌─────────────┐     password/passkey OK      ┌──────────────────────┐
│ Login form  │ ───────────────────────────► │ RiskEvaluationService │
│ / WebAuthn  │                              └──────────┬───────────┘
└─────────────┘                                         │
                                                        ▼
                                              ┌─────────────────┐
                                              │ RiskDecision    │
                                              │ ALLOW / STEP_UP │
                                              │ BLOCK           │
                                              └────────┬────────┘
                         ┌─────────────────────────────┼─────────────────────────────┐
                         ▼                             ▼                             ▼
                   Continue login              AuthPendingStateStore           Reject + audit
                   (+ optional MFA             TYPE_STEP_UP + redirect         + optional alert
                    if policy)                 to challenge UI
```

**Insertion points**

1. **Password login success path** (before establishing full `SecurityContext` / continuing OAuth authorize) — primary.
2. **WebAuthn authenticate finish** — score with lower weight on “unknown device” if credential is known; still check IP / travel.
3. **Social login (future Phase 3.5)** — same service after IdP assertion.

Do **not** score on every API call in v1 (that is CAE / continuous evaluation — Phase 5).

---

## 5. Signals (v1)

Each signal produces a weight `0..100` and optional metadata. Final score = clamped sum (or max + weighted sum — prefer **weighted sum with cap**).

| Signal ID | Description | Suggested weight | Data source |
|-----------|-------------|------------------|-------------|
| `NEW_DEVICE` | No matching trusted device / no prior session with same fingerprint | +40 | `user_trusted_device` + `user_session` |
| `NEW_IP` | IP not seen for user in last N days | +15 | `user_session.ip_address` |
| `NEW_COUNTRY` | Country ≠ last successful login country | +25 | GeoIP from IP |
| `IMPOSSIBLE_TRAVEL` | Distance/time vs last login exceeds threshold (e.g. >500 km/h) | +50 | GeoIP + last session timestamp |
| `RECENT_FAILURES` | Failed logins for user/IP in last 15m | +10..30 | Brute-force Redis counters |
| `TOR_OR_HOSTING` | Optional IP reputation (v1.1) | +20 | Optional provider / static list |
| `KNOWN_TRUSTED_DEVICE` | Cookie / fingerprint matches trusted device | −30 | Trusted device cookie |
| `PASSKEY_KNOWN_CREDENTIAL` | WebAuthn credential already registered | −20 | `webauthn_credential` |

**Impossible travel (v1 algorithm)**

1. Resolve GeoIP for previous and current IP (lat/lon or country centroid).
2. Haversine distance `d` km; elapsed hours `t` since last successful login.
3. If `t > 0` and `d / t > V_max` (default 800 km/h) → fire signal.
4. If previous geo missing → skip (do not invent HIGH).

---

## 6. Decisions & thresholds

Stored in `tenant_settings` (keys):

| Key | Default | Meaning |
|-----|---------|---------|
| `adaptive_auth_enabled` | `false` | Master switch |
| `adaptive_score_step_up` | `40` | Score ≥ → STEP_UP |
| `adaptive_score_block` | `80` | Score ≥ → BLOCK |
| `adaptive_notify_new_device` | `true` | Email on new device even if ALLOW |
| `adaptive_fail_mode` | `OPEN` | `OPEN` \| `CLOSED` on evaluator errors |
| `adaptive_trust_device_days` | `90` | Trusted device TTL |
| `adaptive_new_ip_lookback_days` | `30` | IP history window |

**Decision matrix**

```
if !enabled → ALLOW (existing MFA policy only)
else if score >= block → BLOCK
else if score >= step_up OR (NEW_DEVICE && require_step_up_on_new_device) → STEP_UP
else → ALLOW
```

When MFA is already required by policy, STEP_UP collapses into the existing MFA challenge (do not double-challenge). When MFA is optional but STEP_UP fires, force MFA if enrolled; if not enrolled, use **email OTP** (v1) or block with “enroll MFA” message (tenant setting).

---

## 7. Data model (tenant DB)

### 7.1 `user_trusted_device`

```text
id                BIGINT PK
user_id           BIGINT FK → application_user
device_fingerprint VARCHAR(128)  -- HMAC(user_id + ua_normalized + optional client hint)
device_name       VARCHAR(200)
last_ip           VARCHAR(45)
last_country      VARCHAR(2) NULL
created_at        TIMESTAMP
last_seen_at      TIMESTAMP
expires_at        TIMESTAMP
revoked           BOOLEAN
```

Indexes: `(user_id, device_fingerprint)`, `(user_id, revoked, expires_at)`.

### 7.2 `login_risk_event` (audit)

```text
id              BIGINT PK
user_id         BIGINT NULL          -- null if unknown username
username_attempt VARCHAR(255) NULL
ip_address      VARCHAR(45)
user_agent      VARCHAR(500)
score           INT
decision        VARCHAR(20)          -- ALLOW | STEP_UP | BLOCK
signals_json    JSON / CLOB          -- [{id, weight, detail}]
created_at      TIMESTAMP
```

Retention: tenant-configurable; default 90 days (batch purge job later).

### 7.3 Optional Redis keys

```text
risk:eval:{tenantId}:{userId}:{fingerprintHash}  TTL 5m   — cache last evaluation
stepup:{token}                                   TTL 10m  — AuthPendingStateStore sibling type
trust:cookie:{tenantId}:{userId}                 — not in Redis; HttpOnly cookie value is signed token
```

---

## 8. Device fingerprint & trust cookie

**v1 fingerprint (server-side):**

```
fingerprint = HEX(HMAC_SHA256(tenantPepper,
  userId | "|" | normalize(User-Agent) | "|" | optionalSecChUa))
```

Do not rely on JS canvas fingerprinting in v1 (privacy + flaky). Optional later: client-reported `device_id` from portal SPA in a signed cookie after successful step-up.

**Trust cookie** (after successful STEP_UP or ALLOW on known device):

- Name: `az_trusted_device`
- HttpOnly, Secure, SameSite=Lax, Path=/
- Value: signed JWT/JWS `{ tenantId, userId, fp, exp }`
- On login, if cookie valid → emit `KNOWN_TRUSTED_DEVICE` negative signal

User can revoke devices from portal Security → Devices (extend existing sessions UI).

---

## 9. Service design

### 9.1 Module placement

| Class | Module | Responsibility |
|-------|--------|----------------|
| `RiskEvaluationService` | `auth-server-core` | Score + decision at login |
| `GeoIpResolver` | `auth-server-core` (interface) | IP → country/lat/lon (MaxMind local DB or no-op stub) |
| `TrustedDeviceService` | `auth-server-core` | CRUD trust + cookie verify |
| `LoginRiskEventRepository` | JDBC in tenant DS | Persist audit |
| `RiskNotificationPublisher` | `auth-server-core` → Redis event | New device / high risk emails |
| Portal: Devices & Adaptive settings | `authenza-tenant-portal` | Toggle + thresholds + revoke device |

### 9.2 API sketch

**Internal (auth-server-core)** — not public product API in v1:

```text
RiskDecision evaluate(RiskContext ctx)
RiskContext {
  tenantId, userId, username, ip, userAgent,
  authMethod: PASSWORD | PASSKEY | SOCIAL,
  webAuthnCredentialId?, lastLoginAt?, lastLoginIp?
}
```

**Admin / portal (IAM or master later):**

```text
GET  /api/v1/settings/adaptive-auth
PUT  /api/v1/settings/adaptive-auth          # TENANT_ADMIN
GET  /api/v1/users/{userId}/trusted-devices
DELETE /api/v1/users/{userId}/trusted-devices/{id}
GET  /api/v1/users/{userId}/risk-events     # optional, admin only
```

### 9.3 Step-up integration

1. Password OK → `evaluate()` → `STEP_UP`.
2. Save `AuthPendingStateStore` with type `TYPE_STEP_UP` (or reuse `TYPE_MFA` with reason `ADAPTIVE`).
3. Redirect to existing MFA verify page if TOTP enrolled; else email OTP page.
4. On success: mark device trusted, publish “new device” email if first trust, continue OAuth / establish session.
5. On failure: increment risk / brute-force style counters; do not issue tokens.

---

## 10. Notifications

| Event | Channel | Template |
|-------|---------|----------|
| New device trusted / first login from device | Email | `new-device-login.html` |
| High-risk login blocked | Email | `high-risk-login-blocked.html` |
| STEP_UP completed from new country | Email (optional) | `new-location-login.html` |

Payload via existing Redis pub/sub → `auth-notification-service` (same pattern as password reset).

Never include raw password or full session token in email; include approximate location, device label, time, and “secure account” CTA.

---

## 11. Portal UX

1. **Security → Adaptive authentication** (tenant admin)
   - Enable toggle, thresholds, fail mode, notify toggles.
2. **Security → Devices** (end user)
   - List trusted devices + last IP/country/seen; Revoke.
3. **Login** — no score shown to attacker; generic “Additional verification required.”

Copy must not reveal which signal fired (enumeration resistance).

---

## 12. Security & privacy

- Rate-limit step-up attempts (reuse MFA attempt limits).
- GeoIP database update process documented; offline country-only mode if DB missing.
- GDPR/DPDP: risk events are security logs; document retention; allow export/delete with user erasure flows (Phase 7 alignment).
- Tenant isolation: never query another tenant’s sessions for travel calc.
- Admin impersonation (future): skip or force STEP_UP — default force.

---

## 13. Observability

Metrics (Micrometer):

- `authenza.risk.evaluations{decision,tenant}`
- `authenza.risk.signals{signal}`
- `authenza.risk.geoip.errors`
- `authenza.risk.latency`

Structured log (no PII beyond hashed user id): `tenantId`, `decision`, `score`, `signalIds`.

---

## 14. Phased delivery

### Phase A — MVP (recommended first slice)

1. Settings flags + `RiskEvaluationService` with `NEW_DEVICE`, `NEW_IP`, `RECENT_FAILURES` only (no GeoIP).
2. Wire password login → STEP_UP via existing MFA pending flow.
3. Trusted device cookie + table.
4. New-device email.
5. Portal: enable toggle + devices list/revoke.

**Feature explanation:** [`docs/prd/.../adaptive-risk-based-auth/FEATURES.md`](../docs/prd/phase-2-security-authentication/adaptive-risk-based-auth/FEATURES.md)  
**Diagrams (architecture / flow / sequence):** [`docs/prd/.../adaptive-risk-based-auth/PHASE-A-DIAGRAMS.md`](../docs/prd/phase-2-security-authentication/adaptive-risk-based-auth/PHASE-A-DIAGRAMS.md)

### Phase B — Geo / travel

1. Embed MaxMind GeoLite2 (or similar) in `auth-server-core`.
2. Add `NEW_COUNTRY` + `IMPOSSIBLE_TRAVEL`.
3. Persist `login_risk_event` for support.

### Phase C — Hardening

1. IP reputation optional plugin.
2. Admin risk event viewer.
3. Fail-closed mode for enterprise tenants.
4. Social login + passkey hooks.

### Phase D+ — Future / Enterprise (vision only)

**When:** After Adaptive Phases A–C are stable. **Not** a near-term roadmap build phase — one-liners only; do not design in detail until promoted.

- Continuous evaluation during a session (re-score on refresh / introspection; force re-auth on elevated risk)
- UEBA or ML scoring (beyond rule weights; keep rules as fallback)
- SIEM connectors (Splunk, Sentinel, and similar export/webhook)
- Admin investigation UI and case management
- Device attestation and managed-device posture (MDM / attestation signals)
- Compliance mappings (SOC 2, ISO) for adaptive controls and retention

**Promote Later → Now only when:**

1. A paying or pilot customer asks for it.
2. An actual incident pattern shows up in the audit data.
3. A compliance audit requires it.

Ship A → B → C first; pull a Phase D+ line only when one of the triggers above fires.

### Extensibility already in Phase A (no generics yet)

Phase A deliberately uses **interfaces, enums, and one abstract base** — not `Foo<T, U>` — because Adaptive varies **behavior** (new signals, new step-up channels), not payload types:

| Hook (shipped) | Future use |
|----------------|------------|
| `RiskSignalEvaluator` / `AbstractRiskSignalEvaluator` | Phase B/C: add `NEW_COUNTRY`, `IMPOSSIBLE_TRAVEL`, reputation as Spring beans |
| `StepUpCapabilityResolver` / `StepUpAction` | Email OTP / passkey step-up without rewriting `LoginRiskGate` |
| `RiskContextFactory` + `AuthMethod` | Social / passkey evaluation contexts |
| `RiskDecisionEngine` | Swap or wrap scoring without changing the gate |
| `RiskFailMode` / `SecurityAlertKind` | New fail modes / alert kinds as enum values |

**Do not** introduce `RiskEvaluator<TContext, TSignal, TDecision>` (or similar) until a second real type appears — speculative generics add noise without helping Phase B.

### Generics — for later (when types actually diverge)

Promote these only when the trigger fires. Prefer typed codecs / aggregators over genericizing the whole Adaptive pipeline.

| Candidate | Shape (sketch) | Promote when |
|-----------|----------------|--------------|
| Signed token codec | `SignedTokenCodec<TPayload>` — HMAC envelope shared by trust cookie, OAuth TX, future signed cookies | Next time trust-cookie + OAuth TX (or a third signed token) are touched together |
| Score aggregator | `ScoreAggregator<TFeatures>` — rules `List<RiskSignal>` vs ML feature vector | Phase D+ UEBA/ML lands; keep weighted-sum rules as fallback |
| Step-up channel handler | `StepUpHandler<TRequest, TResponse>` only if channels need distinct DTOs | Email OTP / WebAuthn step-up introduce payloads that no longer fit `StepUpAction` + MFA pending alone |
| Risk event store | `RiskEventRepository<TEvent>` or typed writers | Phase B+ `login_risk_event` and later SIEM export share persistence shapes |

**Rule:** interfaces/enums for new *behavior*; generics only for shared *algorithms over different payload types*. Until then, add a bean or enum value — not a type parameter.

---

## 15. Testing strategy

| Layer | Cases |
|-------|--------|
| Unit | Score math, travel calc, threshold decisions, cookie HMAC |
| Integration | Login with unknown UA → STEP_UP; trusted cookie → ALLOW; MFA-required tenant no double prompt |
| Multi-pod | Pending step-up token in Redis survives instance hop |
| Tenant isolation | User A tenant X device not trusted in tenant Y |
| Negative | GeoIP down + fail-open; GeoIP down + fail-closed |

---

## 16. Non-goals / explicit deferrals

These are **intentionally out of MVP**. See **Phase D+** for the enterprise path — do not expand Phase A to absorb them.

- Continuous session risk re-check on every API request (→ CAE / introspection).
- Selling risk scores as a product API in v1.
- Replacing WebAuthn attestation with proprietary device binding.
- Adaptive auth for M2M / client_credentials (not interactive).
- In-product SOC case management, full UEBA/ML platform, mandatory third-party risk vendors.

---

## 17. Open decisions

1. Email OTP library vs reuse notification OTP patterns for users without MFA.
2. GeoIP vendor (MaxMind local vs cloud API) for India DPDP / latency preference — **prefer local DB**.
3. Whether `NEW_DEVICE` alone always STEP_UP when adaptive enabled (stricter) or only contributes weight (default design).
4. Cookie name / domain strategy under custom domains (Phase 4B).

---

## 18. Success criteria

- Tenant can enable adaptive auth without code change.
- Unknown device + password on optional-MFA tenant forces step-up ≥ 95% in e2e tests.
- Known trusted device + same country does not add friction beyond existing MFA policy.
- New-device email delivered via notification service within SLA of other auth emails.
- Zero cross-tenant leakage in risk history queries.

---

## 19. References

- [`planning/ROADMAP.md`](ROADMAP.md) — Adaptive / Risk-Based Authentication (Phase 2, out of current scope)
- `SessionRecordingService` — IP / UA / device capture
- `AuthPendingStateStore` — multi-step auth tokens
- `BruteForceProtectionService` — failure counters
- Existing MFA verify / setup Thymeleaf + portal Security screens
