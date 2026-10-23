# Authenza — Feature PRD Index

Feature PRDs are organized by **roadmap phase** (folder) and **sub-phase** (nested folder where applicable).

| Level | Doc | Role |
|-------|-----|------|
| Product | [`docs/PRD.md`](../PRD.md) | Whole-product requirements |
| Phase | `docs/prd/phase-*/PRD.md` | Phase goals, scope, child links |
| Sub-phase / feature | `docs/prd/phase-*/…/PRD.md` | Focused feature PRD |

**Status source of truth:** [`planning/ROADMAP.md`](../../planning/ROADMAP.md)  
**Architecture:** [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md)

---

## Phase tree

| Phase | Parent PRD | Children |
|-------|------------|----------|
| Phase 1 — Identity lifecycle | [PRD](phase-1-identity-lifecycle/PRD.md) | — |
| Phase 2 — Security & authentication | [PRD](phase-2-security-authentication/PRD.md) | [Adaptive](phase-2-security-authentication/adaptive-risk-based-auth/PRD.md) · [Features](phase-2-security-authentication/adaptive-risk-based-auth/FEATURES.md) · [Phase A diagrams](phase-2-security-authentication/adaptive-risk-based-auth/PHASE-A-DIAGRAMS.md) |
| Phase 2.5 — India GTM readiness | [PRD](phase-2-5-india-gtm-readiness/PRD.md) | — |
| Phase 3 — OAuth2 / OIDC | [PRD](phase-3-oauth2-oidc/PRD.md) | [3A Foundation](phase-3-oauth2-oidc/phase-3a-foundation/PRD.md), [3B Hardening](phase-3-oauth2-oidc/phase-3b-hardening/PRD.md) |
| Phase 3.5 — Social Login & Admin | [PRD](phase-3-5-social-login-admin/PRD.md) | [Social Login](phase-3-5-social-login-admin/social-login/PRD.md), [Admin Console](phase-3-5-social-login-admin/admin-console/PRD.md) |
| Phase 4 — Tenant customization | [PRD](phase-4-tenant-customization/PRD.md) | [4A Access control](phase-4-tenant-customization/phase-4a-access-control/PRD.md), [4B Branding & config](phase-4-tenant-customization/phase-4b-branding-hierarchy/PRD.md) |
| Phase 5 — Interoperability | [PRD](phase-5-interoperability-observability/PRD.md) | — |
| Phase 5.5 — Hyperscale | [PRD](phase-5-5-hyperscale/PRD.md) | — |
| Phase 6 — Developer experience | [PRD](phase-6-developer-experience/PRD.md) | — |
| Phase 7 — Compliance & privacy | [PRD](phase-7-compliance-privacy/PRD.md) | — |
| Phase 8 — Monetization | [PRD](phase-8-monetization/PRD.md) | — |
| Phase 9 — Global identity | [PRD](phase-9-global-identity/PRD.md) | — |

---

## Linking convention

Every phase / feature PRD includes:

- **Parent** — link up (product PRD or phase PRD)
- **Children** — links down (if any)
- **Roadmap** — matching section in `planning/ROADMAP.md`
- **Design** — only when a detailed design exists (e.g. Adaptive)

Do **not** duplicate live status here beyond a summary table — update the roadmap first.