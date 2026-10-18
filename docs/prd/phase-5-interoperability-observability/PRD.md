# Feature PRD — Phase 5: Interoperability & Observability

| Field | Value |
|-------|--------|
| Phase | 5 |
| Status | **Mixed** — some items shipped; most later / pre-prod must-dos |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | None (workstreams listed in-body; split later if needed) |
| Roadmap | [`planning/ROADMAP.md`](../../../planning/ROADMAP.md) → Phase 5 |

---

## 1. Problem

Before paying / self-serve production customers, Authenza must look like a platform (not internal ops), separate control data, and grow audit/ops maturity for enterprise buyers.

## 2. Key requirements

| ID | Requirement | Status |
|----|-------------|--------|
| FR-5.1 | Rename `system-admin` → `platform` | Later (must before prod self-serve) |
| FR-5.2 | Platform DB separation from auth-master | Later |
| FR-5.3 | Liquibase init/migration restructure | Later |
| FR-5.4 | Tenant settings + deactivate/activate | **Shipped** |
| FR-5.5 | BYOD validate-db + Hosted/BYOD create | **Shipped** |
| FR-5.6 | Async provisioning job | Backlog |
| FR-5.7 | Auth0-style Database Connections | Later |
| FR-5.8 | Federated IdP catalog / Entra (post Social Login MVP) | Later |
| FR-5.9 | Unified audit logging | Later |
| FR-5.10 | Tracing + log aggregation | Later |
| FR-5.11 | Expanded automated testing | Later |
| FR-5.12 | Master M2M OAuth hardening | Later |
| FR-5.13 | SCIM 2.0 | Later |
| FR-5.14 | Admin impersonation | Later |
| FR-5.15 | Continuous Access Evaluation | Later |
| FR-5.16 | Settings cache scale-out | **Shipped** |
| FR-5.17–5.18 | Token/path & infra scale | Later |
| FR-5.19 | Rate-limit headers + extraction fix | **Shipped** |

## 3. Must before first paying self-serve prod

- [ ] Platform rename + DB separation plan executed  
- [ ] Audit story acceptable for procurement (at least logging design)

## 4. Links

- Parent: [`docs/PRD.md`](../../PRD.md)
- Related: [Phase 3.5 Social Login](../phase-3-5-social-login-admin/social-login/PRD.md) (pulls Google/GitHub forward)