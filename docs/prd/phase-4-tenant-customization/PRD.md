# Feature PRD — Phase 4: Tenant Customization & Organization

| Field | Value |
|-------|--------|
| Phase | 4 |
| Status | **4A Next** · **4B Later** |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | [4A Access control](phase-4a-access-control/PRD.md), [4B Branding & config](phase-4b-branding-hierarchy/PRD.md) |
| Roadmap | [`planning/ROADMAP.md`](../../../planning/ROADMAP.md) → Phase 4 |

---

## 1. Problem

B2B tenants need real access control and (later) branding, hierarchy, and advanced configuration — without exposing admin APIs to every authenticated user.

## 2. Children

| Child | Timing |
|-------|--------|
| [4A — Access control & BYOD surface](phase-4a-access-control/PRD.md) | After / parallel Social Login |
| [4B — Branding, hierarchy & advanced config](phase-4b-branding-hierarchy/PRD.md) | Later |

## 3. Priority rule

Ship **4A PreAuthorize + guards** before broad third-party exposure of session/user admin APIs.