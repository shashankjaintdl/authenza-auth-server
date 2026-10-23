# Part I — Foundations

| | |
|--|--|
| Previous | [← Glossary](../00-glossary.md) |
| Next | [Chapter 1 →](01-what-is-authenza.md) |
| Up | [Book index](../README.md) |

---

Before you run services or touch Adaptive Auth, lock in three ideas:

1. **Authenza is the customer’s OIDC provider** — apps integrate with standards, not a proprietary-only SDK.  
2. **Isolation is physical** — DB-per-tenant, not only a `tenant_id` column in a shared schema.  
3. **Control plane ≠ data plane** — master provisions; core + IAM serve day-to-day auth.

### Chapters in this part

1. [What is Authenza?](01-what-is-authenza.md)  
2. [Architecture at a glance](02-architecture-at-a-glance.md)  
3. [Module map](03-module-map.md)  

---

[Chapter 1 — What is Authenza? →](01-what-is-authenza.md)
