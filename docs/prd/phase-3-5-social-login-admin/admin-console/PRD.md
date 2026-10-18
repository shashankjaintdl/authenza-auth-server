# Feature PRD — Tenant Admin Console

| Field | Value |
|-------|--------|
| Phase | 3.5 · Feature |
| Status | **Partial** — foundation shipped; full parity open |
| Parent | [Phase 3.5 PRD](../PRD.md) |
| Product parent | [`docs/PRD.md`](../../../PRD.md) |
| Roadmap | Phase 3.5 → Tenant Admin Dashboard UI |

---

## 1. Problem

REST APIs alone do not close deals with non-technical founders/operators. Buyers judge the GUI.

## 2. Shipped vs open

| Area | Status |
|------|--------|
| Users list / invite | Shipped |
| MFA / WebAuthn / session / attack protection screens | Shipped |
| Onboarding, create tenant (Hosted/BYOD), settings, manage tenants | Shipped |
| Tenant switcher + post-login resolver | Shipped |
| Adaptive settings + trusted devices | Shipped |
| Roles & permissions UI | Open |
| Audit log viewer | Open |
| OAuth clients CRUD / secret rotation | Open |
| MFA disable/reset portal polish | Open |
| `tenant_switch_policy` toggle UI | Open |

## 3. Acceptance (full console)

- [ ] Admin can manage roles/permissions without SQL.
- [ ] Admin can view security audit events in-portal.
- [ ] Admin can register/rotate OAuth clients in-portal.
- [x] Core security + tenant ops usable without APIs.

## 4. Links

- Parent: [Phase 3.5](../PRD.md)
- Sibling: [Social Login](../social-login/PRD.md)