# Feature PRD — Phase 2.5: India GTM — Immediate Launch Readiness

| Field | Value |
|-------|--------|
| Phase | 2.5 |
| Status | **Not started / Next** (parallel to Phase 2 leftovers) |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | None (items are peer GTM workstreams) |
| Roadmap | [`planning/ROADMAP.md`](../../../planning/ROADMAP.md) → Phase 2.5 + India GTM priorities |

---

## 1. Problem

Indian developers and buyers will not evaluate Authenza without: local try, compliance story (DPDP), and clear pricing. These are sales/DX unlocks, not deep protocol work.

## 2. Goals

- Zero-config local stack (“Authenza in a Box”).
- 5-minute quickstart.
- Explicit DPDP positioning from DB-per-tenant architecture.
- INR + free-tier packaging defined (implementation may land in Phase 8).

## 3. Requirements

| ID | Requirement | Status |
|----|-------------|--------|
| FR-GTM.1 | Production-quality docker-compose full stack + demo tenant | Next |
| FR-GTM.2 | QUICKSTART.md (clone → login → first client) | Next |
| FR-GTM.3 | DPDP_COMPLIANCE.md + README badge | Next |
| FR-GTM.4 | INR pricing + free tier definition | Next (ship with Phase 8) |

## 4. Acceptance

- [ ] New developer reaches portal login in ≤10 minutes on a clean machine.
- [ ] Demo tenant credentials work without manual DB setup.
- [ ] DPDP doc exists and is linked from README.
- [ ] Public INR/free tier narrative agreed for sales.

## 5. Related GTM (other phases)

Social Login + Admin Console → [Phase 3.5](../phase-3-5-social-login-admin/PRD.md).