# Feature PRD — Phase 3: OAuth2 & OpenID Connect

| Field | Value |
|-------|--------|
| Phase | 3 |
| Status | **3A shipped** · **3B open** |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | [3A Foundation](phase-3a-foundation/PRD.md), [3B Hardening](phase-3b-hardening/PRD.md) |
| Roadmap | [`planning/ROADMAP.md`](../../../planning/ROADMAP.md) → Phase 3 |

---

## 1. Problem

Authenza must be a standards-compliant OIDC provider so SPAs and APIs integrate without proprietary SDKs — while remaining multi-tenant (issuer, storage, clients per tenant).

## 2. Goals

- Tenant-scoped tokens with usable claims.
- Durable authorization storage across restarts/pods.
- Portal PKCE client works end-to-end.
- Later: revocation, introspection, rotation, consent UX (without blocking GTM).

## 3. Children

| Child | Status |
|-------|--------|
| [Phase 3A — Foundation](phase-3a-foundation/PRD.md) | Shipped |
| [Phase 3B — Hardening](phase-3b-hardening/PRD.md) | Not started |

## 4. Planning rule

**Do not block** Social Login or portal RBAC on finishing all of 3B. 3A is enough to proceed.

## 5. Related leftover

Passkey → full OIDC completion tracked with 3B / GTM (see 3B child).