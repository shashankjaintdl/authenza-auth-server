# Chapter 7 — Login & brute-force path

| | |
|--|--|
| Part | [III — Security core](README.md) |
| Previous | [← Part opener](README.md) |
| Next | [MFA →](08-mfa-totp.md) |

---

## Idea

Form login authenticates the password. Failures increment Redis-backed counters (`BruteForceProtectionService`). Success continues through gates in `SecurityConfig` (force password → adaptive → policy MFA → session).

## Terms with examples

| Term | Example |
|------|---------|
| **Form login** | `POST /acme/login` with username/password |
| **Brute-force counter** | 3 failures → Redis count `3` for user@acme |
| **Force password change** | Temp password → `/acme/force-password-change?pwdToken=…` |
| **Success gates order** | password OK → force-pwd? → Adaptive → policy MFA → session |

Full definitions: [Glossary § C](../00-glossary.md).

## Map

| Piece | Role |
|-------|------|
| `SecurityConfig` | Success handler orchestration |
| `BruteForceProtectionService` | Failure counts / lockout |
| `AuthPendingStateStore` | Multi-step tokens in Redis |
| `TenantUriUtils` / tenant context | Resolve `tenantId` from URI |

## Why Adaptive reads BF counts

Adaptive signal `RECENT_FAILURES` reuses the **same** counters — no second failure store.

**Example:** Attacker fails 5 times, then guesses correctly → password OK, Adaptive may add +30 and STEP_UP if enrolled.

## Lab

1. With adaptive **off**, fail password several times; observe lockout / delay behavior for your tenant settings.  
2. Succeed once; confirm counter reset path (see success handler).  

## Further reading

- [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — security sections  
- Feature PRD Phase 2: [`docs/prd/phase-2-security-authentication/PRD.md`](../../docs/prd/phase-2-security-authentication/PRD.md)  

---

← [Part III](README.md) · [Chapter 8 — MFA →](08-mfa-totp.md)
