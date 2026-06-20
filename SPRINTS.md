# Authenza — Sprint Plan

> Status key: ✅ Done · 🔄 In Progress · ⬜ Not Started

---

## Phase 1 — User Onboarding & Identity Lifecycle

### Sprint 1 — Registration & Verification ✅

| Task | Status |
|------|--------|
| Build "Sign Up" APIs (`POST /api/v1/users/register`) | ✅ Done |
| Email verification flow via tokenized links | ✅ Done |
| `verify-email` UI page (Thymeleaf template) | ✅ Done |
| `auth-notification-service` for sending verification emails | ✅ Done |
| Redis Pub/Sub event publishing (`EMAIL_VERIFICATION`) | ✅ Done |

### Sprint 2 — Account Recovery ✅

| Task | Status |
|------|--------|
| Forgot Password API + email token dispatch | ✅ Done |
| Reset Password page (`reset-password.html`) | ✅ Done |
| Time-limited password reset tokens | ✅ Done |

### Sprint 3 — Admin User Invitation ✅

| Task | Status |
|------|--------|
| `POST /api/v1/users/invite` — admin invites user via email | ✅ Done |
| One-time invitation token + "Set Password" flow | ✅ Done |
| `AcceptInviteRequest` DTO + handler | ✅ Done |

### Sprint 4 — Profile Management ✅

| Task | Status |
|------|--------|
| User profile fetch & update APIs | ✅ Done |
| Forced password change flow (`force-password-change.html`) | ✅ Done |
| `requires_password_change` flag enforcement on login | ✅ Done |
| Self-service account deletion | ⬜ Not Started *(deferred to Phase 4 feature toggle)* |

### Sprint 5 — Admin Provisioning & Password Policy ✅

| Task | Status |
|------|--------|
| Auto-create `TENANT_ADMIN` shadow user after schema migration | ✅ Done |
| Assign `TENANT_ADMIN` role to tenant owner on provisioning | ✅ Done |
| Password policy enforcement (min length, special chars) | ✅ Done |

---

## Phase 2 — Enhanced Security & Authentication

### Sprint 6 — Brute Force Protection ✅

| Task | Status |
|------|--------|
| Track failed login attempts per user/tenant | ✅ Done |
| Account lockout after N failures (`max-attempts` configurable) | ✅ Done |
| Lock duration enforcement (`lock-duration-minutes`) | ✅ Done |
| Conditional Math Captcha after 2 failures | ✅ Done |
| **Distributed Enforcement (Redis counters across cluster)** | ⬜ Not Started |

### Sprint 7 — Multi-Factor Authentication (TOTP) 🔄

| Task | Status |
|------|--------|
| TOTP secret generation + QR code (`/mfa/setup`) | ✅ Done |
| MFA confirm endpoint (`/mfa/confirm`) | ✅ Done |
| `MfaAuthenticationFilter` — intercepts password login, gates on OTP | ✅ Done |
| Tenant-wide policy (`mfa_required_for_all` in `tenant_settings`) | ✅ Done |
| Per-user flag (`mfa_enabled` in `application_user`) | ✅ Done |
| Admin-initiated enrollment (admin sets flag → user redirected to setup on next login) | ✅ Done |
| MFA setup UI page (`mfa-setup.html`) | ✅ Done |
| MFA verify UI page (`mfa-verify.html`) | ✅ Done |
| Orphan secret cleanup (abandoned setup no longer blocks login) | ✅ Done |
| MFA self-service disable — backend (`POST /mfa/disable`) | ✅ Done |
| **MFA self-service disable — portal UI (Disable MFA button + OTP modal)** | ⬜ Not Started *(Phase 2 — Next Sprint)* |
| **Admin MFA Reset — backend (`POST /mfa/reset`, no OTP required)** | ⬜ Not Started *(Phase 2 — Next Sprint)* |
| **Admin MFA Reset — portal UI (Reset MFA button in User Management)** | ⬜ Not Started *(Phase 2 — Next Sprint)* |
| MFA endpoint security hardening (user-scoped, no cross-user access) | ⬜ Not Started *(deferred to Phase 4 RBAC)* |

### Sprint 8 — Active Session Management ✅

| Task | Status |
|------|--------|
| `sessions` DB table + Liquibase migration | ✅ Done |
| `SessionRecordingService` — record session on login | ✅ Done |
| `SessionController` — list active sessions API | ✅ Done |
| Session revocation (logout specific device) | ✅ Done |
| "Active Devices" UI in tenant portal | ✅ Done |
| Current session detection (`current: true` flag) | ✅ Done |
| `expiresAt` timestamp in session response | ✅ Done |

### Sprint 9 — WebAuthn / FIDO2 (Passkeys) ✅

| Task | Status |
|------|--------|
| `webauthn_credential` table (V0.0.11 Liquibase migration) | ✅ Done |
| Passkey registration start/finish (`/passkeys/register/start`, `/finish`) | ✅ Done |
| Passkey authentication start/finish (`/webauthn/authenticate/start`, `/finish`) | ✅ Done |
| Passkey management API (`GET/DELETE /users/{userId}/passkeys`) | ✅ Done |
| `webauthn_fingerprint_enabled` tenant feature flag | ✅ Done |
| Backend enforcement — block register/auth when flag disabled | ✅ Done |
| Login page conditional rendering of Passkey button | ✅ Done |
| Admin portal toggle (enable/disable biometrics with confirmation modal) | ✅ Done |
| WebAuthn bridge flow (`auth-server-core` → OIDC completion via bridge token) | ✅ Done |
| **Full OIDC flow integration (passkey assertion → auth code grant without password)** | ⬜ Not Started *(TODO)* |

### Sprint 10 — Session & Login Hardening 🔄

| Task | Status |
|------|--------|
| Fix: orphan `mfa_secret` blocking login when `mfa_enabled=false` | ✅ Done |
| Fix: 404 whitelabel error when OAuth2 session expires on login page | ✅ Done |
| Add `GET /{tenantId}/` handler to re-initiate authorize flow | ✅ Done |
| Extend HTTP session timeout to 4h | ✅ Done |
| Fix: `?session_expired` param handled in login page (no 401) | ✅ Done |
| Scale-Out Policy Caching (Caffeine local cache + Redis Pub/Sub invalidation) | ✅ Done |
| **Fix: `requires_password_change` gate bypassed via WebAuthn/Passkey login** — `WebAuthnBridgeController` establishes a full Spring Security session without checking the flag, allowing passkey users to skip forced password change | ⬜ Not Started |
| **Fix: `requires_password_change` flag has no effect on already-logged-in users** — if admin sets the flag after a user has a valid JWT, the user retains portal access until the token naturally expires; needs `password_change_required` JWT claim + `RevokedSessionJwtValidator` enforcement | ⬜ Not Started |
| **Tenant-Level Rate Limiting** | ⬜ Not Started |
| **Strict Cross-Tenant Session Isolation** | ⬜ Not Started *(see CROSS_TENANT_SESSION_BLEED.md)* |
| **Adaptive / Risk-Based Authentication** | ⬜ Not Started |

---

## Phase 3 — OAuth2 & OIDC Deepening

### Sprint 11 — JWT & Token Infrastructure 🔄

| Task | Status |
|------|--------|
| `OAuth2TokenCustomizer` — inject `tenant_id`, `roles`, `user_id` into JWTs | ✅ Done |
| `JdbcOAuth2AuthorizationService` — persistent token storage per-tenant | ✅ Done |
| Multi-tenant `RoutingDataSource` wired to JDBC auth service | ✅ Done |
| OIDC UserInfo endpoint customization | ✅ Done |
| **Automated JWKS rotation (dynamic `JWKSource` + 90-day cron)** | ⬜ Not Started |
| **PKCE enforcement for SPA / mobile clients** | ⬜ Not Started |
| **Token Revocation endpoint (RFC 7009)** | ⬜ Not Started |
| **Token Introspection endpoint (RFC 7662)** | ⬜ Not Started |
| **Secure Refresh Token Rotation** | ⬜ Not Started |

### Sprint 12 — Consent & Advanced OAuth2 ⬜

| Task | Status |
|------|--------|
| Custom multi-tenant OAuth2 Consent screen (`/oauth2/consent` template) | ⬜ Not Started |
| Pushed Authorization Requests (PAR — RFC 9126) | ⬜ Not Started |
| Device Authorization Grant (RFC 8628) | ⬜ Not Started |
| Mutual TLS client authentication (RFC 8705) | ⬜ Not Started |
| Rich Authorization Requests (RAR — RFC 9396) | ⬜ Not Started |
| OpenID Foundation Conformance Test Suite run | ⬜ Not Started |

---

## Phase 4 — Tenant Customization & Organization

### Sprint 13 — RBAC & Portal Segregation 🔄

| Task | Status |
|------|--------|
| `Roles`, `Permissions`, `Groups` tables in tenant schema | ✅ Done |
| Propagate roles as JWT claims | ✅ Done |
| Custom `UserDetails` carrying `userId` + `username` | ✅ Done *(via JWT `user_id` claim)* |
| `oauth2ResourceServer` JWT validation in `auth-iam-service` | ✅ Done *(TenantJwtAuthenticationManagerResolver)* |
| `@PreAuthorize` on MFA & settings endpoints | ⬜ Not Started |
| Role-gate `PUT /api/v1/settings/mfa-policy` to `TENANT_ADMIN` | ⬜ Not Started |
| Angular Route Guard (`admin.guard`) for dashboard/users/settings | ⬜ Not Started |
| Auth Server client access control (block non-admin token issuance) | ⬜ Not Started |

### Sprint 14 — Tenant Branding & Feature Toggles ⬜

| Task | Status |
|------|--------|
| Tenant Environment tagging (DEV / STAGING / PROD enum) | ⬜ Not Started |
| Production Safety Rails in portal (confirmation modals for PROD) | ⬜ Not Started |
| `[DEV]` prefix on system emails for non-production tenants | ⬜ Not Started |
| Master Service Network Isolation (VPC level) | ⬜ Not Started |
| `branding_settings` JSON column in tenant DB | ⬜ Not Started |
| Dynamic branding injection into `login.html` / `consent.html` | ⬜ Not Started |
| Custom domain support (`auth.theircompany.com`) | ⬜ Not Started |
| Hierarchical feature toggles (tenant + client level) | ⬜ Not Started |
| `allow_public_registration` toggle | ⬜ Not Started |
| Tenant-selectable CAPTCHA (Math / reCAPTCHA / Turnstile) | ⬜ Not Started |
| Dynamic Registration Schemas & custom user attributes | ⬜ Not Started |
| White-labeled email templates + BYO-SMTP | ⬜ Not Started |
| i18n / Localization on login & consent pages | ⬜ Not Started |

### Sprint 15 — Dynamic Client & Session HA ⬜

| Task | Status |
|------|--------|
| Dynamic OAuth2 Client Management API (CRUD for registered clients) | ⬜ Not Started |
| Client secret rotation | ⬜ Not Started |
| Enterprise Hierarchy (Organizations / Sub-departments) | ⬜ Not Started |
| Shared Session Storage (Spring Session → Redis HA) | ⬜ Not Started |

---

## Phase 5 — Interoperability & Observability

### Sprint 16 — Infrastructure Hardening ⬜

| Task | Status |
|------|--------|
| Production DB separation (`system-admin` isolated DB) | ⬜ Not Started |
| Liquibase `initialization/` vs `migration/` split | ⬜ Not Started |
| Federated Identity / Identity Brokering (Google, GitHub, Entra ID SSO) | ⬜ Not Started |
| Tenant Admin Dashboard APIs (all IAM routes ready) | ✅ Done *(UserController, TenantSettingsController)* |
| Unified Audit Logging Framework | ⬜ Not Started |
| Distributed Tracing (Micrometer / W3C Trace Context) | ⬜ Not Started |
| Log Aggregation stack (Grafana Loki / ELK) | ⬜ Not Started |

### Sprint 17 — Testing & Compliance Infra ⬜

| Task | Status |
|------|--------|
| Unit Tests (JUnit 5 + Mockito) per module | ⬜ Not Started |
| Integration Tests (`@SpringBootTest` + Testcontainers) | ⬜ Not Started |
| End-to-End Tests (REST Assured in `auth-e2e-tests`) | ⬜ Not Started |
| Contract Testing (`ApiResponse<T>` envelope compatibility) | ⬜ Not Started |
| Zero-Trust Master Service Hardening (M2M `client_credentials`) | ⬜ Not Started |
| SCIM 2.0 Directory Sync | ⬜ Not Started |
| Administrator Impersonation ("Login As") | ⬜ Not Started |
| Continuous Access Evaluation (CAE) | ⬜ Not Started |

---

## Phase 6 — Developer Experience & Extensibility

### Sprint 18 — DX & Webhooks ⬜

| Task | Status |
|------|--------|
| `docker-compose.yml` — "Authenza In A Box" local dev setup | ⬜ Not Started |
| Asynchronous Event Webhooks (`USER_REGISTERED`, `PASSWORD_CHANGED`) | ⬜ Not Started |
| Synchronous Logic Webhooks (pre-login `ALLOW`/`DENY` hooks) | ⬜ Not Started |
| Custom Claims Providers (HTTP hook during token minting) | ⬜ Not Started |
| M2M API Keys (static rotatable API keys) | ⬜ Not Started |
| JIT Legacy User Migration (validate against legacy API on login) | ⬜ Not Started |
| Professional Notification Engine (BYO-SMTP + provider marketplace) | ⬜ Not Started |

---

## Phase 7 — Enterprise Compliance & Privacy

### Sprint 19 — GDPR & Compliance ⬜

| Task | Status |
|------|--------|
| Total Data Portability API (full tenant DB export) | ⬜ Not Started |
| User-level Takeout API | ⬜ Not Started |
| Right to be Forgotten (PII anonymization) | ⬜ Not Started |
| Step-Up Authentication (re-auth for high-risk actions) | ⬜ Not Started |
| Production Readiness Pre-Flight checklist dashboard | ⬜ Not Started |

---

## Phase 8 — SaaS Monetization & Billing

### Sprint 20 — Billing Architecture ⬜

| Task | Status |
|------|--------|
| Subscription Tier Model (FREE / PRO / ENTERPRISE) in master DB | ⬜ Not Started |
| Premium Feature Enforcement (Spring Security filter → 403 on tier violation) | ⬜ Not Started |
| API Tiering (Invite-only vs Direct Provisioning per tier) | ⬜ Not Started |
| Usage-Based Metering (MAU tracking + Stripe integration) | ⬜ Not Started |

---

## Phase 9 — Global Identity & Multi-Account

### Sprint 21 — Multi-Account & Cross-Tenant ⬜

| Task | Status |
|------|--------|
| Unified Multi-Session Management (Google-style account switcher) | ⬜ Not Started |
| Global Identity Mapping Registry (master DB) | ⬜ Not Started |
| Cross-Tenant Account Discovery (privacy-preserving) | ⬜ Not Started |
| Global Logout (invalidate all sessions across tenants) | ⬜ Not Started |
| Cross-Subdomain Passkeys (configurable Relying Party ID) | ⬜ Not Started |

---

## Summary

| Phase | Sprints | Status | Progress |
|-------|---------|--------|----------|
| Phase 1 — Identity Lifecycle | Sprint 1–5 | ✅ Complete | ~100% |
| Phase 2 — Security & Auth | Sprint 6–10 | 🔄 In Progress | ~85% |
| Phase 3 — OAuth2 / OIDC | Sprint 11–12 | 🔄 In Progress | ~30% |
| Phase 4 — Tenant Customization | Sprint 13–15 | 🔄 In Progress | ~15% |
| Phase 5 — Interoperability | Sprint 16–17 | 🔄 In Progress | ~10% |
| Phase 6 — DX & Extensibility | Sprint 18 | ⬜ Not Started | 0% |
| Phase 7 — Compliance & Privacy | Sprint 19 | ⬜ Not Started | 0% |
| Phase 8 — Monetization | Sprint 20 | ⬜ Not Started | 0% |
| Phase 9 — Multi-Account | Sprint 21 | ⬜ Not Started | 0% |

---

## Immediate Next Tasks (Phase 2 Completion)

These 3 items complete Phase 2 before moving to Phase 3:

1. **MFA Self-Service Disable UI** — "Disable MFA" button + OTP confirmation modal in portal Security settings tab
2. **Admin MFA Reset — Backend** — `POST /api/v1/users/{userId}/mfa/reset` endpoint (admin-only, no OTP required)
3. **Admin MFA Reset — UI** — "Reset MFA" button in Admin Portal → User Management → User Detail screen

---

*Last updated: 2026-05-20 · Generated from [ROADMAP.md](ROADMAP.md)*
