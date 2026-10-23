# Adaptive Auth Phase A — Manual Test Scenarios

| Field | Value |
|-------|--------|
| Scope | Phase A only (`NEW_DEVICE`, `NEW_IP`, `RECENT_FAILURES`, `KNOWN_TRUSTED_DEVICE`) |
| Defaults | step-up **40**, block **80**, Adaptive **off** |
| Insertion point | After password OK, before policy MFA (`LoginRiskGate`) |
| Observe | `auth-server-core` log line `[Risk] tenant=… score=… decision=… signals=[…]` |

Passkey / social login are **out of scope** (hooks exist, not wired).

---

## 0. Score cheat sheet (defaults)

| Signals that fire | Score | Decision |
|-------------------|-------|----------|
| (none) | 0 | ALLOW |
| `NEW_IP` +15 | 15 | ALLOW |
| `RECENT_FAILURES` 1–2 +10 | 10 | ALLOW |
| `RECENT_FAILURES` 3–4 +20 | 20 | ALLOW |
| `RECENT_FAILURES` ≥5 +30 | 30 | ALLOW |
| `NEW_DEVICE` +40 | **40** | **STEP_UP** |
| `NEW_DEVICE` + `NEW_IP` | 55 | STEP_UP |
| `NEW_DEVICE` + failures 1–2 | 50 | STEP_UP |
| `NEW_DEVICE` + failures 3–4 | 60 | STEP_UP |
| `NEW_DEVICE` + failures ≥5 | 70 | STEP_UP |
| `NEW_DEVICE` + `NEW_IP` + failures 3–4 | 75 | STEP_UP |
| `NEW_DEVICE` + `NEW_IP` + failures ≥5 | **85** | **BLOCK** |
| `KNOWN_TRUSTED_DEVICE` −30 | 0 (clamped) | ALLOW |
| trusted + `NEW_IP` | 0 | ALLOW |
| trusted + `NEW_IP` + failures ≥5 | 15 | ALLOW |

`NEW_DEVICE` and `KNOWN_TRUSTED_DEVICE` never fire together. Valid trust cookie **and** an active DB row skip `NEW_DEVICE`.

**BLOCK is not reachable with stock brute-force.** Lockout is 5 failures; 5 failures + new device + new IP would be 85, but the account is locked before password succeeds. Use **S-BLOCK** (temporary `adaptive_score_block=70`) or raise `brute_force_max_attempts` to `10` for S-12.

---

## 1. Setup

### 1.1 Stack

Boot in README order: Redis + MySQL → master → IAM (`:8082`) → core (`:8081`) → notification → portal (`:4200`).

Replace `{tenant}` and `{userId}` throughout.

| Surface | URL |
|---------|-----|
| Login | `http://localhost:8081/{tenant}/login` |
| MFA verify | `http://localhost:8081/{tenant}/mfa-verify?mfaToken=…` |
| Adaptive settings | `PUT http://localhost:8082/api/v1/settings/adaptive-auth` |
| Trusted devices | `GET/DELETE http://localhost:8082/api/v1/users/{userId}/trusted-devices` |

IAM calls need a tenant-admin bearer token (portal login → copy from Network tab, or your usual admin curl).

### 1.2 Test users (create once)

| ID | User | MFA | Purpose |
|----|------|-----|---------|
| **U-enrolled** | password + TOTP fully enrolled, MFA **optional** (not tenant-required) | STEP_UP → `/mfa-verify` |
| **U-policy** | MFA **required** by policy + enrolled | STEP_UP collapses into existing MFA (one challenge, not two) |
| **U-plain** | no MFA secret | STEP_UP must **not** force `/mfa-setup` |
| **U-other** | any enrolled user | cookie must not transfer across users |

Reset between scenarios: log out, clear site cookies for `localhost:8081`, optionally delete `user_trusted_device` rows for the user.

### 1.3 Enable Adaptive

```bash
curl -s -X PUT "http://localhost:8082/api/v1/settings/adaptive-auth" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "adaptive_auth_enabled": "true",
    "adaptive_score_step_up": "40",
    "adaptive_score_block": "80",
    "adaptive_notify_new_device": "true",
    "adaptive_fail_mode": "OPEN",
    "adaptive_trust_device_days": "90",
    "adaptive_new_ip_lookback_days": "30"
  }'
```

Settings go through `TenantSettingsCache` (Redis invalidation). If a login still looks disabled, wait a few seconds or restart `auth-server-core`.

### 1.4 How to drive logins (curl)

Form field names must match your login page (`username` / `password`). Follow redirects with `-c/-b` cookies. Override IP and device with headers:

```bash
# New IP (does not need a real NIC)
-H "X-Forwarded-For: 203.0.113.50"

# New device fingerprint (UA is part of HMAC)
-H "User-Agent: PhaseATestBrowser/1.0"
```

Fingerprint material is `tenantId|userId|UA|Sec-CH-UA`. Same browser + same UA after a successful remember **does not** count as a new device, even in a fresh profile **if** a `user_trusted_device` row already exists for that fingerprint. Use a **different User-Agent** (or Firefox vs Chrome) to fire `NEW_DEVICE`.

### 1.5 What to watch

| Check | Where |
|-------|--------|
| Score / decision / signal IDs | core log `[Risk] …` |
| STEP_UP | 302 → `/{tenant}/mfa-verify?mfaToken=` |
| BLOCK | 302 → `/{tenant}/login?error` (generic — no signal name) |
| Trust cookie | `az_trusted_device` HttpOnly; `Path=/` |
| Trust row | tenant DB `user_trusted_device` (`revoked=0`, `expires_at > now`) |
| Session row | `user_session.ip_address` (feeds `NEW_IP`) |
| Email | notification service + inbox; Redis channel `notification:security-alert` |
| Failures | Redis `bf:{tenantId}:{username}` (lowercase username) |

Cookie is `Secure`. Chromium usually stores it on `http://localhost`; if it never appears, run the login over HTTPS or inspect whether the browser dropped it.

---

## 2. Baseline (flag off)

### S-01 — Adaptive disabled matches pre-feature login

**Setup:** `adaptive_auth_enabled=false`. U-enrolled, any browser.

**Steps:** Log in with correct password.

**Expect:**
- No `[Risk]` evaluate line (gate returns before scoring).
- No `az_trusted_device`.
- No new-device email.
- MFA only if policy already requires it.

**Pass:** Login identical to Adaptive-off product.

---

## 3. `NEW_DEVICE` (+40)

### S-02 — Unknown device, MFA enrolled → STEP_UP

**Setup:** Adaptive on. U-enrolled. No row in `user_trusted_device` for this UA. No `az_trusted_device` cookie.

**Steps:** Correct password from a **new User-Agent**.

**Expect:**
- `[Risk] score=40 decision=STEP_UP signals=[NEW_DEVICE]` (and `NEW_IP` too on a first-ever login — see S-04).
- Redirect to `/{tenant}/mfa-verify?mfaToken=…` (not `/mfa-setup`).
- After TOTP success: cookie set, `user_trusted_device` upserted, optional new-device email.

**Pass:** Extra MFA once; then device is remembered.

### S-03 — Unknown device, MFA not enrolled → ALLOW, no setup

**Setup:** Adaptive on. U-plain. New UA.

**Steps:** Correct password.

**Expect:**
- Score ≥ 40 (`NEW_DEVICE`, maybe `NEW_IP`).
- Log: `STEP_UP skipped (no MFA enrolled)`.
- Session completes. **No** redirect to `/mfa-setup`.
- Email if `adaptive_notify_new_device=true`.
- Cookie + DB row still written on full login.

**Pass:** Friction is email + remember, not forced authenticator enrollment.

### S-04 — First login also fires `NEW_IP`

**Setup:** User has **no** `user_session` rows (or none in last 30 days). New UA.

**Expect:** `signals=[NEW_DEVICE, NEW_IP]` (order may vary), score **55**, STEP_UP if enrolled.

**Pass:** First-ever login is STEP_UP, not BLOCK.

---

## 4. `NEW_IP` (+15)

### S-05 — Same device, new IP only → ALLOW

**Setup:** Complete S-02 so cookie + trust row exist. Same browser/UA.

**Steps:** Log in with `X-Forwarded-For: 203.0.113.50` (an IP **not** in this user’s `user_session` in the last 30 days).

**Expect:**
- `KNOWN_TRUSTED_DEVICE` −30 + `NEW_IP` +15 → score **0**, ALLOW.
- If you **delete the cookie but leave the DB row**, `NEW_DEVICE` does **not** fire (`existsActive`); score is **15**, still ALLOW.

**Pass:** Café Wi‑Fi on a known laptop does not force MFA.

### S-06 — Same IP again → `NEW_IP` absent

**Setup:** After S-05, session recorded with `203.0.113.50`.

**Steps:** Log in again with the same `X-Forwarded-For`.

**Expect:** `NEW_IP` not in `signals=[]`. Trusted return → ALLOW, score 0.

**Pass:** Lookback works.

### S-07 — New device + new IP → STEP_UP (not BLOCK)

**Setup:** New UA **and** unseen IP. U-enrolled. No trust row for that UA.

**Expect:** 40+15=**55**, STEP_UP, MFA verify.

**Pass:** Stacked device+IP still under 80.

---

## 5. `RECENT_FAILURES` (+10 / +20 / +30)

Use **wrong password** N times, then the **correct** password in the **same ~15 minute** Redis window. Do **not** hit lockout (default max = 5).

Reset Redis if needed: `DEL bf:{tenant}:{email}`.

| ID | Wrong tries before success | Weight | With known device (no NEW_DEVICE) | With new device (+40) |
|----|----------------------------|--------|-----------------------------------|------------------------|
| **S-08** | 1 or 2 | +10 | ALLOW (10) | STEP_UP (50) |
| **S-09** | 3 or 4 | +20 | ALLOW (20) | STEP_UP (60) |
| **S-10** | 5+ (see note) | +30 | ALLOW (30) | STEP_UP (70) |

**S-10 note:** Default `brute_force_max_attempts=5` **locks** at the 5th failure. For +30 you must either:

```bash
# temporarily, then restore to 5
PUT /api/v1/settings  { "brute_force_max_attempts": "10" }
```

then 5 wrong + 1 right, **or** skip S-10 and treat S-09 as the highest safe default.

**Expect:** `[Risk] signals` includes `RECENT_FAILURES`. After **successful** login, Redis key `bf:{tenant}:{user}` is deleted (counter reset).

**Pass:** Typos add friction; they do not BLOCK by themselves.

---

## 6. `KNOWN_TRUSTED_DEVICE` (−30)

### S-11 — Returning browser

**Setup:** S-02 completed. Same UA. Cookie present. Active DB row.

**Steps:** Correct password, no failures, same IP as last session.

**Expect:**
- `signals=[KNOWN_TRUSTED_DEVICE]` (no `NEW_DEVICE`).
- Score 0, ALLOW.
- No second MFA if policy MFA is off.
- `last_seen_at` on the trust row updates.

**Pass:** Remembered device is quiet.

---

## 7. BLOCK

### S-12 — Block needs stacked risk

**Option A (recommended, no lockout fight):** keep failures at 3–4 and lower the block bar for this test only:

```json
{ "adaptive_score_block": "70" }
```

Then: **new UA** + **unseen IP** + **3 wrong passwords** + correct password → 40+15+20=**75** ≥ 70 → BLOCK.

**Option B:** `brute_force_max_attempts=10`, restore `adaptive_score_block=80`, new UA + unseen IP + 5 wrong + correct → 40+15+30=**85** → BLOCK.

**Expect:**
- `[Risk] decision=BLOCK`.
- Redirect `/{tenant}/login?error` (no “blocked by adaptive” copy).
- No session, no `az_trusted_device` write (remember runs only after full login).
- Restore `adaptive_score_block=80` and lockout to 5 when done.

**Pass:** Block is rare and generic.

---

## 8. Step-up vs policy MFA

### S-13 — Policy MFA already required → one challenge

**Setup:** U-policy (MFA required). New device so score ≥ 40.

**Expect:** Gate `COLLAPSE_INTO_POLICY_MFA` — one `/mfa-verify` (or setup if not fully enrolled). Not two MFA pages. `notifyNewDevice` still carried into pending state.

**Pass:** No double challenge.

### S-14 — STEP_UP then TOTP success remembers device

**Setup:** S-02. Complete MFA.

**Expect:** `onLoginFullyEstablished` writes cookie + DB. Next login S-11.

**Pass:** Trust is post-MFA, not post-password.

---

## 9. Notify / email

### S-15 — New device email on

**Setup:** `adaptive_notify_new_device=true`. U-enrolled or U-plain. New UA. Notification service running.

**Expect:** After **full** login (password-only or after MFA), email from `security-alert` template. Core log `[RiskNotify] Published NEW_DEVICE alert`.

**Pass:** Mail has device name + IP, no password/MFA secret.

### S-16 — Notify off

**Setup:** `"adaptive_notify_new_device": "false"`. Repeat S-02.

**Expect:** STEP_UP still happens. No security-alert publish.

**Pass:** Notify is independent of scoring.

---

## 10. Trusted-device admin

### S-17 — List devices

**Setup:** At least one successful Adaptive login.

```bash
curl -s "http://localhost:8082/api/v1/users/{userId}/trusted-devices" \
  -H "Authorization: Bearer $TOKEN"
```

**Expect:** Device name (e.g. `Chrome on macOS`), last IP, timestamps. Not revoked rows.

### S-18 — Revoke does not kill the current session

**Steps:** While still logged in, `DELETE /api/v1/users/{userId}/trusted-devices/{deviceId}`.

**Expect:**
- Row `revoked=true`.
- Current session still works (trust ≠ session).
- Cookie may still exist in the browser.
- **Next** login from that browser: cookie invalid because DB is inactive → `NEW_DEVICE` fires again → STEP_UP if enrolled.

**Pass:** Revoke = lose remember, not instant logout.

### S-19 — Tampered / foreign cookie

**Steps:** Copy `az_trusted_device` to another user (U-other) or edit the payload.

**Expect:** HMAC fail → `trustedCookieValid=false`. Treated as unknown device.

**Pass:** Cookie is not portable across users or tenants.

---

## 11. Fail mode / safety

### S-20 — Flag off after it was on

**Setup:** Disable Adaptive. Login on a new UA.

**Expect:** Same as S-01. Existing trust rows unused.

### S-21 — Fail-open (default)

Hard to force without a code fault. Smoke-check: Redis down for brute-force count should yield `recentFailedAttempts=0` (read catch) and login still succeeds. Evaluator/gate errors log and continue (`OPEN`).

**Do not** use `adaptive_fail_mode=CLOSED` as a launch default; if you trial it, a forced evaluator error should STEP_UP (score 40) instead of ALLOW. Restore `OPEN`.

---

## 12. Regression (must still work)

| ID | Case | Expect |
|----|------|--------|
| **S-22** | Wrong password | Normal `?error`; Adaptive does not run (credentials failed). |
| **S-23** | Account lock at 5 failures | `?locked` / locked status; Adaptive never sees a success. |
| **S-24** | Tenant MFA policy on, Adaptive off | Existing MFA setup/verify unchanged. |
| **S-25** | Force password change | Redirect to force-change **before** Adaptive (see `SecurityConfig` order). |

---

## 13. Suggested run order (local afternoon)

1. S-01 (prove off)  
2. Enable Adaptive  
3. S-03 (plain user — no forced setup)  
4. S-02 → S-14 → S-11 (happy path remember)  
5. S-05, S-06, S-07 (IP)  
6. S-08, S-09 (failures; S-10 if you raise lockout)  
7. S-12 (BLOCK, then restore thresholds)  
8. S-13, S-15, S-16  
9. S-17, S-18, S-19  
10. S-20, S-22–S-25  

Tick in the Phase A PRD acceptance list as you go.

---

## 14. Restore after testing

```json
{
  "adaptive_auth_enabled": "false",
  "adaptive_score_step_up": "40",
  "adaptive_score_block": "80",
  "adaptive_notify_new_device": "true",
  "adaptive_fail_mode": "OPEN",
  "brute_force_max_attempts": "5"
}
```

Delete leftover `user_trusted_device` rows and Redis `bf:*` keys for the test users if the tenant will be reused.
