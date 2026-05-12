# Authenza - Project Roadmap

This consolidated roadmap outlines the step-by-step feature development to evolve **Authenza** from a foundational multitenant Spring Authorization Server into a comprehensive, enterprise-grade Identity and Access Management (IAM) product.

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
  - **Distributed Enforcement (Redis) Pending:** Move attack counters from local memory to Redis to ensure account lockouts are consistent across all cluster instances.
- **Multi-Factor Authentication (MFA):**
  - Introduce TOTP (Time-Based One Time Password, e.g., Google Authenticator).
  - Enforce multi-step authentication flows within your custom login journey.
  - Two-level policy enforcement: tenant-wide (`mfa_required_for_all`) and per-user (`mfa_enabled`) flags.
  - Admin-initiated enrollment: tenant admin sets `mfa_enabled=true`; user is directed to QR code setup on next login.
- **MFA Endpoint Security Hardening:**
  - Require authentication before calling self-service MFA endpoints (`/mfa/setup`, `/mfa/confirm`, `/mfa/disable`).
  - Enforce user identity scoping: a user can only manage their own MFA secret, not another user's.
  - *(Full RBAC-based admin override — "admin can manage any user's MFA" — deferred to Phase 4.)*
- **Active Session Management:**
  - Track active user sessions globally (e.g., via Redis or a `sessions` database table) instead of solely relying on stateless tokens.
  - Add capabilities for users to view "Active Devices" and revoke (logout) specific remote sessions.
- **FIDO2 / WebAuthn (Passwordless):** ✅ *Implemented (Phase 2)*
  - Allow users to authenticate using physical device biometrics (Apple FaceID, TouchID, Windows Hello, YubiKeys).
  - Library: Yubico `webauthn-server-core` (`com.yubico:webauthn-server-core:2.5.4`).
  - Database: `webauthn_credential` table (V0.0.11 Liquibase migration) stores credential ID, COSE public key, and sign counter per user.
  - **Registration flow** (`/users/{userId}/passkeys/register/start` → `/finish`): Generates a challenge, verifies the browser's attestation, persists the public key credential.
  - **Authentication flow** (`/webauthn/authenticate/start` → `/finish`): Generates an assertion challenge, verifies the signed assertion, increments the sign-count for replay-attack protection.
  - **Passkey management** (`GET/DELETE /users/{userId}/passkeys`): Lets users view and remove their registered keys from the Security settings tab.
  - **TODO (Next Steps):** Integrate the `/webauthn/authenticate/finish` success response into the `auth-server-core` OIDC flow so a successful passkey assertion can complete an OAuth2 authorization code grant without a password form submission.
- **Tenant-Level Rate Limiting:**
  - Protect infrastructure from noisy-neighbor DDoS attacks by throttling API requests per `tenant_id` (e.g., max 10,000 auth attempts per hour).
- **Strict Cross-Tenant Session Isolation:**
  - Prevent "Tenant Session Hopping" by tightly coupling the `tenantId` into the active authenticated `UserPrincipal`.
  - Enforce strict validation in the security filter to ensure a user authenticated in Tenant A cannot mistakenly (or maliciously) mint OAuth2 tokens from Tenant B's authorization endpoints.
  - *Reference:* See [CROSS_TENANT_SESSION_BLEED.md](CROSS_TENANT_SESSION_BLEED.md) for architectural implementation details.
- **Adaptive / Risk-Based Authentication:**
  - Detect anomalous login attempts (e.g., impossible travel, unknown devices, or new IP addresses).
  - Automatically challenge the user with progressive MFA or send a "New Device Detected" security alert email.

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

---

## Phase 4: Tenant Customization & Organization
Enable complex B2B scenarios and tenant-specific configuration.

- **Role-Based Access Control (RBAC):**
  - Introduce `Roles`, `Permissions`, and `Groups` entities within the tenant database schema.
  - Propagate these roles as scopes/claims on minted tokens.
  - Build a **custom `UserDetails`** principal that carries `userId` (database PK) alongside `username`, enabling precise ownership checks in controllers.
  - Add `@PreAuthorize` expressions for MFA management: users can only manage their own MFA; `ROLE_ADMIN` or `ROLE_TENANT_ADMIN` can manage any user's MFA.
  - Role-gate the tenant-wide MFA policy endpoint (`PUT /api/v1/settings/mfa-policy`) to `ROLE_TENANT_ADMIN` only.
  - **Tenant Portal UI & Backend Endpoint Segregation (In Progress):**
    - Enable Spring Security `oauth2ResourceServer` in `auth-iam-service` to validate JWTs.
    - Add `@PreAuthorize("hasRole('TENANT_ADMIN')")` restrictions on `UserController`, `TenantSettingsController`, etc.
    - Implement an Angular Route Guard (admin.guard) on the frontend to dynamically hide Dashboard/Users/Settings views from normal users.
    - **Auth Server Client Access Control:** Implement a backend interceptor in `SecurityConfig` to strictly block token issuance for the `authenza-tenant-portal` client if the user lacks the `TENANT_ADMIN` role, redirecting back with an `error=access_denied` parameter for a clean UX.
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

- **Production Database Separation (system-admin Isolation):**
  - Currently `system-admin` tenant shares the `auth-master` database because `DataBaseInitializer` registers it with the same JDBC URL (dev shortcut).
  - Separate `system-admin` into its own dedicated database (`jdbc:mysql://.../system-admin`) to cleanly isolate tenant registry data from tenant user/IAM data.
  - Add `app.system-tenant.url` to `auth-master-service/application.yaml` and update `DataBaseInitializer` to use it when provisioning the system tenant.
  - Migrate existing `application_user`, `oauth2_registered_client`, `roles`, `tenant_settings`, and all IAM tables out of `auth-master` into the dedicated database before go-live.
  - Each tenant (system-admin, acme-corp, etc.) will then have a fully isolated database, with `auth-master` containing only the tenant registry table.

- **Liquibase Changelog Restructuring (initialization / migration split):**
  - Currently all Liquibase changesets for tenant databases live under `migration/tenant/V0.0.1` through `V0.0.7`, including the initial schema creation and seed data.
  - Activate the `initialization/` folder (currently a Phase 5 placeholder) by moving the baseline schema and seed data out of versioned migrations:
    - Move `migration/tenant/V0.0.1/ddl.xml` (base table creation) → `initialization/ddl.xml`
    - Move `migration/tenant/V0.0.5/dml.xml` (default roles/permissions seed) → `initialization/dml.xml`
  - Wire `initialization/db.changelog-initialization.xml` into `TenantProvisioningService` so it runs first on every new tenant database, followed by `migration/tenant/db.changelog-tenant.xml` for incremental changes.
  - Update the `DATABASECHANGELOG` table on all existing tenant databases to reflect the new file paths, preventing Liquibase checksum failures during the cut-over.
  - End state: `initialization/` = one-time baseline; `migration/` = incremental versioned changes only.

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

---

## Phase 6: Developer Experience (DX) & Extensibility
Empower tenant developers to integrate deeply with the Authenza platform.

- **Seamless Local Development ("Authenza In A Box"):**
  - Provide a highly polished `docker-compose.yml` and unified CLI experience so a company's developers can spin up the entire Authenza stack (Master DB, Redis, Auth Server, and Portal) locally on their laptop in seconds.
  - *Strategic differentiator:* Developers cannot test Auth0 or Azure AD effectively without a live internet connection and navigating complex cloud sandbox environments. Authenza must run locally as flawlessly as it runs in production.
- **Event Webhooks (Asynchronous):**
  - Fire asynchronous HTTP callbacks to tenant-configured URLs upon critical lifecycle events (`USER_REGISTERED`, `PASSWORD_CHANGED`, `TENANT_DELETED`) for downstream auditing and data synchronization.
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
Meet stringent global data privacy requirements (GDPR, CCPA).

- **Total Data Portability (The Anti-Lock-in Guarantee):**
  - Implement self-service APIs allowing a tenant administrator to request a complete 100% database export (users, roles, settings, logs) down to the SQL/JSON level at the click of a button.
  - Implement user-level "Takeout" APIs for individual data exports.
  - *Strategic differentiator:* Okta and Auth0 intentionally lock data in to make migrating away extremely difficult. Because Authenza guarantees Physical Isolation (Database-per-tenant), providing a raw, unadulterated data dump is both secure and trivial.
- **Right to be Forgotten (Account Anonymization):**
  - Build a secure workflow to completely scramble or purge a user's PII across a tenant's database tables rather than simply disabling the account.
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
- **Usage-Based Metering (Base + Overage):**
  - Track Monthly Active Users (MAUs) and aggregate authentication events per tenant.
  - Report usage metrics to external payment gateways (like Stripe) to automatically invoice customers who exceed their base user quotas.

---

## Phase 9: Global Identity & Multi-Account Management
Evolve the platform to support users with multiple independent identities across different tenants.

- **Unified Multi-Session Management (Google-Style):**
  - Implement a session indexing system that allows users to stay logged into multiple tenant accounts simultaneously in one browser.
  - Build a "Master Account Switcher" UI component for seamless transitions between workspaces.
- **Global Identity Mapping Registry:**
  - Create a high-performance registry in the Master Database to securely link local tenant identities to a single "Physical Person" global ID.
- **Cross-Tenant Account Discovery:**
  - Develop a secure workflow that allows users to "find" their existing accounts in other tenants during the login process (Privacy-preserving).
- **Global Logout & Security Events:**
  - Implement a "Sign out of all accounts" protocol that invalidates all active sessions across different tenant databases in a single action.

--- 

*Generated to guide the continuous development of the Authenza Multi-Tenant Auth Service.*
