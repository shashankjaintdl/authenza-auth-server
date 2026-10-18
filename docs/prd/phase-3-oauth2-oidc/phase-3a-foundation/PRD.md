# Feature PRD — Phase 3A: OIDC Foundation

| Field | Value |
|-------|--------|
| Phase | 3 · Sub-phase **3A** |
| Status | **Shipped** |
| Parent | [Phase 3 PRD](../PRD.md) |
| Product parent | [`docs/PRD.md`](../../../PRD.md) |
| Roadmap | Phase 3 → Phase 3A |

---

## 1. Problem

Portal and apps need working OIDC (claims, durable codes/tokens, PKCE) before Social Login and RBAC can ship.

## 2. Requirements

| ID | Requirement | Status |
|----|-------------|--------|
| FR-3.1 | JWT claims: tenant_id, user_id, roles, permissions, session | Shipped |
| FR-3.2 | OIDC UserInfo | Shipped |
| FR-3.3 | JDBC OAuth2 authorization storage (tenant-routed) | Shipped |
| FR-3.4 | Portal `oidc-client` PKCE seed | Shipped |
| FR-3.5 | Redis BF + settings cache invalidation | Shipped |

## 3. Acceptance

- [x] Access/ID tokens contain tenant and identity claims needed by portal/IAM.
- [x] Auth codes survive auth-server restart (JDBC).
- [x] Portal completes Auth Code + PKCE login.

## 4. Links

- Parent: [Phase 3](../PRD.md)
- Sibling: [Phase 3B](../phase-3b-hardening/PRD.md)