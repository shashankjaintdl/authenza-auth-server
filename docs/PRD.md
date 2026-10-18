# Authenza — Product Requirements Document (PRD)

| Field | Value |
|-------|--------|
| Product | **Authenza** — Multi-tenant Identity & Access Management (IAM) |
| Document type | Complete **product** PRD (aligned to roadmap) |
| Status source of truth | [`planning/ROADMAP.md`](../planning/ROADMAP.md) |
| Architecture companion | [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) |
| Feature PRD index | [`docs/prd/README.md`](prd/README.md) |
| Adaptive design | [`planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md`](../planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md) |
| Audience | Product, engineering, GTM / sales engineering |
| Last aligned | From `planning/ROADMAP.md` (Phases 1–9 + India GTM) |

> **How to use this PRD:** This is the **parent product PRD**. Phase and feature detail live under [`docs/prd/`](prd/README.md) (phase folders → optional sub-phase / feature PRDs). Update **`planning/ROADMAP.md`** first for status, then refresh PRDs when scope changes.

### Child phase PRDs

| Phase | Feature PRD |
|-------|-------------|
| 1 Identity lifecycle | [`prd/phase-1-identity-lifecycle/PRD.md`](prd/phase-1-identity-lifecycle/PRD.md) |
| 2 Security & authentication | [`prd/phase-2-security-authentication/PRD.md`](prd/phase-2-security-authentication/PRD.md) → [Adaptive](prd/phase-2-security-authentication/adaptive-risk-based-auth/PRD.md) |
| 2.5 India GTM readiness | [`prd/phase-2-5-india-gtm-readiness/PRD.md`](prd/phase-2-5-india-gtm-readiness/PRD.md) |
| 3 OAuth2 / OIDC | [`prd/phase-3-oauth2-oidc/PRD.md`](prd/phase-3-oauth2-oidc/PRD.md) → [3A](prd/phase-3-oauth2-oidc/phase-3a-foundation/PRD.md), [3B](prd/phase-3-oauth2-oidc/phase-3b-hardening/PRD.md) |
| 3.5 Social + Admin | [`prd/phase-3-5-social-login-admin/PRD.md`](prd/phase-3-5-social-login-admin/PRD.md) → [Social](prd/phase-3-5-social-login-admin/social-login/PRD.md), [Console](prd/phase-3-5-social-login-admin/admin-console/PRD.md) |
| 4 Tenant customization | [`prd/phase-4-tenant-customization/PRD.md`](prd/phase-4-tenant-customization/PRD.md) → [4A](prd/phase-4-tenant-customization/phase-4a-access-control/PRD.md), [4B](prd/phase-4-tenant-customization/phase-4b-branding-hierarchy/PRD.md) |
| 5 Interoperability | [`prd/phase-5-interoperability-observability/PRD.md`](prd/phase-5-interoperability-observability/PRD.md) |
| 5.5 Hyperscale | [`prd/phase-5-5-hyperscale/PRD.md`](prd/phase-5-5-hyperscale/PRD.md) |
| 6 Developer experience | [`prd/phase-6-developer-experience/PRD.md`](prd/phase-6-developer-experience/PRD.md) |
| 7 Compliance | [`prd/phase-7-compliance-privacy/PRD.md`](prd/phase-7-compliance-privacy/PRD.md) |
| 8 Monetization | [`prd/phase-8-monetization/PRD.md`](prd/phase-8-monetization/PRD.md) |
| 9 Global identity | [`prd/phase-9-global-identity/PRD.md`](prd/phase-9-global-identity/PRD.md) |

---

## 1. Executive summary

Authenza is an enterprise-oriented, **self-hostable / SaaS-capable** IAM platform built on **Spring Authorization Server (OAuth2 / OIDC)**. Differentiator versus shared-cloud IdPs (Auth0, Okta, Firebase Auth):

1. **Physical database-per-tenant isolation** (strong data residency / DPDP story for India).
2. **Authenza as the customer’s OIDC provider** — apps integrate with standard protocols, not a proprietary SDK-only model.
3. **Operator-friendly Tenant Portal** for non-technical buyers (users, security, sessions, tenants).
4. **Progressive security** — MFA, passkeys, sessions, Adaptive Risk-Based Auth (Phase A shipped).

**Current product maturity (high level)**

| Area | Status |
|------|--------|
| Identity lifecycle (register, verify, reset, invite, owner bootstrap) | **Shipped** |
| Login security (BF lockout, TOTP MFA, sessions, WebAuthn core, cross-tenant REQUIRE_PASSWORD) | **Shipped** (some portal polish / RBAC open) |
| OIDC foundation (JWT claims, JDBC authz, portal PKCE) | **Shipped (3A)** — hardening **3B** open |
| Adaptive Auth | **Phase A shipped** — B/C later |
| Tenant Portal foundation | **Partial** — Social Login + full console still open |
| India GTM blockers (Social Login, in-a-box DX, DPDP doc, INR pricing) | **Next / open** |
| Enterprise federation, SCIM, billing, global multi-account | **Later** |

---

## 2. Problem statement

Indian and global B2B SaaS teams need to:

- Authenticate end users securely (password, MFA, passkeys, eventually social).
- Isolate customer PII for compliance (especially **DPDP Act 2023** / data localization).
- Offer an admin GUI — APIs alone do not close business deals.
- Avoid vendor lock-in and opaque shared multi-tenant data planes.

Shared cloud IdPs are fast to adopt but legally and commercially risky for many Indian fintech / healthtech buyers. Homegrown Spring Security stacks are flexible but slow to productize (OIDC, MFA, sessions, portal, multi-tenant ops).

**Authenza’s job:** make a production-grade, multi-tenant OIDC IAM **productizable** — with isolation as a first-class feature, not an afterthought.

---

## 3. Goals & non-goals

### 3.1 Goals

| ID | Goal | Success signal |
|----|------|----------------|
| G1 | Ship a credible **OIDC provider** per tenant | Portal + 3rd-party apps complete Auth Code + PKCE login |
| G2 | Enforce **tenant data isolation** | No cross-tenant session/data bleed; DB-per-tenant provisioned |
| G3 | Reach **first paying Indian customers** | Social Login + portal + local try + DPDP positioning |
| G4 | Provide **security controls** buyers expect | MFA, lockout, sessions, Adaptive A, passkeys |
| G5 | Remain **extensible** toward Auth0/Okta-class features | Clear later phases: SCIM, SAML-on-demand, billing, CAE |

### 3.2 Non-goals (near term)

- Building a full fraud/UEBA ML platform before Adaptive A–C are stable.
- Blocking Social Login / RBAC on finishing all OIDC Phase 3B items.
- Implementing SAML unless a paying enterprise makes it a contract blocker.
- Forcing SaaS tenant owners through platform “super-admin” first-run wizard (that path is self-hosted / in-a-box only).

---

## 4. Personas & primary use cases

### 4.1 Personas

| Persona | Needs |
|---------|--------|
| **Tenant owner / founder** | Create workspace (hosted or BYOD), invite team, toggle MFA, see sessions, manage tenants |
| **Tenant admin** | Users, roles (later), security policies, reset MFA, deactivate users |
| **End user (employee / customer)** | Sign up / accept invite, login, MFA, passkeys, reset password, manage devices |
| **Developer integrator** | OIDC discovery, PKCE SPA client, session APIs, local docker try |
| **Platform operator (self-hosted)** | First-run super-admin, provisioning, upgrades, isolation ops |
| **Enterprise security / procurement** | DPDP/SOC2 story, audit logs, SCIM/SAML (later), data export |

### 4.2 Core use cases (must work today)

1. Owner provisions a tenant → lands on dashboard (including **incognito** / empty localStorage).
2. User registers or accepts invite → verifies / sets password → logs in via OIDC to portal.
3. Admin enables MFA → user enrolls TOTP → subsequent logins challenge MFA.
4. User views/revokes **active sessions** (immediate logout) and optionally **trusted devices** (Adaptive memory).
5. Cross-tenant navigation with `REQUIRE_PASSWORD` does not silently reuse another tenant’s session.

---

## 5. Product principles

1. **Isolation first** — physical DB-per-tenant; never rely on row-level tenancy alone for PII.
2. **Standards first** — OAuth2/OIDC/WebAuthn over proprietary lock-in.
3. **Flag-gated risk** — Adaptive defaults **off**; tenants opt in.
4. **Fail safe for auth availability** — Adaptive evaluator errors fail-open by default.
5. **Buyer UI matters** — ship portal surfaces in parallel with APIs.
6. **Ship MVP, document enterprise path** — e.g. Adaptive A–C build; D+ vision only until promoted.
7. **Trusted device ≠ session** — revoke trust ≠ immediate logout; revoke session for lockout now.

---

## 6. Functional requirements by phase

Status legend: **Shipped** · **Partial** · **Next** · **Later** · **On-demand**

### 6.1 Phase 1 — Identity lifecycle — **Shipped**

| Req ID | Requirement | Status | Acceptance criteria |
|--------|-------------|--------|---------------------|
| FR-1.1 | Secure user registration with email verification before activation | Shipped | Unverified users cannot complete privileged login; verification email delivered |
| FR-1.2 | Forgot / reset password via time-limited tokens | Shipped | Token expiry enforced; password policy applied on reset |
| FR-1.3 | Profile view/update and password change | Shipped | Authenticated user can update own profile |
| FR-1.4 | Self-service account deletion | Partial | Deferred behind Phase 4 feature toggle |
| FR-1.5 | Admin invite → set-password (not public signup) | Shipped | Invite token one-time; lands on set-password |
| FR-1.6 | On provision, create owner shadow user as `TENANT_ADMIN` | Shipped | Owner can admin portal day one |
| FR-1.7 | Enforce password policy on signup/reset | Shipped | Invalid passwords rejected with clear errors |

### 6.2 Phase 2 — Security & authentication — **Mostly shipped**

| Req ID | Requirement | Status | Acceptance criteria |
|--------|-------------|--------|---------------------|
| FR-2.1 | Brute-force tracking + account lockout | Shipped | After N failures, account locked for configured duration; Redis-backed in cluster |
| FR-2.2 | Conditional captcha after repeated failures | Shipped | Math captcha after configured failure threshold |
| FR-2.3 | TOTP MFA with tenant-wide + per-user policy | Shipped | `mfa_required_for_all` and `mfa_enabled` both honored |
| FR-2.4 | MFA self-service disable (TOTP required) | Partial | Backend shipped; portal polish open |
| FR-2.5 | Admin MFA reset without user TOTP | Partial | Backend shipped; portal + full RBAC open |
| FR-2.6 | MFA endpoint authn + self-scoping | Partial | Authn/self-scope shipped; admin RBAC Phase 4 |
| FR-2.7 | Active session list + revoke one / all | Shipped | APIs + portal; immediate session end on revoke |
| FR-2.8 | Session API ownership guards | Later | Phase 4 `@PreAuthorize` |
| FR-2.9 | WebAuthn register / authenticate / manage | Partial | Core shipped; full OIDC passwordless completion open |
| FR-2.10 | Enforce `requires_password_change` on password + passkey + JWT | Shipped | Cannot skip gate via passkey or stale JWT |
| FR-2.11 | Per-tenant API rate limiting | Shipped | Tenant-scoped throttle; fail-open on Redis outage |
| FR-2.12 | Cross-tenant isolation `REQUIRE_PASSWORD` | Shipped | Mismatch clears session → re-login |
| FR-2.13 | Cross-tenant `AUTO_LOGIN` via `G_SESSIONID` | Later | Phase 9 |
| FR-2.14 | Portal toggle for `tenant_switch_policy` | Later | Phase 2 leftover / portal security policies |
| FR-2.15 | Adaptive Risk-Based Auth Phase A | Shipped | See §6.2.1 |
| FR-2.16 | Adaptive Phase B (Geo / travel) + C (hardening) | Later | After A stable |
| FR-2.17 | Adaptive Enterprise D+ (CAE, UEBA, SIEM, …) | Later | Promote only on customer/incident/audit |

#### 6.2.1 Adaptive Auth Phase A — detailed requirements (**Shipped**)

| Req ID | Requirement | Acceptance criteria |
|--------|-------------|---------------------|
| FR-A.1 | Master flag `adaptive_auth_enabled` (default false) | Disabled → zero change to existing login |
| FR-A.2 | Score signals: NEW_DEVICE, NEW_IP, RECENT_FAILURES (+ trusted negative) | Explainable weights; thresholds configurable |
| FR-A.3 | Decisions ALLOW / STEP_UP / BLOCK | BLOCK uses generic login error |
| FR-A.4 | STEP_UP only challenges if MFA enrolled or policy requires MFA | Must **not** force authenticator setup when OTP disabled and user unenrolled |
| FR-A.5 | Trusted device table + signed cookie | Cookie valid only if DB row active (revoke works next login) |
| FR-A.6 | New-device / step-up security email (configurable) | Requires notification service + user email |
| FR-A.7 | Portal: Adaptive settings + trusted device revoke | Revoke ≠ logout; session revoke = logout |
| FR-A.8 | Fail-open on evaluator errors (default) | Login still completes |

### 6.3 Phase 2.5 / India GTM readiness — **Next**

| Req ID | Requirement | Status | Notes |
|--------|-------------|--------|-------|
| FR-GTM.1 | Authenza in a Box (`docker compose up` full stack + demo tenant) | Next | Developer acquisition #1 |
| FR-GTM.2 | 5-minute QUICKSTART | Next | Clone → up → login → register client |
| FR-GTM.3 | `DPDP_COMPLIANCE.md` + README badge | Next | Sales asset for Indian enterprise |
| FR-GTM.4 | INR pricing + free tier definition | Next | Implement with Phase 8; define now |

### 6.4 Phase 3 — OAuth2 / OIDC

#### 3A — **Shipped**

| Req ID | Requirement | Status |
|--------|-------------|--------|
| FR-3.1 | JWT claims: tenant_id, user_id, roles, permissions, session claims | Shipped |
| FR-3.2 | OIDC UserInfo | Shipped |
| FR-3.3 | JDBC OAuth2 authorization storage (tenant-routed) | Shipped |
| FR-3.4 | Portal public PKCE client seed | Shipped |
| FR-3.5 | Redis BF + settings cache invalidation | Shipped |

#### 3B — **Later** (do not block Social Login)

| Req ID | Requirement | Status |
|--------|-------------|--------|
| FR-3.6 | Platform-wide PKCE enforcement | Later |
| FR-3.7 | Token revocation RFC 7009 | Later |
| FR-3.8 | Token introspection RFC 7662 | Later (CAE prereq) |
| FR-3.9 | Refresh token rotation | Later |
| FR-3.10 | Branded consent screen | Later |
| FR-3.11 | Automated JWKS rotation | Later |
| FR-3.12 | OIDF certification base | Later |
| FR-3.13 | PAR / Device / mTLS / RAR | Later / on-demand |
| FR-3.14 | RegisteredClient cache | Later |
| FR-3.15 | Passkey finish → full OIDC code grant | Next/Later (track with 3B/GTM) |

### 6.5 Phase 3.5 — Social Login & Admin Console — **Next / Partial**

| Req ID | Requirement | Status | Acceptance criteria |
|--------|-------------|--------|---------------------|
| FR-35.1 | Login with Google (IdP broker) | **Next** | From tenant login, Google returns → tenant-scoped Authenza session/token |
| FR-35.2 | Login with GitHub | **Next** | Same broker model |
| FR-35.3 | Multi-tenant account linking via global_accounts / memberships | **Next** | Existing global email maps memberships; else create + assign |
| FR-35.4 | Microsoft IdP | Later | Phase 5 enterprise |
| FR-35.5 | Tenant Admin Console foundation | Partial | Users, security, sessions, onboarding, create/manage tenants shipped |
| FR-35.6 | Roles & permissions admin UI | Later | |
| FR-35.7 | Audit log viewer | Later | Needs audit framework |
| FR-35.8 | OAuth clients CRUD / secret rotation UI | Later | |
| FR-35.9 | Portal MFA disable/reset polish + switch policy UI | Later | |

### 6.6 Phase 4 — Tenant customization

#### 4A — **Next** (after / parallel Social Login)

| Req ID | Requirement | Status |
|--------|-------------|--------|
| FR-4.1 | RBAC data model + roles in JWT | Shipped (foundation) |
| FR-4.2 | `@PreAuthorize` on admin/settings/session APIs | Next |
| FR-4.3 | Angular admin.guard | Next |
| FR-4.4 | Block portal tokens without TENANT_ADMIN where required | Next |
| FR-4.5 | Read-only Authentication → Database status page | Next |

#### 4B — **Later**

Environment tagging, org hierarchy, dynamic client mgmt, Spring Session Redis HA, branding/custom domains, feature toggles, global system config, captcha providers, custom user attributes, BYO-SMTP portal, i18n.

### 6.7 Phase 5 — Interoperability & observability — **Later** (selected must-dos called out)

| Req ID | Requirement | Status | Notes |
|--------|-------------|--------|-------|
| FR-5.1 | Rename `system-admin` → `platform` | Later (must before prod self-serve) | Bundle with DB separation |
| FR-5.2 | Platform DB separation from auth-master | Later | |
| FR-5.3 | Liquibase init/migration path cleanup | Later | |
| FR-5.4 | Tenant settings edit + deactivate/activate | **Shipped** | |
| FR-5.5 | BYOD validate-db + Hosted/BYOD create | **Shipped** | |
| FR-5.6 | Async provisioning job | Later / backlog | Only if create p95 demands |
| FR-5.7 | Auth0-style named Database Connections | Later | After 4A DB page |
| FR-5.8 | Federated IdP catalog / Entra admin UI | Later | Google/GitHub = 3.5 |
| FR-5.9 | Unified audit logging | Later | |
| FR-5.10 | Distributed tracing + log aggregation | Later | |
| FR-5.11 | Expanded automated testing (unit/IT/e2e/contract) | Later | |
| FR-5.12 | Master service M2M OAuth hardening | Later | SOC2 readiness |
| FR-5.13 | SCIM 2.0 | Later | Enterprise tier |
| FR-5.14 | Admin impersonation | Later | Enterprise |
| FR-5.15 | Continuous Access Evaluation | Later | Needs introspection |
| FR-5.16 | Settings cache scale-out | **Shipped** | |
| FR-5.17 | Token customizer zero-latency / async session link | Later | |
| FR-5.18 | Prod infra Redis Cluster / pool tuning | Later | |
| FR-5.19 | Rate-limit headers + extraction fix | **Shipped** | |

### 6.8 Phase 5.5 — Hyperscale — **Later**

Edge JWT verification, virtual threads, Redis OAuth2 authorization store, connection multiplexing, L1/L2 user/RBAC cache, bloom-filter revocation — architectural readiness for 1M+ RPS data plane. Not a GTM blocker.

### 6.9 Phase 6 — Developer experience & extensibility — **Later / GTM pull-forward**

| Req ID | Requirement | Status |
|--------|-------------|--------|
| FR-6.1 | Polished local stack + CLI | Next (overlaps GTM.1) |
| FR-6.2 | First-run platform setup wizard (self-hosted only) | Later |
| FR-6.3 | Async event webhooks | Later |
| FR-6.4 | Sync pre-login “plugin killer” webhooks | Later |
| FR-6.5 | Custom claims providers | Later |
| FR-6.6 | M2M API keys | Later |
| FR-6.7 | JIT legacy user migration | Later |
| FR-6.8 | Professional notification engine / BYO-SMTP marketplace | Later |

### 6.10 Phase 7 — Compliance & privacy — **Later / On-demand**

Data export / takeout, right to be forgotten, full DPDP audit + consent APIs, **SAML on-demand**, step-up for high-risk actions, production readiness checklist.

### 6.11 Phase 8 — Monetization — **Later**

FREE / PRO / ENTERPRISE plans, feature gating, tiered rate limits, MAU metering + Stripe (or equiv.), INR packaging for India GTM.

### 6.12 Phase 9 — Global identity & multi-account — **Later**

Multi-session switcher, `AUTO_LOGIN` + `G_SESSIONID`, global identity registry, cross-tenant discovery, global logout, cross-subdomain passkeys, global OAuth client registry in master DB.

---

## 7. Non-functional requirements

| ID | Category | Requirement | Status |
|----|----------|-------------|--------|
| NFR-1 | Isolation | No cross-tenant data or session bleed | Shipped (REQUIRE_PASSWORD path) |
| NFR-2 | Availability | Adaptive / Redis failures must not brick login (fail-open defaults) | Shipped for Adaptive A + rate limit |
| NFR-3 | Scalability | Pending MFA/pwd-change and BF counters work across pods via Redis | Shipped |
| NFR-4 | Performance | Hot settings cached (Caffeine); avoid sync DB in token customizer long-term | Partial |
| NFR-5 | Security | Secrets via config/env; HTTPS cookies for trust device; generic auth errors | Partial / ongoing |
| NFR-6 | Operability | Liquibase for tenant schema; docker-compose for local | Partial |
| NFR-7 | Compliance posture | Document DPDP isolation story; formal audits later | Next (doc) / Later (audit) |
| NFR-8 | UX | Incognito / empty storage resolves tenants before onboarding | Shipped (portal post-login resolver) |
| NFR-9 | Extensibility | Pluggable risk signal evaluators; Redis notification channels | Shipped (pattern) |

---

## 8. Out of scope (until promoted)

- Full UEBA / ML risk scoring as a product
- In-product SOC case management
- Mandatory third-party bot/IP intel vendors
- Continuous mid-session evaluation (CAE) before introspection exists
- SAML unless enterprise contract requires it
- Replacing customer SOC tools with Authenza “cases”

**Promotion triggers (Adaptive D+ and similar):** paying/pilot customer ask · real incident pattern in audit data · compliance audit requirement.

---

## 9. Dependencies & constraints

| Dependency | Constraint |
|------------|------------|
| MySQL (or BYOD JDBC) | Tenant DB provisioned / reachable |
| Redis | Required for distributed pending auth, BF, settings invalidation, notifications |
| Email delivery | Notification service + SMTP/branding for verification, reset, invites, security alerts |
| Angular Tenant Portal | Separate app; must stay API-compatible with IAM/master/OIDC |
| Java 17 + Spring Boot 3 | Platform standard |

---

## 10. Success metrics (product)

| Metric | Target (directional) |
|--------|----------------------|
| Time-to-first-login for new developer | &lt; 10 minutes with in-a-box + quickstart |
| Portal login success (OIDC) | Stable Auth Code + PKCE without sticky sessions for MFA |
| Cross-tenant bleed incidents | Zero in production |
| Adaptive false-friction | Known trusted device does not add friction beyond MFA policy |
| India GTM | Social Login live; DPDP doc published; free/INR packaging defined |
| First 10 paying customers | Tracked commercially; product blockers = §6.3 + §6.5 |

---

## 11. Risks & open decisions

| Risk / decision | Mitigation |
|-----------------|------------|
| Portal ahead of IAM deploy → 404 on new APIs | Version/deploy IAM with portal; friendly errors |
| Adaptive STEP_UP vs disabled OTP | Fixed: no forced MFA setup when unenrolled |
| RBAC incomplete on admin APIs | Phase 4A before broad third-party exposure |
| `system-admin` naming confuses buyers | Rename to `platform` before prod self-serve |
| 3B scope creep blocks GTM | Explicitly do not block Social Login on 3B |
| BYOD vs Auth0 “connections” confusion | Keep BYOD = DB location; Connections = later IdP/store concept |

---

## 12. Delivery priorities (recommended order)

Aligned with roadmap GTM guidance:

1. **Social Login (Google → GitHub)** — Phase 3.5  
2. **RBAC enforcement (4A)** + session/API guards  
3. **Authenza in a Box + QUICKSTART + DPDP doc** — Phase 2.5 / 6  
4. **Portal console polish** (roles, clients, audit, MFA reset UI)  
5. **OIDC 3B** items as enterprise demand appears (revocation, introspection first if CAE needed)  
6. **Adaptive B/C** when A is validated in production  
7. **Billing (8), Global identity (9), Hyperscale (5.5)** as scale/revenue require  

---

## 13. Document control

| Action | Owner |
|--------|--------|
| Change requirement status | Update `planning/ROADMAP.md` first |
| Change architecture diagrams / module boundaries | Update `docs/ARCHITECTURE.md` |
| Change Adaptive behavior | Update Adaptive design + this PRD §6.2.1 |
| Major scope add (new phase) | PRD + roadmap in same change set |

---

*This PRD is the product expression of Authenza’s roadmap: what we build, for whom, what “done” means, and what we deliberately defer.*
