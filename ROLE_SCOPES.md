# Roles, Scopes, and Authorities Architecture

Based on the roadmap and architecture of the Authenza platform, we need specific Scopes, Roles, and Authorities to separate platform administrators (managing the entire SaaS) from tenant administrators (managing their individual organizations).

## 1. Default OAuth2 Scopes
Scopes define what a specific OAuth2 client/token is allowed to do. They are usually broad.

*   **`openid`, `profile`, `email`**: Standard OIDC scopes for authentication.
*   **`authenza:platform:admin`**: Required for any API calls to `auth-master-service` (Super Admin).
*   **`authenza:tenant:admin`**: Required for API calls to `auth-iam-service` to configure tenant-specific features.
*   **`authenza:user`**: Required for operations acting on behalf of a regular end-user inside a tenant.

---

## 2. System Admin (Authenza Platform Owner)
This group manages the global platform, billing tiers, and new customer registrations. They operate within the `system-admin` tenant database.

**Role:** `ROLE_SYSTEM_ADMIN`

**Authorities (Granular Permissions):**
*   **`tenant:create`** / **`tenant:delete`**: Can provision or shut down customer workspaces.
*   **`tenant:update_tier`**: Move a tenant between FREE, PRO, and ENTERPRISE plans (Phase 8).
*   **`platform:billing:read`**: View overarching SaaS revenue and usage metrics (Phase 8).
*   **`global:settings:write`**: Force immediate JWKS rotation or configure global platform flags (Phase 3).
*   **`platform:audit:read`**: View global platform-level activity.

---

## 3. Tenant Admin (The B2B Customer)
This group acts as the highest authority strictly within their isolated database schema (`auth_tenant_xxx`).

**Role:** `ROLE_TENANT_ADMIN`

**Authorities:**

*   **Identities (Phase 1 & 5)**
    *   **`user:create`** (Inviting users)
    *   **`user:delete`** / **`user:impersonate`** (Admin "Login As" feature)
    *   **`group:write`** / **`role:write`** (Assigning RBAC to their employees)
*   **OAuth2 & Identity Providers (Phase 3 & 4)**
    *   **`client:write`** (Create new SPAs or M2M OAuth2 clients)
    *   **`idp:write`** (Configure external Google/Okta Single Sign-On)
*   **Customization & Branding (Phase 4)**
    *   **`theme:write`** (Dynamic branding, logos, colors)
    *   **`email:write`** (Custom SMTP settings and white-labeled HTML templates)
    *   **`schema:write`** (Configure custom JSON registration schemas)
*   **Security & Audit (Phase 2 & 5)**
    *   **`sec:settings:write`** (Enforce MFA, set password policies, configure lockout limits)
    *   **`audit:read`** (View their own organization's login and admin activity logs)
*   **Developer Experience (Phase 6)**
    *   **`webhook:write`** (Create webhook endpoints for events)
    *   **`apikey:write`** (Generate system-level Machine-to-Machine keys)

---

## 4. Tenant End-User (Regular User)
A standard employee or customer living inside a tenant's database.

**Role:** `ROLE_USER`

**Authorities:**
*   **`profile:write`** (Self-serve detail updates)
*   **`password:write`** (Self-serve password reset/change)
*   **`device:write`** / **`session:revoke`** (View and revoke own active sessions - Phase 2)
*   **`mfa:manage`** (Enroll/delete their own TOTP devices)

---

## How it connects

When a Tenant Admin logs in, the `OAuth2TokenCustomizer` will look at their database record, see `ROLE_TENANT_ADMIN`, fetch all the above authorities, and bake them directly into the JWT token claims. The `auth-iam-service` controllers will then simply apply `@PreAuthorize("hasAuthority('client:write')")` on the endpoints.
