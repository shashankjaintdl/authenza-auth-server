# Authenza - Project Roadmap

This consolidated roadmap outlines the step-by-step feature development to evolve **Authenza** from a foundational multitenant Spring Authorization Server into a comprehensive, enterprise-grade Identity and Access Management (IAM) product.

---

## 🇮🇳 India Go-To-Market (GTM) — Immediate Priorities

> These items are **prerequisite to acquiring the first 10 paying Indian customers**. India's B2B SaaS
> market (~25,000+ startups) needs developer experience, social login, and compliance-readiness
> — not SAML or enterprise federation. Resolve these before progressing to Phase 5+.

- **"Login with Google / GitHub" (Social Login — Developer Table Stakes):**
  - Implement `oauth2Login()` with Google and GitHub as the first two external Identity Providers.
  - Every Indian SaaS user expects social login. Without it, sign-up friction kills developer adoption.
  - *Maps to Phase 5 Federated Identity — pull forward to immediate priority.*

- **Tenant Admin Dashboard UI (Non-Technical Buyer Requirement):**
  - Indian startup founders and non-technical operators need a GUI to manage users, roles, MFA settings, and
    sessions — REST APIs alone will not close a deal with a business buyer.
  - Build a complete Tenant Admin Console (Angular, matches existing portal stack) covering:
    - User list, invite, deactivate, reset MFA, force password change
    - Role & permission management
    - Security settings (MFA policy, WebAuthn toggle, lockout policy)
    - Audit log viewer
  - *Maps to Phase 5 Tenant Admin Dashboard APIs — pull forward to immediate priority.*

- **"Authenza in a Box" — Local Dev Experience (Developer Acquisition):**
  - Indian developers will not pay before they can try locally. Provide a zero-config `docker-compose.yml`
    that spins up the full stack (Auth Server, IAM Service, Master DB, Tenant DB, Redis, Portal) in one command.
  - Publish a 5-minute quickstart guide on GitHub.
  - Include **First-Run Platform Setup** (Keycloak-style create super-admin once) — see Phase 6.
  - *Maps to Phase 6 — pull forward to immediate priority.*

- **DPDP Act 2023 Compliance Positioning:**
  - India's **Digital Personal Data Protection Act (2023)** mandates data localization and purpose limitation.
    Shared-cloud auth providers (Auth0, Okta, Firebase) become legally risky for Indian PII storage.
  - Authenza's physical DB-per-tenant isolation is a **native DPDP compliance answer** — make this explicit:
    - Add a `DPDP_COMPLIANCE.md` document to the repo explaining how tenant isolation satisfies DPDP requirements.
    - Add DPDP badge / compliance section to README and marketing site.
  - *This is the single highest-leverage sales enabler for Indian enterprise and fintech buyers.*

- **INR Pricing + Free Tier (Startup Adoption):**
  - Indian startups are price-sensitive. Offer a credible free tier (e.g., up to 1,000 MAU, 1 tenant, Authenza branding)
    so teams can adopt without budget approval.
  - Publish transparent INR pricing for PRO and ENTERPRISE tiers.
  - *Maps to Phase 8 Monetization — define India-specific pricing before Phase 8 implementation.*

---

## Phase 1: User Onboarding & Identity Lifecycle (IAM Core)
Establish robust management of user identities within their respective tenants.

- **User Registration & Verification:**
  - Build secure "Sign Up" APIs and UI pages.
  - Implement an email verification flow (e.g., using Spring Mail to send secure verification links or OTPs) before activating a user.
- **Account Recovery (Forgot/Reset Password):**
  - Develop self-service flows for resetting passwords securely using time-limited tokens.
- **Profile Management & Deletion:**
  - Expose APIs for users to update their details, change passwords from within the app, and view their own profile data.
  - Provide a self-service way for users to permanently delete their accounts to comply with data privacy requests (optionally toggled by Tenant Admins in Phase 4).
- **Admin User Invitation Flow (B2B Core):**
  - Implement an API allowing Tenant Admins to securely invite employees via email.
  - Generate one-time invitation tokens that redirect the invited user to a "Set Password" screen rather than general sign-up.
- **Initial Admin User Provisioning (The "Onboarding Bridge"):**
  - Automatically create a `User` record in the tenant's private database for the `ownerId` immediately after schema migration.
  - Assign the `TENANT_ADMIN` role to this user so the tenant creator has full platform and application access from day one.
  - This establishes the "local shadow" record required for Step 3 (OIDC) and Phase 9 (Multi-Account Management).
- **Strict Password Policies:**
  - Enforce customizable security constraints (min length, special characters, preventing common passwords) during sign-up and password reset.

---

## Phase 2: Enhanced Security & Authentication
Elevate platform security by protecting endpoints and validating identities.

- **Brute Force Protection & Account Lockout:**
  - Track failed login attempts.
  - Automatically lock accounts for a duration after `N` failed attempts to prevent credential stuffing.
  - **Conditional Captcha (Core Logic):** Implement backend attempt tracking, validation filters, and a default "Math Captcha" challenge that appears after 2 failed attempts to differentiate between human errors and bot attacks.
  - ✅ **Distributed Enforcement (Redis):** Attack counters are already securely managed via `StringRedisTemplate` ensuring account lockouts are consistent across all cluster instances.
- **Multi-Factor Authentication (MFA):**
  - Introduce TOTP (Time-Based One Time Password, e.g., Google Authenticator).
  - Enforce multi-step authentication flows within your custom login journey.
  - Two-level policy enforcement: tenant-wide (`mfa_required_for_all`) and per-user (`mfa_enabled`) flags.
  - Admin-initiated enrollment: tenant admin sets `mfa_enabled=true`; user is directed to QR code setup on next login.
- **MFA Self-Service Disable (Path 1):** *(Phase 2 — Next Sprint)*
  - Allow logged-in users to remove MFA from their own account via the portal's Security Settings page.
  - Backend: `POST /api/v1/users/{userId}/mfa/disable` (already implemented) — requires the user to submit their current valid TOTP code to prove device possession before the secret is cleared.
  - Frontend: Add a **"Disable MFA"** button + OTP confirmation modal in the user profile Security tab (similar to the existing OTP disable pattern in the admin portal's MFA component).
  - This transitions MFA from "mandatory" to "optional" for that user once the tenant-wide `mfa_required_for_all` is disabled.
- **Admin MFA Reset (Path 2):** *(Phase 2 — Next Sprint)*
  - Allow Tenant Admins to forcefully wipe a user's MFA state without needing their TOTP code — for recovery when a user loses their authenticator device and is permanently locked out.
  - Backend: Add `POST /api/v1/users/{userId}/mfa/reset` endpoint — admin-only, no OTP required, clears `mfa_secret` and sets `mfa_enabled = false` directly.
  - Frontend: Add a **"Reset MFA"** button in the Admin Portal → User Management → User Detail screen.
    - *(Full RBAC enforcement — only `ROLE_TENANT_ADMIN` can call this — deferred to Phase 4 when `@PreAuthorize` is fully wired.)*
- **MFA Endpoint Security Hardening:**
  - Require authentication before calling self-service MFA endpoints (`/mfa/setup`, `/mfa/confirm`, `/mfa/disable`).
  - Enforce user identity scoping: a user can only manage their own MFA secret, not another user's.
  - *(Full RBAC-based admin override — "admin can manage any user's MFA" — deferred to Phase 4.)*
- **Active Session Management:** ✅ *Implemented (Phase 2)*
  - Track active user sessions globally via a `sessions` database table and Redis revocation blacklist.
  - Add capabilities for users to view "Active Devices" and revoke (logout) specific remote sessions.
  - **Third-Party App Integration (API-First):** Customers building their own apps do **not** need to implement session tracking themselves. Authenza exposes the following ready-to-use REST endpoints callable with the user's OAuth2 access token:
    - `GET  /api/v1/users/{userId}/sessions` — list all active devices with IP, browser, last-active timestamp
    - `DELETE /api/v1/users/{userId}/sessions/{sessionId}` — revoke a specific device
    - `DELETE /api/v1/users/{userId}/sessions` — sign out from all devices simultaneously
  - **Integration pattern:** Authenticate user via OAuth2 → extract `user_id` claim from JWT → call session API with Bearer token → render device list in your UI.
  - *Prerequisite for safe third-party exposure:* `@PreAuthorize("principal.id == #userId or hasRole('TENANT_ADMIN')")` ownership guards must be enabled on `SessionController` (deferred to Phase 4 RBAC sprint) to prevent cross-user session access.
- **FIDO2 / WebAuthn (Passwordless):** ✅ *Implemented (Phase 2)*
  - Allow users to authenticate using physical device biometrics (Apple FaceID, TouchID, Windows Hello, YubiKeys).
  - Library: Yubico `webauthn-server-core` (`com.yubico:webauthn-server-core:2.5.4`).
  - Database: `webauthn_credential` table (V0.0.11 Liquibase migration) stores credential ID, COSE public key, and sign counter per user.
  - **Registration flow** (`/users/{userId}/passkeys/register/start` → `/finish`): Generates a challenge, verifies the browser's attestation, persists the public key credential.
  - **Authentication flow** (`/webauthn/authenticate/start` → `/finish`): Generates an assertion challenge, verifies the signed assertion, increments the sign-count for replay-attack protection.
  - **Passkey management** (`GET/DELETE /users/{userId}/passkeys`): Lets users view and remove their registered keys from the Security settings tab.
  - **TODO (Next Steps):** Integrate the `/webauthn/authenticate/finish` success response into the `auth-server-core` OIDC flow so a successful passkey assertion can complete an OAuth2 authorization code grant without a password form submission.
- ✅ **Known Security Gaps — `requires_password_change` Enforcement:** (Completed)
  - **Fixed: WebAuthn/Passkey Login Bypasses the Gate:** Added `isPasswordChangeRequired()` check in `WebAuthnBridgeController` after account-state validation to store pending attributes and redirect to the forced change page.
  - **Fixed: Flag Has No Effect on Already-Logged-In Users:** Embedded `password_change_required` claim in JWT customizer and enforced validation rejection in `RevokedSessionJwtValidator`.
- **Tenant-Level Rate Limiting:**
  - Protect infrastructure from noisy-neighbor DDoS attacks by throttling API requests per `tenant_id` (e.g., max 10,000 auth attempts per hour).
- **Strict Cross-Tenant Session Isolation & Configurable Switch Policy:**
  - *Reference:* See [CROSS_TENANT_SESSION_BLEED.md](CROSS_TENANT_SESSION_BLEED.md), [consolidated_tenant_switching.md](consolidated_tenant_switching.md) for full architectural details.
  - **Phase 1: Database Migration** ✅ *Completed*
    - Added `tenant_switch_policy` key to `tenant_settings` via Liquibase V0.0.11 changeset.
    - Default value seeded as `REQUIRE_PASSWORD` (Secure by Default — Zero Trust principle).
  - **Phase 2 (Step 1): Backend Enforcement — `REQUIRE_PASSWORD` Path** ✅ *Completed*
    - Created `TenantUserDetails` — a custom `UserDetails` principal that carries `tenantId` inside the authenticated Spring Security session context.
    - Updated `JdbcTenantUserDetailsService.loadUserByUsername` to return `TenantUserDetails` instead of Spring's built-in `User` object.
    - Updated `MultiTenantSecurityFilter` to detect cross-tenant session mismatch, read `tenant_switch_policy` from `TenantSettingsCache`, and on `REQUIRE_PASSWORD`: clear `SecurityContext`, invalidate HTTP session, redirect to `/{tenantId}/login`.
    - Fixed `TenantAwareRegisteredClientRepository` to return `null` (instead of throwing `AccessDeniedException`) when `client_id` does not exist — resolves `500 Whitelabel Error` on invalid client requests.
  - **Phase 2 (Step 2): Backend Enforcement — `AUTO_LOGIN` (G_SESSIONID / Master Token)** ⏳ *Deferred to Phase 9 — Intentional*
    - Validate Global Master Token (`G_SESSIONID`) cookie via `auth-master-service`.
    - Confirm global account membership (`OWNER` or `COLLABORATOR`) in the target tenant.
    - Silently mint a new Tenant B security session on valid token.
    - *Note:* Placeholder comment added in `MultiTenantSecurityFilter` to mark the extension point.
  - **Phase 3: Tenant Portal UI Integration** ⏳ *Not Started — Out of Current Scope*
    - Add `tenant_switch_policy` dropdown toggle in Admin Settings → Security Policies panel.
    - Expose a tenant/organization selector in the portal header showing all accessible tenants.
- **Adaptive / Risk-Based Authentication:**
  - Detect anomalous login attempts (e.g., impossible travel, unknown devices, or new IP addresses).
  - Automatically challenge the user with progressive MFA or send a "New Device Detected" security alert email.
- **Performance & Data Access Optimization (Sprint 10):**
  - Add Database Indexes for high-frequency low-latency queries (`user_session` on `user_id, revoked` and `authorization_id`; `webauthn_credential` on `user_id`).
  - Move WebAuthn and MFA challenges from per-pod `HttpSession` to Redis for horizontal scalability.
    - Migrate temporary authentication session states (like `PENDING_MFA_*` and `PENDING_PASSWORD_CHANGE_*` attributes) out of local `HttpSession` into a distributed Redis cache to prevent cross-pod routing failures.

---

## Phase 2.5: 🇮🇳 India GTM — Immediate Launch Readiness
Complete these in parallel with or immediately after Phase 2. No Phase 3+ dependencies.
These are **non-feature, high-leverage tasks** that unblock developer discovery and enterprise sales in India.

- **"Authenza in a Box" — Zero-Config Local Dev Setup:** *(2–3 days)*
  - Create a production-quality `docker-compose.yml` at the repo root that spins up the entire Authenza
    stack in a single command: `docker-compose up`
    - Services: `auth-server-core`, `auth-iam-service`, `auth-master-service`, `auth-notification-service`,
      MySQL (master DB + tenant DB pre-seeded), Redis, Nginx reverse proxy, Angular tenant portal.
  - Seed a default demo tenant (`demo.authenza.local`) with a pre-created admin user so developers
    can log in immediately without any manual setup.
  - Publish a **5-minute quickstart guide** (`QUICKSTART.md`) covering:
    1. Clone repo
    2. `docker-compose up`
    3. Open `http://localhost:4200`
    4. Log in with demo credentials
    5. Register your first OAuth2 client
  - *Indian developers will not evaluate a product they cannot run locally. This is the #1 developer acquisition lever.*

- **DPDP Act 2023 Compliance Positioning Document:** *(1–2 days)*
  - Create `DPDP_COMPLIANCE.md` at the repo root explaining how Authenza's architecture satisfies
    India's Digital Personal Data Protection Act 2023:
    - **Physical DB isolation** → each tenant's PII never co-mingles (satisfies data localisation)
    - **Self-hosted deployment** → data never leaves the customer's own infrastructure
    - **Purpose limitation** → tenant-scoped `oauth2_authorization` ensures tokens are tenant-bound
    - **Right to erasure** → tenant DB deletion removes all associated PII completely
  - Add a "DPDP Compliant Architecture" badge and summary section to `README.md`.
  - Add a "Why Indian Fintechs Choose Authenza" section linking to `DPDP_COMPLIANCE.md`.
  - *This document is your primary sales asset for Indian fintech, healthtech, and enterprise buyers.
    It turns your technical architecture into a legal compliance argument.*

---

## Phase 3: Deepening OAuth2 & OpenID Connect (OIDC) Integration
Expand upon the Spring Authorization Server framework standardizing the token issuing processes.

- **OpenID Connect (OIDF) Certification Base:**
  - Run the platform against the official OpenID Foundation Conformance Test Suite.
  - Target formal certification under the "Basic Provider", "Implicit", "Hybrid", and "Config" OpenID profiles.
  - *Strategic differentiator:* Achieving official OIDF Certification definitively proves to Enterprise InfoSec teams that Authenza strictly adheres to global identity standards, immediately removing any "homegrown security" stigma.
- **JWT Customization & Tenant Claims Context:**
  - Implement `OAuth2TokenCustomizer` to enrich Token claims.
  - Map the user's `tenant_id`, `roles`, and profile data directly into Access Tokens and ID Tokens.
- **The OIDC UserInfo Endpoint:**
  - Customize `OidcUserInfoAuthenticationProvider` or provide a custom mapper.
  - Expose user profile data through standard OIDC claims (`email`, `given_name`, `picture`) for clients that require user info endpoints natively.
- **Custom Multi-Tenant OAuth2 Consent Screen:**
  - Build an `/oauth2/consent` HTML screen tailored to the tenant's brand.
  - Require users to explicitly approve third-party applications requesting scopes (`email`, `profile`).
- **Automated JWKS (JSON Web Key Set) Rotation:**
  - Replace static RSA keys with a dynamic `JWKSource` backed by the master database. 
  - Build a background cron job to securely rotate JWT signing keys every 90 days.
- **PKCE & Token Revocation:**
  - Enforce Proof Key for Code Exchange (PKCE) for SPA and mobile clients.
  - Expose token revocation endpoints (RFC 7009) to allow explicit invalidation of access/refresh tokens.
- **Token Introspection Endpoint (RFC 7662):**
  - Enable the `/oauth2/introspect` endpoint on the authorization server so resource servers can verify token validity in real time, rather than relying solely on local JWT signature + expiry checks.
  - **Why this matters for session revocation:** When a session is revoked (e.g. via the Active Devices API), the `oauth2_authorization` row is deleted, blocking refresh-token reuse immediately. However, access tokens are short-lived JWTs validated locally — a revoked user can still call APIs until the JWT expires (typically 5–60 min). Introspection closes this gap: resource servers query the auth server on each request, and a revoked authorization returns `{ "active": false }` instantly.
  - **Trade-off:** Each API request incurs a network round-trip to the auth server (~1–5ms). Suitable for high-security endpoints (admin panels, billing, MFA management). Lower-sensitivity endpoints can continue using local JWT validation.
  - Switch resource servers from `oauth2ResourceServer(rs -> rs.jwt(...))` to `opaqueToken()` with introspection URI and client credentials to opt in.
  - *Prerequisite for Phase 5 Continuous Access Evaluation (CAE).*
- **Persistent OAuth2 Authorization Storage (`JdbcOAuth2AuthorizationService`):**
  - Replace the default `InMemoryOAuth2AuthorizationService` with a tenant-aware `JdbcOAuth2AuthorizationService` to persist authorization codes, access tokens, refresh tokens, and consent records across server restarts.
  - Add `oauth2_authorization` and `oauth2_authorization_consent` tables to the tenant Liquibase schema (new versioned migration).
  - Ensure the JDBC service is wired through the multi-tenant `RoutingDataSource` so each tenant's tokens are stored in their own isolated database.
  - This is a prerequisite for Refresh Token Rotation, Token Revocation, and Active Session Management.
- **Secure Refresh Token Rotation:**
  - Implement long-lived offline access tokens with strict rotation policies (a new token is issued per use) to balance UX with anti-theft security in SPAs.
- **Advanced OAuth2 Protocol Enhancements:**
  - **Pushed Authorization Requests (PAR - RFC 9126):** Prevent URL interception by having clients make secure back-channel POST requests to retrieve an opaque `request_uri` prior to browser redirection.
  - **Device Authorization Grant (RFC 8628):** Support input-constrained devices (e.g., CLI tools, Smart TVs) by allowing users to authorize on a secondary browser via a short code.
  - **Mutual TLS (mTLS) Client Authentication (RFC 8705):** Mandate X.509 client certificates for high-security B2B integrations instead of basic `client_secret` strings.
  - **Rich Authorization Requests (RAR - RFC 9396):** Enable complex JSON payload scopes for precise transactional approvals rather than simple string scopes.
- **Scale Preparation & Hardening:**
  - ✅ Migrate Brute Force tracking from in-memory `ConcurrentHashMap` to Redis with TTL to prevent cluster-wide lockout bypasses (Completed - currently using `StringRedisTemplate`).
  - Implement caching for `RegisteredClient` lookups using Caffeine with Redis Pub/Sub invalidation to reduce MySQL load on every token request.

---

## Phase 3.5: 🇮🇳 India GTM — Social Login & Admin Dashboard
Start immediately after Phase 3 completes. Depends on `JdbcOAuth2AuthorizationService` (Phase 3)
being stable. These two items are the **highest-ROI features for Indian customer acquisition**.

- **Social Login — Google & GitHub (`oauth2Login()`):** *(1–2 weeks)*
  - Implement OAuth2 social login as an **Identity Broker**: Authenza acts as the OIDC server for
    your tenants' apps, while Google/GitHub act as upstream external Identity Providers (IdPs).
  - **Multi-tenant routing logic (critical):** When a user hits `tenant-a.authenza.com` and clicks
    "Login with Google", the post-login flow must resolve which tenant they belong to:
    - Check if the Google email exists in `global_accounts`
      - **YES** → load `tenant_memberships`, issue tenant-scoped token, log them in
      - **NO** → create new `global_accounts` entry, assign to active tenant, proceed
  - **Providers to implement (in order):**
    1. **Google** — highest adoption in Indian SaaS market (Google Workspace dominates)
    2. **GitHub** — essential for developer-facing products and open source ecosystems
    3. **Microsoft** — needed for enterprise tenants on Office 365 / Azure AD (add in Phase 5)
  - **Prerequisites from Phase 3:**
    - `JdbcOAuth2AuthorizationService` must be live (tokens must survive server restarts)
    - JWT customizer must inject `tenantId` into tokens issued after social login
    - PKCE must be enforced (required by Google and GitHub OAuth2 apps)
  - *Every Indian SaaS user expects "Login with Google". Without it, sign-up friction kills adoption.*

- **Tenant Admin Dashboard UI — Full Console:** *(3–4 weeks, parallel to social login)*
  - Build a complete Angular-based Tenant Admin Console on top of `auth-iam-service` APIs.
  - **Non-technical Indian startup founders need a GUI** — REST APIs alone will not close a sale
    with a business buyer who is evaluating Authenza against Firebase or Clerk.
  - **Required screens:**
    - **Users** — list, search, invite by email, deactivate, force password change, reset MFA
    - **Roles & Permissions** — create roles, assign permissions, assign roles to users
    - **Security Settings** — MFA policy toggle, WebAuthn enable/disable, lockout threshold, session TTL
    - **Active Sessions** — view all active user devices, revoke individual sessions or all sessions
    - **Audit Log** — searchable log of `LOGIN_SUCCESS`, `PASSWORD_CHANGED`, `MFA_ENABLED`, etc.
    - **OAuth2 Clients** — list registered clients, rotate secrets, manage redirect URIs
  - *This is the product that non-technical buyers evaluate. It must feel premium — not an afterthought.*

---

## Phase 4: Tenant Customization & Organization
Enable complex B2B scenarios and tenant-specific configuration.

- **Role-Based Access Control (RBAC):**
  - Introduce `Roles`, `Permissions`, and `Groups` entities within the tenant database schema.
  - Propagate these roles as scopes/claims on minted tokens.
  - Build a **custom `UserDetails`** principal that carries `userId` (database PK) alongside `username`, enabling precise ownership checks in controllers.
  - Add `@PreAuthorize` expressions for MFA management: users can only manage their own MFA; `ROLE_ADMIN` or `ROLE_TENANT_ADMIN` can manage any user's MFA.
  - Role-gate the tenant-wide MFA policy endpoint (`PUT /api/v1/settings/mfa-policy`) to `ROLE_TENANT_ADMIN` only.
  - **Enable Session API Ownership Guards:** Uncomment `@PreAuthorize("principal.id == #userId or hasRole('TENANT_ADMIN')")` on all three `SessionController` endpoints (`GET/DELETE /users/{userId}/sessions`). Currently commented out — without this, any authenticated user can list or revoke sessions belonging to any other user. This is a **required prerequisite** before advertising the session API as a safe third-party integration point.
  - **Tenant Portal UI & Backend Endpoint Segregation (In Progress):**
    - Enable Spring Security `oauth2ResourceServer` in `auth-iam-service` to validate JWTs.
    - Add `@PreAuthorize("hasRole('TENANT_ADMIN')")` restrictions on `UserController`, `TenantSettingsController`, etc.
    - Implement an Angular Route Guard (admin.guard) on the frontend to dynamically hide Dashboard/Users/Settings views from normal users.
    - **Auth Server Client Access Control:** Implement a backend interceptor in `SecurityConfig` to strictly block token issuance for the `authenza-tenant-portal` client if the user lacks the `TENANT_ADMIN` role, redirecting back with an `error=access_denied` parameter for a clean UX.
- **Authentication → Database (read-only store status)** ⏳ *Phase 4 portal polish — thin Auth0-like page, not multi-connection yet:*
  - Portal page under Authentication → Database showing the **current tenant identity store**: hosted vs BYOD, engine (MySQL/Postgres/MariaDB), connection status, last preflight (if any).
  - **Not** Auth0 “Create DB Connection” / multiple stores — that is Phase 5. This page only surfaces what BYOD + hosted provisioning already configured.
  - Differentiate clearly from BYOD: BYOD answers “where does this tenant’s Authenza DB live?”; this page only **displays** that answer.
- **Tenant Environment Tagging (Dev/Prod):**
  - Add an `environment` classification (Enum: `DEVELOPMENT`, `STAGING`, `PRODUCTION`) to the tenant model in the master database.
  - Implement "Production Safety Rails" in the Tenant Portal (e.g., confirmation modals for destructive actions in PROD).
  - Enable environment-specific logic, such as prepending "[DEV]" to system emails and applying stricter rate limits on non-production tenants.
  - **Master Service Network Isolation (Do some validation before proceed):**
    - Explicitly maintain `.permitAll()` in `auth-master-service` core controllers (`GlobalAccountController`, `TenantRegistrationController`).
    - Enforce security natively using VPC Network level isolation (internal routing / ingress blocks) to speed up Phase 4 delivery without M2M overhead.
- **Enterprise Hierarchy (Sub-departments / Organizations):**
  - Implement an `organizations` table inside the tenant database to support sub-branches (e.g., Acme NYC, Acme London).
  - **Reason 1 (RBAC):** Before you have departments, you need a powerful Role-Based Access Control (RBAC) system. You'll want roles like "Department Manager" who can only see users in "Acme NYC" but not "Acme London".
  - **Reason 2 (Context):** By this phase, your core login flow is rock solid, so adding an optional `org_id` context to the JWT is a simple upgrade.
- **Dynamic Client Management System:**
  - Wrap the `RegisteredClientRepository` with APIs for tenant administrators.
  - Allow tenants to self-serve dynamic creation of their own OAuth2 clients (M2M tokens, SPAs) and rotate client secrets.
- **Shared Session Storage (Redis Persistence):**
  - Enable High Availability (HA) by moving Spring Security's `HttpSession` storage from local RAM to a shared Redis cluster. This ensures that users stay logged in even if the backend server instance restarts or if traffic is routed to a different node.
- **Unrestricted Tenant Branding & Custom Domains:**
  - Store tenant-specific UI Theme objects securely in the database (e.g., a JSON `branding_settings` column holding `logoUrl`, `primaryColor`, and `termsUrl`).
  - **Dynamic Rendering Pipeline:** Intercept the `tenantId` from the request, fetch the specific tenant's branding profile from the database, and dynamically inject the branding variables into the Thymeleaf models so custom logos and themes appear natively on `login.html` and `consent.html`.
  - **Custom Domains (`auth.theircompany.com`):** Allow tenants to serve their login pages securely from their own fully branded subdomains, completely white-labeling the Authenza engine instead of just swapping a logo.
- **Hierarchical Feature Toggles (Tenant & Client Level):**
  - Implement a hybrid security toggle system for capabilities like `allow_public_registration`.
  - Dynamically hide or show features like the "Create Account" option on the UI, and restrict the API accordingly based on tenant rules.
  - **Tenant Level:** Acts as a global master switch to lock down an entire workspace if necessary (e.g., strict internal B2B environments).
  - **Client Level:** Store granular toggles directly in OAuth2 `ClientSettings` (e.g., allowing "Create Account" on a public mobile app, but disabling it on an internal employee portal).
  - **Concrete Example:** Implement the toggle for *Self-Service Account Deletion* (defined in Phase 1) here to allow admins to control user-data exit flows.
- **Global System Configuration Architecture (`global_system_config` vs `tenant_settings`):**
  - Implement a multi-tier configuration architecture separating platform super-admin governance from tenant customer preferences:
    1. **Global Master Tier (`auth-master.global_system_config`):**
       - **Scope:** Platform Owner / Super Admin.
       - **Purpose:** Platform-wide feature gates, global kill switches (e.g., emergency disable of WebAuthn, social IdP, or SMS provider during zero-day incidents), platform default security fallbacks, and maintenance mode.
       - **Zero-Migration Default Pattern:** New platform default settings are seeded once in the master database, automatically inherited by all existing tenants without running DML scripts across thousands of isolated tenant databases.
    2. **Tenant Tier (`{tenant}.tenant_settings`):**
       - **Scope:** Tenant Owner / B2B Customer Admin.
       - **Purpose:** Customer-specific policies, custom branding, local lockout thresholds (`brute_force_max_attempts`), session inactivity timeouts (`session_ttl_days`), and switch policies (`tenant_switch_policy`).
    3. **Resolution Hierarchy:**
       $$\text{Global Kill Switch (Override)} \longrightarrow \text{Tenant Local Setting} \longrightarrow \text{Global System Default} \longrightarrow \text{Code Fallback}$$
- **Tenant-Selectable Captcha (Advanced):** 
  - Expose UI settings for Tenant Admins to choose their preferred security challenge (Math, Google reCAPTCHA, or Cloudflare Turnstile).
  - Update the dynamic login rendering pipeline to inject the chosen captcha provider based on tenant configuration.
- **Dynamic Registration Schemas & Custom User Attributes:**
  - Facilitate dynamic onboarding by allowing administrators to configure custom fields (e.g., mobile, company code, employee ID) per client/tenant using a JSON schema.
  - **Schema Validation Workflow:** 
    - The JSON configuration specifies if a field is `required: true` vs `required: false`. 
    - The UI dynamically generates HTML inputs with the `required` attribute based on this schema.
    - The Backend (`UserService`) strictly enforces this schema by validating the incoming JSON payload against the Tenant's mandated rules, preventing API bypass.
  - Store incoming flexible values natively in a JSON database schema format and dynamically inject them into minted JWTs via OAuth2 token customizers.
- **White-Labeled Email Templates & BYO-SMTP:**
  - Ensure the auth experience is fully branded up to the user's inbox.
  - Allow tenants to upload custom HTML email templates (Welcome, OTP, Password Reset) and optionally configure their own SMTP server credentials.
- **Internationalization (i18n) & Localization:**
  - Adapt the custom login pages, consent screens, and emails natively to the user's `locale` parameter or browser settings to elegantly support global B2B workforces.

---

## Phase 5: Interoperability & Observability
Mature the product to fit into wider enterprise ecosystems.

- **Rename Platform Tenant `system-admin` → `platform`** ⚠️ *Phase 5 must-do — before first paying / self-serve prod customers:*
  - **Why:** `/system-admin/login` and `/system-admin/register` look like internal ops URLs; `platform` is clearer for a first-party control-plane workspace. Does **not** replace customer onboarding — new tenant owners still use global signup → `/onboarding` → `/{their-tenant}/dashboard`.
  - **When:** Dedicated migration sprint in Phase 5, **before prod customer launch**. Do not drive-by rename mid-feature work; do not defer until after live OIDC clients and bookmarks accumulate.
  - **Scope:**
    1. Master DB: rename `tenants.tenant_id` (and memberships) `system-admin` → `platform`.
    2. Configs / portal defaults / deactivate lock / e2e fixtures / env files.
    3. Temporary redirects from `/{old}/…` → `/{platform}/…` for one release, then remove aliases.
    4. Prefer bundling with **Production Database Separation** below (same Phase 5 cut-over window).
  - **Out of scope here:** Renaming to `common` / `shared` (rejected — ambiguous with shared-schema).

- **Production Database Separation (platform / former system-admin Isolation):**
  - Currently the platform tenant (`system-admin`, to be renamed `platform`) shares the `auth-master` database because `DataBaseInitializer` registers it with the same JDBC URL (dev shortcut).
  - Separate `platform` into its own dedicated database (`jdbc:mysql://.../platform`) to cleanly isolate tenant registry data from tenant user/IAM data.
  - Add `app.system-tenant.url` (or `app.platform-tenant.url`) to `auth-master-service/application.yaml` and update `DataBaseInitializer` to use it when provisioning the platform tenant.
  - Migrate existing `application_user`, `oauth2_registered_client`, `roles`, `tenant_settings`, and all IAM tables out of `auth-master` into the dedicated database before go-live.
  - Each tenant (`platform`, `acme-corp`, etc.) will then have a fully isolated database, with `auth-master` containing only the tenant registry table.

- **Liquibase Changelog Restructuring (initialization / migration split):**
  - Currently all Liquibase changesets for tenant databases live under `migration/tenant/V0.0.1` through `V0.0.7`, including the initial schema creation and seed data.
  - Activate the `initialization/` folder (currently a Phase 5 placeholder) by moving the baseline schema and seed data out of versioned migrations:
    - Move `migration/tenant/V0.0.1/ddl.xml` (base table creation) → `initialization/ddl.xml`
    - Move `migration/tenant/V0.0.5/dml.xml` (default roles/permissions seed) → `initialization/dml.xml`
  - Wire `initialization/db.changelog-initialization.xml` into `TenantProvisioningService` so it runs first on every new tenant database, followed by `migration/tenant/db.changelog-tenant.xml` for incremental changes.
  - Update the `DATABASECHANGELOG` table on all existing tenant databases to reflect the new file paths, preventing Liquibase checksum failures during the cut-over.
  - End state: `initialization/` = one-time baseline; `migration/` = incremental versioned changes only.

- **Tenant Settings (Edit Safe Fields + Deactivate)** 🔄 *In progress / portal + master APIs:*
  - Portal page (`/:tenantId/tenants/settings`) to edit account type, org name, company size, and default flag.
  - Soft-deactivate / reactivate owned tenants (platform tenant locked — today `system-admin`, after Phase 5 rename `platform`). No hard delete or JDBC edits in this phase.
  - Master APIs: `GET/PUT /api/v1/admin/tenant/{tenantId}`, `POST .../deactivate`, `POST .../activate`, working `POST .../default`.

- **Async Tenant Provisioning Job + Real Migration Progress** ⏳ *Backlog — trigger when create p95 >10–15s or users abandon mid-create:*
  - Today `POST` tenant registration is synchronous: master row → Liquibase `update` → seed admin → HTTP response. Portal shows **staged** status (validate → prepare → apply schema → finish), not changeset-accurate %.
  - Evolve to async create when latency or BYOD cloud DBs justify it:
    1. **Job API:** `POST /admin/tenant/register` returns `202 Accepted` + `jobId` (or `tenantId` + `PROVISIONING` status); run provision on a worker thread/queue.
    2. **Status API (or SSE):** `GET /admin/tenant/jobs/{jobId}` reports stage + optional Liquibase progress (`appliedChangesets / totalChangesets`, current changeset id, error).
    3. **Liquibase reporting:** Attach a listener / changelog inventory so progress is real, not heuristic timers.
    4. **Portal:** Poll or stream job status; survive refresh; show failure/retry; optional “we’ll notify when ready” for long BYOD creates.
    5. **Cleanup:** Timeouts and rollback/mark-`INACTIVE` for half-provisioned tenants (master row without usable schema).
  - **Prerequisite / related:** Liquibase initialization vs migration split (above); BYOD preflight remains a separate non-mutating check before the job starts.
  - **Do not build early:** Keep blocking create + staged UX until metrics show sustained slow creates.

- **Database Connections (Auth0-style identity stores)** ⚠️ *Phase 5 must-do for Auth0-parity Authentication → Database:*
  - **Differs from BYOD:** BYOD (already shipped) configures the **tenant’s Authenza database** at provision time. Database Connections are **named login sources** an application can enable (Authenza username/password store, legacy SQL user table, LDAP/AD, etc.).
  - **Product surface:** Portal Authentication → Database with list + “Create DB Connection” (Auth0-shaped), not only a read-only status card (Phase 4).
  - **Scope:**
    1. Connection model per tenant (`name`, `type`, `enabled`, config JSON).
    2. Default connection = Authenza native user store (hosted or BYOD-backed — same IAM tables).
    3. Enable/disable connections **per OAuth2 application** (which apps may authenticate against which store).
    4. Optional enterprise types: legacy credential check, LDAP/AD (align with Phase 6 JIT / Phase 7 SAML as needed).
    5. Passkey / username-password toggles per connection where applicable.
  - **When to prioritize:** Multi-app tenants, legacy migration, or deals that require an Auth0-like Database Connections screen. Do not block Phase 1–4 or current BYOD on this.
  - **Prerequisite:** Phase 4 read-only Database status page; Dynamic Client Management (Phase 4) helps app↔connection wiring.

- **Federated Identity (Identity Brokering):**
  - Transform Authenza into an Identity Broker by adding standard `oauth2Login()`.
  - Let tenants configure external Identity Providers (Google, GitHub, Microsoft Entra ID) so they can enforce Single Sign-On (SSO) with their active directories.
- **Tenant Admin Dashboard APIs:**
  - Prepare `auth-iam-service` routes to serve an eventual React/Angular Tenant Admin Frontend used to manage tenant users and settings.
- **Unified Audit Logging Framework:**
  - Intercept and safely log sensitive events (`LOGIN_SUCCESS`, `PASSWORD_CHANGED`, `CLIENT_CREATED`).
  - Funnel logs to a data store suitable for compliance monitoring and usage tracking.
- **Centralized Application Logging & Tracing:**
  - Implement Distributed Tracing (Micrometer Tracing / W3C Trace Context) to automatically inject and propagate `traceId` and `spanId` across all microservices.
  - Deploy an automated Log Aggregation stack (e.g., Grafana Loki + Promtail, or ELK) to centrally search, correlate, and monitor multi-module system logs in production.
- **Automated Testing & Quality Assurance (`auth-e2e-tests`):**
  - **Unit Tests (JUnit 5 + Mockito):** Write isolated tests for services, repositories, and utility classes within each module (`auth-iam-service`, `auth-notification-service`, etc.).
  - **Integration Tests (`@SpringBootTest` + Testcontainers):** Boot individual services against real MySQL and Redis containers (via Testcontainers) to validate database interactions, Liquibase migrations, and Redis Pub/Sub event publishing.
  - **End-to-End Tests (REST Assured in `auth-e2e-tests`):** Orchestrate full cross-service flow tests (User Registration → Redis Event → Notification Listener → Email Sent) by hitting live HTTP endpoints of all running services. Generate HTML test reports for CI/CD pipelines.
  - **Contract Testing:** Validate that the `ApiResponse<T>` envelope and event DTOs (`EmailVerificationEvent`) remain backward-compatible across all modules.
- **Zero-Trust Master Service Hardening (SOC2 Compliance readiness) (Do some validation before proceed):**
  - Upgrade `auth-master-service` from simple VPC network isolation (Phase 4) to formal OAuth2 Resource Server protocols.
  - Enforce M2M `client_credentials` validation via `auth-server-core` tokens using a `system-admin` scope strictly for administrative microservice routes (`/admin/tenant`, `/admin/accounts/register`).
- **SCIM 2.0 Directory Sync Provisioning:**
  - Expose API endpoints compliant with the System for Cross-domain Identity Management (SCIM) standard.
  - Enable massive enterprise tenants to automatically sync (create/update/disable) their employees directly from Azure AD, Okta, or Google Workspace into Authenza.
- **Administrator Impersonation ("Login As"):**
  - Provide a highly secure API for Support Teams or Tenant Admins to briefly assume the identity of a specific user to troubleshoot issues, generating a restricted JWT without ever requiring the user's password.
- **Continuous Access Evaluation (CAE):**
  - Publish real-time "Critical Event Streams" (e.g., `user_disabled`, `password_reset`) to downstream Resource Servers.
  - Empower microservices to instantly drop access tokens upon critical security events rather than waiting for standard the JWT 1-hour expiry window.
  - *Requires Token Introspection (Phase 3) to be enabled on resource servers — without it, access tokens remain valid locally until expiry even after a critical security event.*
- **Scale-Out Policy Caching (Caffeine + Redis Pub/Sub):**
  - Optimize the performance of tenant-specific security policies (MFA, Session TTL) by introducing ultra-low-latency local Caffeine caches.
  - Implement a Redis Pub/Sub invalidation mechanism to "broadcast" setting changes from the `auth-iam-service` to all `auth-server-core` instances, ensuring instant policy updates while maintaining near-zero database load during logins.
- **Token Generation Optimizations (Zero-Latency Customizer):**
  - Eliminate all synchronous database queries from `OAuth2TokenCustomizer` to prevent database bottlenecks during high-volume token minting and silent renewals.
  - Decouple `user_session` linking by moving the `UPDATE` query into an asynchronous `@EventListener(OAuth2TokenCreatedEvent.class)` so it executes in a background thread, removing it from the critical path of the HTTP response.
- **Production Infrastructure Scale-Out:**
  - Transition from standalone Redis to Redis Cluster for High Availability (HA).
  - Tune HikariCP connection pools specifically for the multi-tenant `RoutingDataSource` to handle peak concurrency.
  - Evaluate DB read replicas to separate read/write load as tenant user bases grow.
  - *Architecture Decision:* Distributed In-Memory Data Grids (IMDG) like TIBCO, Hazelcast, or Apache Ignite are explicitly excluded. MySQL + Redis + Caffeine is the proven, sufficient stack for scaling to 10M+ users in this domain.
- ✅ **Rate Limiting Resilience, Transparency & Bug Fix:**
  - **Fixed Naive Tenant ID Extraction:** Excluded system prefixes (`/api`, `/actuator`, etc.) to prevent false-positive global throttling.
  - Enhanced `TenantRateLimitFilter` with graceful degradation (fail-open) so Redis outages do not cause auth system downtime.
    - *Future Improvement:* Evolve the fail-open fallback to support local in-memory rate limiting (JVM heap mapping) if Redis is unavailable, preventing complete limit bypass.
  - Injected standard rate-limit HTTP headers (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`) into API responses for client transparency.

---

## Phase 5.5: Hyperscale Architecture & High-Throughput Scaling (1M+ RPS Readiness)
Architectural evolutions to scale Authenza from tens of thousands of requests to millions of requests per second (1M+ RPS), aligning with hyperscale identity infrastructure (Auth0, Okta, Cloudflare Access).

- **Data Plane vs. Control Plane Decoupling (Stateless Edge Verification):**
  - Decouple the low-volume authentication/login flow (Control Plane) from high-volume token validation (Data Plane).
  - Distribute public signing keys via `/.well-known/jwks.json` to API Gateways (Envoy, Kong, AWS API Gateway, Cloudflare Workers).
  - Gateways verify RS256/Ed25519 JWT signatures completely in RAM (`<0.05ms` CPU math) with zero database queries and zero network hops to the auth server, handling 1,000,000+ API requests per second at the edge.

- **Java 21 Virtual Threads & Non-Blocking Concurrency (Project Loom):**
  - Enable `spring.threads.virtual.enabled=true` across all Spring Boot 3 microservices.
  - Replace Tomcat's 200 OS worker thread limit with lightweight JVM virtual threads, allowing each service instance to handle 50,000+ concurrent open connections during I/O wait times without thread starvation.

- **In-Memory Distributed OAuth2 Storage (Redis Cluster Migration):**
  - Replace `JdbcOAuth2AuthorizationService` (which performs synchronous MySQL ACID writes on every token grant) with an in-memory `RedisOAuth2AuthorizationService` backed by Redis Cluster.
  - Enforce access tokens as completely stateless JWTs (0 database footprint).
  - Store authorization codes (5-minute TTL) and refresh tokens (30-day TTL) in Redis RAM with automatic key expiry.

- **Multi-Tenant Connection Multiplexing & Pool Scaling:**
  - Introduce database proxying (ProxySQL / AWS RDS Proxy / Vitess) to prevent MySQL connection exhaustion as tenant count grows into thousands.
  - Multiplex thousands of tenant databases over a compact, persistent pool of physical database connections.
  - Implement dynamic on-demand connection pool creation with LRU eviction for dormant tenant data sources in `TenantRoutingDataSource`.

- **Multi-Tier L1/L2 Caching for User & RBAC Lookups:**
  - Eliminate the 5-table relational SQL JOIN in `JdbcTenantUserDetailsService` on repeated requests.
  - **L1 Cache (Caffeine):** In-memory JVM heap cache (`<0.001ms`) for hot user roles and tenant settings.
  - **L2 Cache (Redis Cluster):** Distributed cache synchronized via Redis Pub/Sub invalidation events when permissions are updated.

- **Distributed Real-Time Revocation (Bloom Filters & Redis Sets):**
  - Manage immediate session and token revocations across millions of concurrent users using distributed Redis Revocation Sets and Bloom filters.
  - Resource servers and API Gateways check revocation status in sub-millisecond time without querying relational databases.

---

## Phase 6: Developer Experience (DX) & Extensibility
Empower tenant developers to integrate deeply with the Authenza platform.

- **Seamless Local Development ("Authenza In A Box"):**
  - Provide a highly polished `docker-compose.yml` and unified CLI experience so a company's developers can spin up the entire Authenza stack (Master DB, Redis, Auth Server, and Portal) locally on their laptop in seconds.
  - *Strategic differentiator:* Developers cannot test Auth0 or Azure AD effectively without a live internet connection and navigating complex cloud sandbox environments. Authenza must run locally as flawlessly as it runs in production.

- **First-Run Platform Setup Wizard (Keycloak-style)** ⚠️ *Phase 6 — required for self-hosted / Authenza in a Box; not the SaaS customer path:*
  - **Problem today:** Platform super-admin is seeded from config/`DataBaseInitializer` (`app.system-admin.*`). Fine for engineers; weak for first-open UX after `docker compose up`.
  - **Goal:** On first application open, if the platform is uninitialized, show a one-time welcome page to **create the platform super-admin** (email + strong password), then mark the install initialized and never show that page again.
  - **Scope:**
    1. Detection API: e.g. `GET /api/v1/admin/setup/status` → `{ initialized: false }`.
    2. Setup UI (auth-server or portal): create super-admin + ensure platform tenant (`system-admin` / later `platform`) exists.
    3. Hard lock after success: route returns 404/410 or redirects to login; reject further setup POSTs.
    4. Optional: disable password bootstrap from env when wizard mode is enabled (`app.setup.wizard-enabled=true`).
  - **Explicitly out of scope:** Using this wizard for normal tenant owners. SaaS customers remain **global signup → `/onboarding` → `/{their-tenant}/dashboard`**. Never send them to “register as super user.”
  - **Ordering:** Prefer after Phase 5 **platform rename** + **platform DB separation** so the wizard creates `platform` cleanly; can ship a thin version earlier for docker-compose if GTM pulls “Authenza in a Box” forward.

- **Event Webhooks (Asynchronous):**
  - Fire asynchronous HTTP callbacks to tenant-configured URLs upon critical lifecycle events (`USER_REGISTERED`, `PASSWORD_CHANGED`, `TENANT_DELETED`, `SESSION_CREATED`, `SESSION_REVOKED`) for downstream auditing and data synchronization.
  - **Session webhooks** eliminate the need for third-party apps to poll the session list API — they receive a push notification when a device logs in or is revoked, enabling real-time UI updates.
- **Synchronous Logic Webhooks (The "Plugin Killer"):**
  - Instead of forcing developers to write custom Javascript plugins inside the Auth engine (like Auth0 Actions), Authenza will pause during critical flows (e.g., `pre-login`) and make a synchronous HTTP call to the tenant's own external REST API.
  - The tenant's API returns `{ "action": "ALLOW" }` or `{ "action": "DENY", "reason": "Invoice unpaid" }`.
  - *Strategic differentiator:* Completely eliminates proprietary vendor lock-in. Developers can customize login workflows using any programming language hosted on their own infrastructure.
- **Custom Claims Providers:**
  - Introduce an extensibility mechanism where tenant admins can register HTTP hooks that Authenza invokes during token minting to dynamically inject external claims.
- **Machine-to-Machine (M2M) API Keys:**
  - Provide static, rotatable `API_KEY` capabilities for simple scripting and service integrations that don't require full OAuth2 flows.
- **Just-In-Time (JIT) Legacy User Migration:**
  - Facilitate frictionless onboarding for massive B2B tenants migrating from legacy identity systems.
  - Intercept login workflows to validate user passwords against the tenant's exact legacy API in real-time, seamlessly securing and migrating the user into the native Authenza database.
- **Professional Notification Engine (BYO-SMTP):**
  - **Provider Marketplace Architecture:** Support standard providers (Amazon SES, SendGrid, Gmail, Microsoft 365) via a plugin-based strategy pattern.
  - **Bring-Your-Own-SMTP (BYO-SMTP):** Allow tenants to securely provide their own credentials, enabling them to maintain their own IP reputation and email deliverability.
  - **Zero-Knowledge Credential Management:** Use Per-Tenant Envelope Encryption to secure SMTP passwords at rest using a Platform Master Key.
  - **The "Auth0+" Experience:**
    - **In-Browser Sandbox:** Provide a "Test Connection" UI for real-time validation of SMTP handshakes and DKIM/SPF health checks.
    - **Regional Binding:** Allow tenants to choose specific geo-regions (e.g., AWS Frankfurt vs. N. Virginia) for email dispatch to satisfy local data residency requirements.
    - **Auto-Failover Logic:** Automatically route critical system emails through the "Platform Default" account if a tenant's custom SMTP account becomes unreachable.
  - **Liquid/Handlebars Templating:** Empower tenants to design and host their own HTML email templates within their isolated storage.

---

## Phase 7: Enterprise Compliance & Privacy
Meet stringent global and Indian data privacy requirements (GDPR, CCPA, DPDP Act 2023).

- **Total Data Portability (The Anti-Lock-in Guarantee):**
  - Implement self-service APIs allowing a tenant administrator to request a complete 100% database export (users, roles, settings, logs) down to the SQL/JSON level at the click of a button.
  - Implement user-level "Takeout" APIs for individual data exports.
  - *Strategic differentiator:* Okta and Auth0 intentionally lock data in to make migrating away extremely difficult. Because Authenza guarantees Physical Isolation (Database-per-tenant), providing a raw, unadulterated data dump is both secure and trivial.
- **Right to be Forgotten (Account Anonymization):**
  - Build a secure workflow to completely scramble or purge a user's PII across a tenant's database tables rather than simply disabling the account.
- **DPDP Act 2023 (India) — Full Compliance Audit:**
  - Conduct a formal audit of all data flows against India's Digital Personal Data Protection Act 2023.
  - Implement Data Principal consent management APIs (purpose-limited consent, withdrawal of consent).
  - Add a Data Fiduciary dashboard for tenant admins to view and manage consent records.
  - Ensure all cross-border data transfer (if any) complies with DPDP localisation requirements.
  - Publish a self-attestation compliance report for Indian enterprise procurement processes.

- **SAML 2.0 Identity Provider Support (Enterprise — On-Demand Only):**
  - Add SAML 2.0 SP (Service Provider) capability so large Indian enterprises with existing Active Directory /
    ADFS / Azure AD infrastructure can federate their employees into Authenza without re-registration.
  - **Trigger:** Implement only when a paying enterprise customer makes it a contract blocker.
  - **Scope:** SAML metadata exchange, signed assertions, attribute mapping to Authenza roles.
  - *Note: OIDC federation (Phase 5) covers 80% of this use case for modern IdPs. SAML is only required
    for legacy ADFS / on-premises AD environments common in Indian banks and government departments.*

- **Step-Up Authentication:**
  - Require re-authentication or progressive MFA prompts only when users attempt high-risk actions (e.g., updating billing, changing passwords).
- **Production Readiness Checklist:**
  - Implement an automated "Pre-Flight" validation dashboard in the Tenant Portal.
  - Require/Validate critical production settings before allowing a tenant to toggle to `PRODUCTION` mode:
    - Custom SMTP configuration (BYO-SMTP).
    - Custom Domain verification.
    - Strong Password Policy enforcement.
    - MFA enrollment for all administrative accounts.
    - Production-grade JWT signing (RS256).

---

## Phase 8: SaaS Monetization & Billing Architecture
Implement the logic to map identity functionality directly to business revenue tiers, ensuring the platform scales profitably.

- **Subscription Tier Modeling:**
  - Define canonical billing plans mapping to the master `tenant` database:
    - **`FREE` (The Entry Hook):** Provide generous MAU limits with core API access, but enforce Authenza branding ("Secured by Authenza") and block premium capabilities to drive startup adoption while securing free marketing.
    - **`PRO` (The Core Revenue Driver):** Monetize established businesses by unlocking crucial Phase 4 Customizations (White-Labeling, Custom Domains, Custom Emails) and charging micro-transactions for overage users.
    - **`ENTERPRISE` (The Big Whales):** Secure massive contracts by unlocking critical B2B compliance features (SCIM 2.0 Directory Sync, SLAs, Admin Impersonation) demanded by corporate security teams.
- **Premium Feature Enforcement:**
  - Implement Spring Security filters/aspects to dynamically intercept REST calls. Return `403 Forbidden` if a tenant attempts to access APIs restricted by their active `ServicePlan` (e.g., blocking a FREE user from accessing the Phase 6 Webhook APIs).
  - **API Tiering Strategy (Invite vs. Create):**
    - **Free / Standard Tier:** Tenants only have access to the UI-friendly `POST /api/v1/users/invite` flow. Admins supply an email, and the system securely manages password creation via a user-facing link.
    - **Enterprise Tier:** Unlock the `POST /api/v1/users/create` "Direct Provisioning" API. This allows corporate IT departments to automate account creation by directly passing temporary passwords, bypassing the email invitation step completely.
- **Tiered API Rate Limiting & Throttling:**
  - Introduce custom API limits based on a tenant's billing tier to monetize API usage.
  - **Database Level:** Add `custom_burst_limit` and `custom_sustained_limit` to the `tenant_settings` or `subscriptions` table.
  - **Filter Level:** Update `TenantRateLimitFilter.java` to fetch custom tenant limits dynamically before creating the Bucket4j bucket.
  - *Example:* `FREE` users default to `5000/hr` (from `application.yaml`), while `ENTERPRISE` unlocks custom Bucket4j allocations up to `100000/hr`.
  - **Fallback Resiliency:** Implement local in-memory fallback rate limiting (JVM heap mapping) if Redis is unavailable, replacing the simple fail-open strategy with a hybrid setup.
- **Usage-Based Metering (Base + Overage):**
  - Track Monthly Active Users (MAUs) and aggregate authentication events per tenant.
  - Report usage metrics to external payment gateways (like Stripe) to automatically invoice customers who exceed their base user quotas.

---

## Phase 9: Global Identity & Multi-Account Management
Evolve the platform to support users with multiple independent identities across different tenants.

- **Unified Multi-Session Management (Google-Style):**
  - Implement a session indexing system that allows users to stay logged into multiple tenant accounts simultaneously in one browser.
  - Build a "Master Account Switcher" UI component for seamless transitions between workspaces.
- **Silent Cross-Tenant SSO (`AUTO_LOGIN` Path & `G_SESSIONID`):**
  - *Reference:* See [consolidated_tenant_switching.md](consolidated_tenant_switching.md) (Path 2).
  - Implement root-domain `G_SESSIONID` cookie inspection in `MultiTenantSecurityFilter`.
  - Validate master session against `auth-master-service` and verify target tenant membership (`tenant_memberships`).
  - Silently mint a new local tenant session without prompting credentials when `tenant_switch_policy = AUTO_LOGIN`.
- **Global Identity Mapping Registry:**
  - Create a high-performance registry in the Master Database to securely link local tenant identities to a single "Physical Person" global ID.
- **Cross-Tenant Account Discovery:**
  - Develop a secure workflow that allows users to "find" their existing accounts in other tenants during the login process (Privacy-preserving).
- **Global Logout & Security Events:**
  - Implement a "Sign out of all accounts" protocol that invalidates all active sessions across different tenant databases in a single action.
- **Cross-Subdomain Passkeys (Customize Relying Party):**
  - Implement a configurable "Customize Relying Party" feature to allow tenants to set their WebAuthn Relying Party ID to the root domain (e.g., `authenza.com` instead of `tenant.authenza.com`).
  - Enable Single Sign-On (SSO) passkey usage, allowing a user to seamlessly authenticate across multiple tenant subdomains using a single biometric credential.
- **Global OAuth2 Client Registry (Master DB Lookup):**
  - Move global clients (like `tenant-portal`'s `oidc-client`) to the Master Database (`auth-master`) instead of copying/replicating them to local isolated tenant databases during new tenant provisioning.
  - Update `TenantAwareRegisteredClientRepository` to fallback to Master DB lookup when a client is not found in the local tenant DB, eliminating data redundancy and provisioning overhead.

--- 

*Generated to guide the continuous development of the Authenza Multi-Tenant Auth Service.*
