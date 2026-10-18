# Chapter 11 — Adaptive concepts

| | |
|--|--|
| Part | [IV — Adaptive Auth](README.md) |
| Previous | [← Part opener](README.md) |
| Next | [Code walkthrough →](12-code-walkthrough.md) |

---

## Idea in one sentence

After the password is correct, ask: *given this device, IP, and recent failures, should we continue, ask for MFA, or stop?*

## Terms with worked examples

### Master switch

**Setting:** `adaptive_auth_enabled` (default `false`).

**Example:** Tenant `acme` leaves it `false` → `LoginRiskGate` returns immediately; login path identical to pre-feature.

### RiskContext (inputs)

Built once per evaluation, e.g.:

| Field | Example value |
|-------|----------------|
| `tenantId` | `acme` |
| `userId` | `42` |
| `ipAddress` | `203.0.113.10` |
| `deviceFingerprint` | `a1b2…` (HMAC hex) |
| `trustedCookieValid` | `false` |
| `recentFailedAttempts` | `3` |
| `authMethod` | `PASSWORD` |

### Signals → score → decision

**Example A — new laptop at home**

| Signal | Weight |
|--------|--------|
| `NEW_DEVICE` | +40 |
| *(no NEW_IP)* | |
| **Score** | **40** → **STEP_UP** (default threshold 40) |

If user has TOTP enrolled → `/acme/mfa-verify`. If not → allow + optional security email.

**Example B — known browser, new café Wi‑Fi**

| Signal | Weight |
|--------|--------|
| `KNOWN_TRUSTED_DEVICE` | −30 |
| `NEW_IP` | +15 |
| **Raw** | −15 → clamp **0** → **ALLOW** |

**Example C — stuffing then success**

| Signal | Weight |
|--------|--------|
| `NEW_DEVICE` | +40 |
| `RECENT_FAILURES` (≥5) | +30 |
| **Score** | **70** → **STEP_UP** (below block 80) |

**Example D — block**

Score ≥ `adaptive_score_block` (default 80) → redirect `/acme/login?error` (generic).

### STEP_UP vs policy MFA

| Situation | What happens |
|-----------|----------------|
| Policy MFA already required | Collapse — one MFA challenge |
| MFA enrolled, policy optional | Adaptive opens verify |
| MFA not enrolled | Phase A: **no setup page**; allow + notify |

### Trust vs session (again)

**Example:** User revokes trusted phone in portal while still using the portal on that phone → stays logged in; next cold login may STEP_UP.

Full glossary: [00-glossary.md § E](../00-glossary.md).

---

← [Part IV](README.md) · [Chapter 12 — Code walkthrough →](12-code-walkthrough.md)
