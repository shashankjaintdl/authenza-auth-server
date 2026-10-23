# Feature PRD — Phase 4A: Access Control & BYOD Surface

| Field | Value |
|-------|--------|
| Phase | 4 · Sub-phase **4A** |
| Status | **Next** (RBAC model foundation shipped) |
| Parent | [Phase 4 PRD](../PRD.md) |
| Roadmap | Phase 4 → Phase 4A |

---

## 1. Problem

Roles exist in JWT/data model, but many admin endpoints lack `@PreAuthorize`. Session APIs must not allow cross-user access.

## 2. Requirements

| ID | Requirement | Status |
|----|-------------|--------|
| FR-4.1 | RBAC tables + roles in JWT + IAM resource server | Shipped (foundation) |
| FR-4.2 | `@PreAuthorize` on MFA / settings / user admin | Next |
| FR-4.3 | Gate MFA policy to TENANT_ADMIN | Next |
| FR-4.4 | Session API ownership guards | Next |
| FR-4.5 | Angular `admin.guard` | Next |
| FR-4.6 | Block portal client tokens without TENANT_ADMIN where required | Next |
| FR-4.7 | Read-only Authentication → Database status page | Next |

## 3. Acceptance

- [ ] Non-admin cannot change MFA policy or reset another user’s MFA.
- [ ] User A cannot list/revoke User B’s sessions.
- [ ] Admin sees hosted vs BYOD DB status (read-only).

## 4. Links

- Parent: [Phase 4](../PRD.md)
- Sibling: [4B](../phase-4b-branding-hierarchy/PRD.md)