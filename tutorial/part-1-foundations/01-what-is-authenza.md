# Chapter 1 — What is Authenza?

| | |
|--|--|
| Part | [I — Foundations](README.md) |
| Previous | [← Part opener](README.md) |
| Next | [Architecture →](02-architecture-at-a-glance.md) |

---

## Idea

Authenza is an enterprise-oriented **Identity & Access Management (IAM)** product built on **Spring Authorization Server**. Differentiator versus typical shared-cloud IdPs:

- **Database-per-tenant** isolation (strong residency / DPDP story)  
- Authenza as the **OIDC provider** for customer apps and the Tenant Portal  
- Operator-friendly portal for users, security settings, sessions, tenants  

## What it is not

- Not a full fraud / SIEM platform (Adaptive Phase A is rule-based login risk only)  
- Not “M2M-only” — interactive login is the primary path for Adaptive  

## Personas you will see in code

| Persona | Typical touchpoints |
|---------|---------------------|
| End user | Login, MFA, passkeys, sessions, trusted devices |
| Tenant admin | Settings, users, adaptive toggle, invites |
| Platform / system admin | Tenant provisioning via master |

## Further reading

- [`docs/PRD.md`](../../docs/PRD.md) — product requirements  
- [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) §1 — product overview  

---

← [Part I](README.md) · [Chapter 2 — Architecture →](02-architecture-at-a-glance.md)
