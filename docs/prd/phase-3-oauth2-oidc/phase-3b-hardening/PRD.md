# Feature PRD — Phase 3B: OIDC Protocol Hardening

| Field | Value |
|-------|--------|
| Phase | 3 · Sub-phase **3B** |
| Status | **Not started** |
| Parent | [Phase 3 PRD](../PRD.md) |
| Product parent | [`docs/PRD.md`](../../../PRD.md) |
| Roadmap | Phase 3 → Phase 3B |

---

## 1. Problem

3A is enough for GTM, but enterprises eventually need revocation, introspection, rotation, stronger PKCE policy, and consent UX.

## 2. Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-3.6 | Platform-wide PKCE enforcement | High |
| FR-3.7 | Token revocation (RFC 7009) | High |
| FR-3.8 | Token introspection (RFC 7662) — CAE prereq | High |
| FR-3.9 | Refresh token rotation | Medium |
| FR-3.10 | Branded multi-tenant consent screen | Medium |
| FR-3.11 | Automated JWKS rotation | Medium |
| FR-3.12 | OIDF certification base | Low / later |
| FR-3.13 | PAR, Device Grant, mTLS, RAR | On-demand |
| FR-3.14 | RegisteredClient lookup cache | Medium |
| FR-3.15 | Passkey finish → OIDC code grant | High (GTM-adjacent) |

## 3. Non-goals

Blocking Phase 3.5 Social Login until this list is complete.

## 4. Links

- Parent: [Phase 3](../PRD.md)
- Sibling: [Phase 3A](../phase-3a-foundation/PRD.md)