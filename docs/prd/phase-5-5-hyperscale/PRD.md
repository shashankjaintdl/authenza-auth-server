# Feature PRD — Phase 5.5: Hyperscale Architecture

| Field | Value |
|-------|--------|
| Phase | 5.5 |
| Status | **Later** (not a GTM blocker) |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | None |
| Roadmap | [`planning/ROADMAP.md`](../../../planning/ROADMAP.md) → Phase 5.5 |

---

## 1. Problem

Current MySQL + Redis + Caffeine stack is enough for early customers. Hyperscale (1M+ RPS **data plane**) needs edge verification and storage/concurrency evolutions.

## 2. Requirements (vision)

- Control plane vs data plane decoupling (gateway JWT verify)
- Java 21 virtual threads
- Redis-backed OAuth2 authorization storage
- Multi-tenant connection multiplexing (proxies)
- L1/L2 user & RBAC caches
- Distributed revocation (Redis sets / bloom filters)

## 3. Non-goals

- Blocking India GTM or Phase 3.5 on this work
- Introducing IMDG (Hazelcast/Ignite) as default

## 4. Promote when

Measured auth/token path saturation or enterprise SLA demands edge-scale verification.