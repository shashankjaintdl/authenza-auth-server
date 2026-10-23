# Feature PRD — Social Login (Google & GitHub)

| Field | Value |
|-------|--------|
| Phase | 3.5 · Feature |
| Status | **Next** |
| Parent | [Phase 3.5 PRD](../PRD.md) |
| Product parent | [`docs/PRD.md`](../../../PRD.md) |
| Roadmap | Phase 3.5 → Social Login |
| Design | [`planning/designs/SOCIAL_LOGIN_IDENTITY_BROKER_DESIGN.md`](../../../../planning/designs/SOCIAL_LOGIN_IDENTITY_BROKER_DESIGN.md) |

---

## 1. Problem

Sign-up/login friction without social IdPs kills developer and end-user adoption in India SaaS.

## 2. Goals

- Authenza as **identity broker**: apps still trust Authenza OIDC; Google/GitHub authenticate the human.
- Correct multi-tenant routing via `global_accounts` + `tenant_memberships`.

## 3. Requirements

| ID | Requirement | Status |
|----|-------------|--------|
| FR-35.1 | Login with Google | Next |
| FR-35.2 | Login with GitHub | Next |
| FR-35.3 | Existing global email → load memberships → tenant token | Next |
| FR-35.4 | New email → create global account, assign to active tenant | Next |
| FR-35.5 | Microsoft Entra | Later (Phase 5) |

## 4. Acceptance

- [ ] From `/{tenantId}/login`, “Continue with Google” completes and lands in portal for that tenant.
- [ ] Returning Google user maps to existing membership without duplicate local users incorrectly.
- [ ] Failure states do not leak cross-tenant identity.

## 5. Non-goals

Replacing Authenza as the app’s IdP; full enterprise IdP catalog UI (Phase 5).

## 6. Links

- Parent: [Phase 3.5](../PRD.md)
- Sibling: [Admin Console](../admin-console/PRD.md)
- Design (architecture, linking algorithm, flow & sequence): [SOCIAL_LOGIN_IDENTITY_BROKER_DESIGN.md](../../../../planning/designs/SOCIAL_LOGIN_IDENTITY_BROKER_DESIGN.md)
