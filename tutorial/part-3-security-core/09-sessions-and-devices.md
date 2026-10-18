# Chapter 9 — Sessions & devices

| | |
|--|--|
| Part | [III — Security core](README.md) |
| Previous | [← MFA](08-mfa-totp.md) |
| Next | [OIDC for the portal →](10-oidc-for-the-portal.md) |

---

## Idea

Authenza tracks **two different “device” concepts**. Mixing them causes support confusion.

## Term A — Session (active device)

**What:** Row in `user_session` (and related session/token tracking) for a login that is **active now**.

**Example:** You are logged into the portal on Chrome. Security → Sessions shows that browser. **Revoke** → you are logged out immediately.

**APIs / UI:** IAM session list + revoke; portal “Active sessions”.

## Term B — Trusted device (Adaptive)

**What:** `user_trusted_device` + cookie `az_trusted_device` so the **next** login is lower risk.

**Example:** After a successful Adaptive-enabled login, Authenza remembers fingerprint `fp=ab12…`. Next week Adaptive emits `KNOWN_TRUSTED_DEVICE` (−30). **Revoke trust** → next login may STEP_UP; **current** session stays until you revoke the session.

## Side-by-side

| Action | Session | Trusted device |
|--------|---------|----------------|
| Purpose | Prove you are logged in now | Lower Adaptive friction later |
| Revoke effect | Logout now | Forget trust for next login |
| Cookie | Session / token cookies | `az_trusted_device` |
| Table | `user_session` | `user_trusted_device` |

## Lab

1. Login with Adaptive **on**; note a trusted device appears after success.  
2. Revoke **trusted** device only → confirm you are still in the app.  
3. Logout / revoke **session** → confirm you must log in again.  

## Further reading

- [Glossary — Sessions vs trusted devices](../00-glossary.md)  
- Adaptive FEATURES: trust lifecycle  

---

← [Ch 8](08-mfa-totp.md) · [Chapter 10 — OIDC →](10-oidc-for-the-portal.md)
