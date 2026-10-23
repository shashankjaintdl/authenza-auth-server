# Chapter 6 — First tenant & first login

| | |
|--|--|
| Part | [II — Getting started](README.md) |
| Previous | [← Boot order](05-boot-order-lab.md) |
| Next | [Part III →](../part-3-security-core/README.md) |

---

## Idea

Master **provisions** a tenant DB + registry row. Core serves `/{tenantId}/login` and OIDC. Portal uses Auth Code + PKCE against that issuer.

## Lab outline

1. **Provision** a tenant via master admin API (see README / master API docs for current `POST` shape — hosted vs BYOD).  
2. Confirm discovery:  
   `GET http://localhost:8081/{tenantId}/.well-known/openid-configuration`  
3. Open login:  
   `http://localhost:8081/{tenantId}/login`  
4. Or use the Tenant Portal (`4200`) configured for that tenant’s issuer.  

Exact JSON bodies change with BYOD fields — copy from current README / Postman collection rather than memorizing this chapter.

## Success criteria

- [ ] Discovery JSON returns for your `tenantId`  
- [ ] Password login reaches home or MFA challenge (not 500 / wrong DB)  
- [ ] Portal can complete PKCE login (if portal is in scope for you)  

## Further reading

- README → Boot Order, BYOD  
- [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — OIDC integration points  

---

← [Ch 5](05-boot-order-lab.md) · [Part III — Security core →](../part-3-security-core/README.md)
