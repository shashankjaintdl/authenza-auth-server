# Manifest of Centralized Components vs. Isolated Tenant Components

This document outlines all core systems within Authenza that must be moved to the Master Database (`auth-master`) to enable centralized operations (like tenant switching and SSO) versus those that must remain physically isolated inside tenant-specific databases to satisfy data isolation compliance.

---

## 1. Centralized Components (Master Database Scope)

These components reside globally in `auth-master` and do not require replication.

| Component / Table | Centralization Logic | Business/Security Purpose |
| :--- | :--- | :--- |
| **OAuth2 Clients** (`oauth2_registered_client`) | Stored globally. Uses a `tenant_id` column where `'GLOBAL'` means system-wide portal apps, and specific tenant names (e.g., `'villsyn'`) restrict visibility. | Prevents client replication overhead during tenant onboarding. Simplifies portal management. |
| **User Identities** (`global_accounts`) | Central user registry containing global IDs, emails, password hashes, and MFA secrets. | Allows users to authenticate centrally. Foundation for global sessions and SSO. |
| **Tenant Association** (`tenant_membership`) | Maps global user IDs to target tenant IDs (e.g. User A is `OWNER` of Tenant B and `COLLABORATOR` of Tenant C). | Controls access boundaries. Dictates which tenants a user is allowed to switch to. |
| **Global WebAuthn Credentials** (`global_webauthn_credentials`) | Biometric key handles registered by global users (Admins, Collaborators, SaaS Operators). | Allows global admins to use a single passkey to access/switch between all their tenants. |
| **Tenant Policy Cache** (`tenant_settings` in master) | Core tenant settings (like `tenant_switch_policy`, active status) reside centrally in the master DB cache. | Allows the security filter (`MultiTenantSecurityFilter`) to make routing/isolation decisions before swapping datasources. |

---

## 2. Isolated Components (Tenant Database Scope)

These components reside strictly inside the isolated tenant schemas (e.g., `villsyn_db`, `acme_corp_db`) and are queried only when the datasource context matches.

| Component / Table | Isolation Logic | Business/Security Purpose |
| :--- | :--- | :--- |
| **Local User Profiles** (`application_user` profile metadata) | Standard employee profile details (first name, last name, phone, employee ID) stay locally. **Passwords/MFA hashes are set to NULL**. | Ensures sensitive personal identifiable information (PII) complies with data residency rules. |
| **RBAC Configurations** (`user_role` & `role_authority`) | Maps roles (e.g. `ROLE_TENANT_ADMIN`, `ROLE_EMPLOYEE`) and fine-grained resource permissions locally. | Maintains absolute business boundary isolation. An admin in Tenant A has zero privilege definition in Tenant B. |
| **Local WebAuthn Credentials** (`webauthn_credential`) | Passkey credentials registered by standard tenant employees. | Complies with strict security audits (SOC2/GDPR) ensuring employee key signatures remain within their corporate boundary. |
| **OAuth2 Authorizations** (`oauth2_authorization`) | Stores tokens, authorization codes, and consent records locally. | Prevents token leakages across databases. Ensures a compromise in one tenant DB does not expose tokens of another. |

---

## 3. Dependency Implementation Sequence

To safely execute these changes, the development pipeline must follow this order:

1. **Phase 1: Master Schema Migration (Liquibase V0.0.12):**  
   Create the central tables in `auth-master` DB (DDL execution).
2. **Phase 2: Client Fallback Hook Implementation:**  
   Configure `TenantAwareRegisteredClientRepository` to query Master DB and handle the `GLOBAL` wildcard scope.
3. **Phase 3: Hybrid User Auth Resolver:**  
   Refactor `JdbcTenantUserDetailsService` to perform split credentials validation (Master DB first, Tenant DB roles second).
4. **Phase 4: WebAuthn Hybrid Registry Routing:**  
   Refactor passkey verification in `WebAuthnService` to dynamically route key handle lookups based on user membership type.
