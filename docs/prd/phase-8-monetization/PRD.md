# Feature PRD — Phase 8: SaaS Monetization & Billing

| Field | Value |
|-------|--------|
| Phase | 8 |
| Status | **Later** · INR/free tier **define now** (GTM) |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | None |
| Roadmap | Phase 8 · pricing narrative in India GTM |

---

## 1. Problem

Authenza must map capabilities to revenue (FREE / PRO / ENTERPRISE) with enforceable limits and metering.

## 2. Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| FR-8.1 | Subscription tiers FREE / PRO / ENTERPRISE | Map to master tenant plan |
| FR-8.2 | Premium feature enforcement (403 by plan) | e.g. webhooks on FREE |
| FR-8.3 | Invite vs direct create API tiering | Enterprise unlocks direct provision |
| FR-8.4 | Tiered rate limits | Custom burst/sustained |
| FR-8.5 | MAU / usage metering + billing integration | Stripe or equiv. |
| FR-8.6 | INR + free tier packaging | Define with GTM before full build |

## 3. Links

- Pricing definition pull-forward: [Phase 2.5](../phase-2-5-india-gtm-readiness/PRD.md)