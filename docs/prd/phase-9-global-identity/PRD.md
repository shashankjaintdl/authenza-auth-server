# Feature PRD — Phase 9: Global Identity & Multi-Account Management

| Field | Value |
|-------|--------|
| Phase | 9 |
| Status | **Later** |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | None |
| Roadmap | Phase 9 · `AUTO_LOGIN` deferred from Phase 2 |

---

## 1. Problem

Real users belong to multiple tenants. They expect Google-style multi-account switching and optional silent SSO across workspaces they own — without session bleed.

## 2. Requirements

| ID | Requirement |
|----|-------------|
| FR-9.1 | Unified multi-session management + account switcher UI |
| FR-9.2 | Silent cross-tenant SSO (`AUTO_LOGIN` + `G_SESSIONID`) |
| FR-9.3 | Global identity mapping registry |
| FR-9.4 | Cross-tenant account discovery (privacy-preserving) |
| FR-9.5 | Global logout across tenant DBs |
| FR-9.6 | Cross-subdomain passkeys (customize RP) |
| FR-9.7 | Global OAuth2 client registry in master DB |

## 3. Dependency

Phase 2 left `AUTO_LOGIN` intentionally deferred here. `REQUIRE_PASSWORD` remains default.

## 4. Links

- Parent: [`docs/PRD.md`](../../PRD.md)
- Related isolation: [Phase 2](../phase-2-security-authentication/PRD.md)