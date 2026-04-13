# Authenza Frontend Architecture

Based on the [ROADMAP.md](./ROADMAP.md), the Authenza platform requires **2 Angular projects** to cover all user personas and roadmap phases.

---

## Project 1: `authenza-admin-portal` — Internal Platform Dashboard

**Used By:** Authenza Platform Owners (Super Admins operating inside the `system-admin` tenant)  
**Backend APIs:** `auth-master-service`  
**OAuth2 Scope:** `authenza:platform:admin`  
**Role:** `ROLE_SYSTEM_ADMIN`

| Roadmap Feature | Angular Page / Module |
|---|---|
| Tenant Provisioning (Phase 4) | Tenant List → Create / Delete / Suspend |
| Subscription Tiers (Phase 8) | Billing Dashboard → Upgrade / Downgrade Plans |
| Usage Metering (Phase 8) | MAU Charts, Overage Invoicing |
| Platform Audit (Phase 5) | Global Event Log Viewer |
| JWKS Rotation (Phase 3) | Key Management Settings |
| CAE Event Streams (Phase 5) | Real-time Security Event Monitor |

---

## Project 2: `authenza-tenant-portal` — Customer-Facing White-Labeled Dashboard

**Used By:** B2B Customer Admins + Their End-Users  
**Backend APIs:** `auth-iam-service`, `auth-server-core`  
**OAuth2 Scopes:** `authenza:tenant:admin`, `authenza:user`  
**Roles:** `ROLE_TENANT_ADMIN`, `ROLE_USER`

Angular route guards (`canActivate`) check the JWT for `ROLE_TENANT_ADMIN` vs `ROLE_USER` and dynamically show/hide navigation — exactly how Auth0 and Firebase Console work.

### Tenant Admin Views (`ROLE_TENANT_ADMIN`)

| Roadmap Feature | Angular Page / Module |
|---|---|
| **Identity Lifecycle (Phase 1)** | |
| User Invitation | User List → Invite via Email |
| Password Policies | Security Settings Page |
| **Enhanced Security (Phase 2)** | |
| Brute Force / Lockout Config | Security Policies Page |
| MFA Enforcement | Security → Require MFA Toggle |
| **OIDC Integration (Phase 3)** | |
| OAuth2 Client Management | Client Apps → Create SPA / M2M |
| Custom Consent Screen | Consent Screen Editor |
| **Tenant Customization (Phase 4)** | |
| RBAC Management | Roles & Permissions Editor |
| Dynamic Branding | Theme Editor (Logo, Colors) |
| Email Templates & BYO-SMTP | Email Settings Page |
| Custom Registration Schema | Dynamic Form Builder |
| Feature Toggles | Feature Flags per Client |
| i18n / Localization | Locale Settings |
| **Interoperability (Phase 5)** | |
| Federated SSO | Identity Providers → Add Google / Okta |
| SCIM 2.0 Directory Sync | Directory Sync Settings |
| Admin Impersonation | "Login As" User Action |
| Tenant Audit Logs | Organization Activity Logs |
| **Developer Experience (Phase 6)** | |
| Event Webhooks | Webhook Endpoints Manager |
| M2M API Keys | API Keys Page |

### End-User Views (`ROLE_USER`)

| Roadmap Feature | Angular Page / Module |
|---|---|
| **Identity Lifecycle (Phase 1)** | |
| Profile Management | My Profile → Edit Details |
| Change / Reset Password | Password Page |
| **Enhanced Security (Phase 2)** | |
| MFA Enrollment | Security → Add Authenticator App |
| Active Sessions | Devices → View & Revoke |
| FIDO2 / WebAuthn | Passkeys → Register Biometrics |
| **Compliance & Privacy (Phase 7)** | |
| Data Export / Takeout | Download My Data |
| Account Deletion | Delete Account |
| Step-Up Authentication | Re-auth Modal for Sensitive Actions |

---

## Why 2 Projects, Not 3?

Tenant Admins and End-Users share the **same OAuth2 tenant context** and the **same backend** (`auth-iam-service`). Splitting them into separate Angular apps would create duplicated auth logic, shared component libraries, and deployment overhead for no benefit. A single app with role-based route guards is the industry standard (Auth0, Firebase Console, Clerk).

## Why Not 1 Project?

The Super Admin portal and the Tenant portal have **completely different backend APIs**, **different OAuth2 scopes**, and **different security boundaries**. Mixing them into one Angular app creates a security risk (accidentally exposing platform-level routes to tenant users) and makes the codebase unnecessarily complex.

---

*This document maps the [ROADMAP.md](./ROADMAP.md) phases to the frontend architecture.*
