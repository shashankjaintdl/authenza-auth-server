# Feature PRD — Phase 2: Enhanced Security & Authentication

| Field | Value |
|-------|--------|
| Phase | 2 |
| Status | **Completed through REQUIRE_PASSWORD**; Adaptive A shipped; some polish open |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | [Adaptive / Risk-Based Auth](adaptive-risk-based-auth/PRD.md) |
| Roadmap | [`planning/ROADMAP.md`](../../../planning/ROADMAP.md) → Phase 2 |
| Architecture | [`docs/ARCHITECTURE.md`](../../ARCHITECTURE.md) §5–§7 |

---

## 1. Problem

Password-only login is insufficient for B2B SaaS. Buyers expect lockout, MFA, device/session control, passkeys, and (increasingly) risk-based step-up — without breaking multi-tenant isolation.

## 2. Goals

- Prevent credential stuffing (BF + captcha + lockout).
- TOTP MFA at tenant and user levels.
- Active session revoke (immediate logout).
- WebAuthn / passkeys (core).
- Cross-tenant session isolation (`REQUIRE_PASSWORD`).
- Adaptive Auth Phase A (opt-in).

## 3. Children

| Child | Purpose |
|-------|---------|
| [Adaptive / Risk-Based Auth](adaptive-risk-based-auth/PRD.md) | Risk scoring, trusted devices, security alerts |

## 4. Requirements (summary)

| Area | Status |
|------|--------|
| Brute-force + lockout + captcha | Shipped |
| TOTP MFA (policy + enroll/verify) | Shipped |
| MFA disable / admin reset APIs | Shipped (portal polish open) |
| Active sessions APIs + UI | Shipped |
| WebAuthn core | Shipped (full OIDC passwordless open) |
| Force password change enforcement | Shipped |
| Tenant rate limiting | Shipped |
| Cross-tenant `REQUIRE_PASSWORD` | Shipped |
| Cross-tenant `AUTO_LOGIN` | Deferred → Phase 9 |
| Portal switch-policy UI | Open |
| Adaptive Phase A | Shipped — see child PRD |
| Adaptive B/C / D+ | Later — see child PRD |

## 5. Acceptance (core phase)

- [x] Failed logins lock and challenge captcha as configured.
- [x] MFA required paths cannot skip TOTP when policy says so.
- [x] Session revoke ends access immediately.
- [x] Cross-tenant URL with foreign session forces re-login under `REQUIRE_PASSWORD`.
- [x] Adaptive off → login unchanged.

## 6. Related

- Design (Adaptive): [`planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md`](../../../planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md)
- Cross-tenant: [`CROSS_TENANT_SESSION_BLEED.md`](../../../guides/CROSS_TENANT_SESSION_BLEED.md)