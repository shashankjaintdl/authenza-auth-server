# Chapter 8 — MFA (TOTP)

| | |
|--|--|
| Part | [III — Security core](README.md) |
| Previous | [← Login & brute-force](07-login-and-brute-force.md) |
| Next | [Sessions & devices →](09-sessions-and-devices.md) |

---

## Idea

**Policy MFA** means the tenant/user *always* needs a TOTP code after password when rules say so. That is separate from **Adaptive STEP_UP**, which *may* require the same verify page when risk is medium and the user is enrolled.

## Terms (with examples)

| Term | Example |
|------|---------|
| **Policy MFA on** | Tenant setting forces MFA → every login → `/acme/mfa-verify` |
| **Enroll** | First-time QR + confirm → `/acme/mfa-setup?mfaToken=…` |
| **Fully enrolled** | `mfa_enabled` + confirmed secret → verify only |
| **Pending `TYPE_MFA`** | Redis holds username/tenant until code succeeds |

## Map

| Class / area | Role |
|--------------|------|
| `SecurityConfig` success handler | Starts MFA pending when `isMfaRequired` |
| `MfaChallengeController` / setup controllers | Serve Thymeleaf pages |
| `MfaAuthenticationFilter` | POST verify → establish session |
| `AuthPendingStateStore` | Opaque `mfaToken` |

## Flow example

```text
Password OK
  → (optional Adaptive; see Part IV)
  → isMfaRequired? 
        yes → clear SecurityContext, save TYPE_MFA, redirect mfa-verify or mfa-setup
        no  → complete login / OAuth
  → user POSTs TOTP
  → filter validates → onLoginFullyEstablished → record session → continue OAuth
```

## Lab

1. Enable MFA for a test user (or tenant policy).  
2. Login → confirm redirect to verify (or setup if not enrolled).  
3. Complete code → land in portal / authorize callback.  
4. Disable Adaptive; behavior of policy MFA must stay the same.

## Interaction with Adaptive

- Adaptive **STEP_UP** + already policy-MFA → **collapse** (one challenge).  
- Adaptive **STEP_UP** + enrolled, policy MFA off → still verify.  
- Adaptive **STEP_UP** + not enrolled → **no setup force** (Phase A).  

See [Glossary — Policy MFA / STEP_UP](../00-glossary.md).

---

← [Ch 7](07-login-and-brute-force.md) · [Chapter 9 — Sessions →](09-sessions-and-devices.md)
