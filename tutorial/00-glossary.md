# Glossary — Terms with examples

| | |
|--|--|
| Previous | [← Preface](00-preface.md) |
| Next | [Part I →](part-1-foundations/README.md) |
| Up | [Book index](README.md) |

This page defines **terms Authenza uses in code and docs**, each with a short definition and a concrete example. Read once; keep as a lookup while doing labs.

---

## A. Tenancy & routing

### Tenant

**Definition:** One customer workspace. Authenza isolates tenants at the **database** level (each tenant typically has its own MySQL/Postgres DB), not only with a column filter.

**Example:** Company “Acme” is provisioned as tenant `acme`. Users, sessions, OAuth clients, and settings for Acme live only in DB `acme`. Tenant `globex` cannot see Acme’s rows.

### `tenantId`

**Definition:** Stable string identifier used in URLs and context. The adapter reads it (often from the path) and switches the datasource.

**Example:**

```text
GET  http://localhost:8081/acme/login
POST http://localhost:8081/acme/oauth2/token
```

Here `tenantId = acme`. Wrong or missing id → wrong DB or 404.

### Control plane vs data plane

| Plane | Services | Job |
|-------|----------|-----|
| **Control plane** | `auth-master-service` | Create tenants, registry, provisioning |
| **Data plane** | `auth-server-core`, `auth-iam-service` | Login, tokens, users, settings **inside** a tenant |

**Example:** `POST /api/v1/admin/tenant` on master (8080) creates DB + registry. After that, users log in on core (8081) at `/acme/login` — master is not on the hot path for every password check.

### BYOD (Bring Your Own Database)

**Definition:** Tenant DB runs on the customer’s engine/host; Authenza still runs Liquibase and routes to their JDBC URL.

**Example:** Acme passes `dbType=POSTGRES` + JDBC URL on provision. Login still uses `/{tenantId}/…`; only the physical DB location changes.

### `TenantContextHolder` / tenant routing

**Definition:** Thread-local (or request-scoped) holder of current `tenantId` so JDBC goes to the right pool via `TenantRoutingDataSource`.

**Example:** Filter sees `/acme/oauth2/authorize` → sets context `acme` → `JdbcTemplate` queries Acme’s `application_user` table.

---

## B. OAuth2 / OIDC

### Issuer

**Definition:** OIDC “who issued this token” base URL, **per tenant**.

**Example:**

```text
http://localhost:8081/acme
```

Discovery: `http://localhost:8081/acme/.well-known/openid-configuration`  
Access tokens for Acme carry that issuer (or related claim set as configured).

### Authorization Code + PKCE

**Definition:** Browser/SPA login flow. Client gets an auth `code`, then exchanges it for tokens. **PKCE** binds the exchange to a secret created in the browser (`code_verifier` / `code_challenge`) so a stolen code is useless without the verifier.

**Example (portal):**

1. Portal redirects user to `/{tenant}/oauth2/authorize?...&code_challenge=...`  
2. User logs in (+ MFA if required)  
3. Browser returns to portal with `?code=...`  
4. Portal POSTs token endpoint with `code` + `code_verifier` → access / refresh / id tokens  

### Public client

**Definition:** OAuth client that cannot keep a client secret (SPA). Authenza supports refresh for public PKCE clients without requiring a secret.

**Example:** Angular Tenant Portal client id `authenza-portal` with PKCE only — no secret in the frontend bundle.

### JWT / custom claims

**Definition:** Access (and often ID) tokens are JWTs. Authenza enriches them with tenant-relevant roles/permissions.

**Example:** Decoded payload includes `sub`, tenant-scoped roles like `TENANT_ADMIN`, and permission claims your APIs authorize on.

---

## C. Login, MFA, pending auth

### Form login / password authentication

**Definition:** Username + password POST to `/{tenantId}/login`. On success, Spring Security builds an `Authentication` — then Authenza may still **not** complete the session if force-password, Adaptive, or MFA intervenes.

**Example:** Password correct → still redirected to `/acme/mfa-verify?mfaToken=...` if MFA is required.

### Brute-force protection

**Definition:** Redis counters of failed attempts per user/tenant (and related lockout / captcha policies). Shared with Adaptive’s `RECENT_FAILURES` signal.

**Example:** User mistypes password 5 times → counter ≥ 5 → Adaptive can add +30 weight on the next **successful** password if Adaptive is enabled; lockout may also block attempts before success.

### Force password change

**Definition:** Admin set a temporary password; user must change it before normal login completes. Uses pending type `TYPE_PWD_CHANGE`.

**Example:** Success on password → redirect `/acme/force-password-change?pwdToken=...` → after change, continue MFA/OIDC as usual.

### Policy MFA

**Definition:** Tenant and/or user setting that **always** requires TOTP when the user is in scope — independent of Adaptive.

**Example:** Tenant setting “MFA required for all” → every login hits MFA after password, even on a trusted device with Adaptive ALLOW.

### TOTP / MFA enroll vs verify

| Term | Meaning | Example URL |
|------|---------|-------------|
| **Enroll / setup** | User registers authenticator secret | `/{tenant}/mfa-setup?mfaToken=...` |
| **Verify / challenge** | User enters 6-digit code | `/{tenant}/mfa-verify?mfaToken=...` |
| **Fully enrolled** | Secret confirmed and MFA enabled | Adaptive STEP_UP may redirect here |
| **Not enrolled** | No usable TOTP | Adaptive Phase A does **not** force setup |

### Pending state (`AuthPendingStateStore`)

**Definition:** Short-lived Redis record for multi-step auth. Opaque token in the query string; payload holds username, tenant, userId, optional OAuth `tx`, flags like `notifyNewDevice`.

**Types (examples):**

| Type constant | Used for |
|---------------|----------|
| `TYPE_MFA` (`"mfa"`) | Policy MFA **and** Adaptive step-up |
| `TYPE_PWD_CHANGE` | Force password change |

**Example:**

```text
/acme/mfa-verify?mfaToken=8f3a…   ← token key in Redis under type "mfa"
```

### `MfaAuthenticationFilter`

**Definition:** Intercepts POST to MFA verify; on valid TOTP, establishes `SecurityContext` and continues OAuth/session recording.

**Example:** User submits code `123456` → filter validates → `onLoginFullyEstablished` (Adaptive trust/email) → session recorded → follow saved authorize request.

---

## D. Sessions vs trusted devices

### Session (`user_session` / active devices)

**Definition:** A **currently authenticated** login tracked in the tenant DB (IP, UA, timestamps). Revoking a session logs that device out **now**.

**Example:** Security → Active sessions → Revoke “Chrome on Mac” → that browser’s tokens/session are invalidated immediately.

### Trusted device (`user_trusted_device` + `az_trusted_device`)

**Definition:** Adaptive memory of a browser fingerprint. Used to lower risk next time. **Revoking trust does not log you out**; it only removes the risk discount.

**Example:**

1. Login completes with Adaptive on → cookie `az_trusted_device` set + DB row  
2. Next week, same browser → `KNOWN_TRUSTED_DEVICE` (−30) may keep score low  
3. User revokes device in portal → DB `revoked=true` → next login treats browser as new even if cookie still present  

### Device fingerprint

**Definition:** Server-side `HMAC-SHA256` over `tenantId|userId|normalized UA|Sec-CH-UA` — not canvas fingerprinting.

**Example:** Same user on Chrome vs Firefox → different fingerprints → may get `NEW_DEVICE` on first Firefox login.

---

## E. Adaptive Risk-Based Auth (Phase A)

### Adaptive Auth (master switch)

**Definition:** Optional feature (`adaptive_auth_enabled`, default `false`). After password OK, score risk and choose ALLOW / STEP_UP / BLOCK.

**Example:** Flag off → login identical to pre-Adaptive. Flag on → unknown laptop may STEP_UP.

### Risk signal

**Definition:** One explainable contribution with an id, weight, and detail string.

| Signal | Weight | Fires when (example) |
|--------|--------|----------------------|
| `NEW_DEVICE` | +40 | No active trust row and cookie not valid |
| `NEW_IP` | +15 | IP not seen in last N days of `user_session` |
| `RECENT_FAILURES` | +10/+20/+30 | 1–2 / 3–4 / ≥5 recent failures |
| `KNOWN_TRUSTED_DEVICE` | −30 | Valid cookie **and** active DB trust row |

**Example score:** New device (+40) + new IP (+15) = **55** → STEP_UP if step-up threshold is 40.

### Risk score

**Definition:** Sum of fired signal weights, clamped to `0..100`.

**Example:** Weights −30 + 15 = −15 → clamp → **0** → ALLOW.

### Risk decision

| Decision | Example user experience |
|----------|-------------------------|
| **ALLOW** | Continue; policy MFA may still run |
| **STEP_UP** | Extra MFA verify if enrolled; else allow + optional email (Phase A) |
| **BLOCK** | Redirect `/{tenant}/login?error` — generic, no signal leak |

**Thresholds (defaults):** step-up ≥ 40, block ≥ 80 (`adaptive_score_*` settings).

### `LoginRiskGate` / `GateOutcome`

**Definition:** Facade called from `SecurityConfig` after password success. Returns whether the handler must **halt** (response already redirected) and whether to **notify** on full login.

**Example:**

```text
halted=true  → SecurityConfig returns immediately (BLOCK or adaptive MFA redirect)
halted=false → SecurityConfig continues to policy MFA / session
notifyNewDevice=true → after full auth, send security email
```

### Step-up (Adaptive)

**Definition:** Risk-driven extra proof. Phase A reuses MFA verify (`TYPE_MFA`), does not invent a separate pending type.

**Example:** Optional-MFA tenant, user enrolled, new device → redirect `/acme/mfa-verify?...` even though policy MFA is off.

### Fail mode (`RiskFailMode`)

| Mode | On evaluator error |
|------|--------------------|
| `OPEN` (default) | Treat as ALLOW — don’t break login |
| `CLOSED` | Treat as STEP_UP |

**Example:** GeoIP (future) throws; `OPEN` → login continues; `CLOSED` → force step-up path.

### Security alert / `SecurityAlertEvent`

**Definition:** Redis pub/sub payload → notification service → email (e.g. new device).

**Example:** First login from new phone with notify on → user receives “new sign-in” style mail with device label + IP (no password/secrets).

---

## F. Platform building blocks

### Redis (in Authenza)

**Uses:** Pending MFA tokens, BF counters, settings invalidation pub/sub, security-alert / email channels, tenant provision events.

**Example channel:** `notification:security-alert` carries JSON `SecurityAlertEvent`.

### Liquibase / `auth-tenant-schema`

**Definition:** Versioned SQL for **each** tenant DB (users, sessions, `user_trusted_device`, settings seeds).

**Example:** Migration seeds `adaptive_auth_enabled=false` so Adaptive is opt-in per tenant.

### Tenant settings / `AdaptiveAuthSettings`

**Definition:** Key/value rows in `tenant_settings` (cached). Adaptive keys include enable flag, thresholds, notify, fail mode, trust TTL, IP lookback.

**Example:**

```text
adaptive_auth_enabled = true
adaptive_score_step_up = 40
adaptive_notify_new_device = true
```

Admin updates via `PUT /api/v1/settings/adaptive-auth`.

### Passkey / WebAuthn

**Definition:** Passwordless or second-factor using device biometrics (FIDO2). Separate from Adaptive trust cookie; future Adaptive may weight known credentials.

**Example:** User registers “MacBook Touch ID”; later authenticates without password via WebAuthn finish endpoint.

---

## G. Quick confusions (side-by-side)

| People say… | Actually… |
|-------------|-----------|
| “Revoke trusted device = logout” | No — revoke **session** to logout now |
| “Adaptive replaces MFA” | No — it can *add* MFA when enrolled; policy MFA still applies |
| “STEP_UP always opens MFA setup” | No — Phase A never forces setup if unenrolled |
| “Same cookie means trusted” | Cookie alone is insufficient — DB row must be active |
| “Master handles every login” | No — master provisions; core authenticates |

---

## See also

- Adaptive technical glossary: [`docs/prd/.../FEATURES.md`](../docs/prd/phase-2-security-authentication/adaptive-risk-based-auth/FEATURES.md)  
- Diagrams: [`PHASE-A-DIAGRAMS.md`](../docs/prd/phase-2-security-authentication/adaptive-risk-based-auth/PHASE-A-DIAGRAMS.md)  

---

← [Preface](00-preface.md) · [Part I — Foundations →](part-1-foundations/README.md)
