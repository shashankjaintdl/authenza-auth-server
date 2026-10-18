# Feature PRD — Phase 3.5: Social Login & Admin Dashboard

| Field | Value |
|-------|--------|
| Phase | 3.5 |
| Status | **Unblocked — Next** (highest ROI for India GTM) |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | [Social Login](social-login/PRD.md), [Admin Console](admin-console/PRD.md) |
| Roadmap | [`planning/ROADMAP.md`](../../../planning/ROADMAP.md) → Phase 3.5 |
| Prerequisite | Phase 3A (JDBC authz + JWT tenant claims) — **met** |

---

## 1. Problem

Indian SaaS users expect “Login with Google.” Non-technical buyers evaluate a GUI, not Postman. Both are table stakes for first customers.

## 2. Goals

- Identity brokering: Authenza remains the OIDC server; Google/GitHub are upstream IdPs.
- Continue polishing Tenant Admin Console toward full parity.

## 3. Children

| Child | Status |
|-------|--------|
| [Social Login (Google / GitHub)](social-login/PRD.md) | Next |
| [Tenant Admin Console](admin-console/PRD.md) | Partial (foundation shipped) |

## 4. Success

- Developer can sign in with Google on a tenant login page.
- Buyer can manage users/security/tenants without calling raw APIs for core tasks.