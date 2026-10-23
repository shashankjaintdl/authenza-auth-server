# Feature PRD — Adaptive / Risk-Based Authentication

| Field | Value |
|-------|--------|
| Phase | 2 (feature) |
| Sub-phases | A (MVP) · B (Geo) · C (Hardening) · D+ (Enterprise vision) |
| Status | **Phase A shipped**; B/C/D+ not started |
| Parent | [Phase 2 PRD](../PRD.md) |
| Product parent | [`docs/PRD.md`](../../../PRD.md) §6.2.1 |
| Roadmap | [`planning/ROADMAP.md`](../../../../planning/ROADMAP.md) → Adaptive |
| Design (engineering) | [`planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md`](../../../../planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md) |
| Phase A diagrams | [`PHASE-A-DIAGRAMS.md`](PHASE-A-DIAGRAMS.md) |
| Feature explanation | [`FEATURES.md`](FEATURES.md) |
| Manual tests (Phase A) | [`MANUAL-TEST-SCENARIOS.md`](MANUAL-TEST-SCENARIOS.md) |

---

## 1. Problem

Every successful password/passkey login is treated the same. New devices, new IPs, and stuffing-adjacent failure bursts often complete without extra friction or user notification.

## 1.1 Features at a glance (Phase A)

| Feature | What it does |
|---------|----------------|
| **Risk scoring** | After password OK, sum signal weights → score `0..100` → ALLOW / STEP_UP / BLOCK |
| **Signals** | `NEW_DEVICE` (+40), `NEW_IP` (+15), `RECENT_FAILURES` (+10..30), `KNOWN_TRUSTED_DEVICE` (−30) |
| **Step-up** | Extra MFA verify when enrolled; never force MFA **setup** if OTP off |
| **Trusted devices** | Remember browser via cookie + DB; revoke lowers trust only (not session) |
| **Security email** | Optional alert on new/risky device path |
| **Tenant controls** | Opt-in flag, thresholds, notify, fail mode, trust TTL, IP lookback |
| **Fail-open** | Default: evaluator/gate errors do not break login |

Full product/engineering explanation: [`FEATURES.md`](FEATURES.md). Diagrams: [`PHASE-A-DIAGRAMS.md`](PHASE-A-DIAGRAMS.md).

## 2. Goals

| Goal | Measure |
|------|---------|
| Opt-in risk scoring after credentials | Flag `adaptive_auth_enabled` default false |
| Progressive challenge when MFA exists | STEP_UP → MFA verify |
| No forced MFA setup when OTP off | Unenrolled + optional MFA → allow + notify |
| Remember devices safely | Trust cookie + DB; revoke invalidates trust |
| Alert user on new/risky sign-in | Security email when notify enabled |

## 3. Personas

End user (security alerts, trusted devices), tenant admin (enable/thresholds), support (explainable decisions — Phase B+ audit).

## 4. Scope by sub-phase

| Sub-phase | Scope | Status |
|-----------|--------|--------|
| **A — MVP** | NEW_DEVICE, NEW_IP, RECENT_FAILURES; ALLOW/STEP_UP/BLOCK; trusted devices; email; portal toggle | **Shipped** |
| **B — Geo** | GeoIP, NEW_COUNTRY, IMPOSSIBLE_TRAVEL, login_risk_event | Later |
| **C — Hardening** | IP reputation plugin, admin risk viewer, fail-closed, social/passkey hooks | Later |
| **D+ — Enterprise** | CAE, UEBA/ML, SIEM, case mgmt, MDM, SOC2/ISO mappings | Vision only |

**Promote D+ only when:** paying/pilot ask · incident in audit · compliance requires it.

## 5. Acceptance — Phase A

- [x] Disabled adaptive → identical login to pre-feature behavior.
- [x] Enabled + unknown device → step-up if MFA enrolled; else allow + optional email.
- [x] Does not force authenticator **setup** when OTP disabled.
- [x] Trusted device revoke removes future trust (not immediate logout).
- [x] Session revoke still used for immediate logout.
- [x] Evaluator errors fail-open by default.

## 6. Non-goals (A)

GeoIP, continuous mid-session evaluation, ML scores, SIEM product, forcing enrollment.

## 7. Links

- Parent: [Phase 2](../PRD.md)
- Feature explanation (detailed): [FEATURES.md](FEATURES.md)
- Manual test scenarios (Phase A): [MANUAL-TEST-SCENARIOS.md](MANUAL-TEST-SCENARIOS.md)
- Design detail: [ADAPTIVE_RISK_BASED_AUTH_DESIGN.md](../../../../planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md)
- Phase A architecture / flow / sequence: [PHASE-A-DIAGRAMS.md](PHASE-A-DIAGRAMS.md)
- Architecture: [ARCHITECTURE.md](../../../ARCHITECTURE.md) §7