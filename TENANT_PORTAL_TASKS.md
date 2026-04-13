# Authenza Tenant Portal: Detailed Task Breakdown

This document breaks down the frontend development tasks specifically for **Project 2: `authenza-tenant-portal`**, detailing sub-tasks and the specific user roles responsible for each action.

---

## Phase 1: Identity Lifecycle (User Onboarding & Profile)
**Goal:** Allow admins to manage staff/users, and allow users to manage their own profile data.

### Task 1.1: User Management Dashboard
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 1.1.1:** Build User List View (Data table with server-side pagination, search by email, and filter by status: Active/Locked/Disabled).
- **Sub-task 1.1.2:** Build "Invite User" Modal (Form taking email address and initial Role assignments; triggers invite email).
- **Sub-task 1.1.3:** Build User Actions Menu (Dropdown on table rows to: Disable account, Force password reset, View details).

### Task 1.2: End-User Profile Management
**Role:** `ROLE_USER` (Note: Admins naturally get this view for their own profile)
- **Sub-task 1.2.1:** Build "My Profile" Page (Form to view and edit `given_name`, `family_name`, `phone_number`, and profile picture upload).
- **Sub-task 1.2.2:** Build "Security" Tab (Change password form requiring current password to validate against before accepting the new one).

---

## Phase 2: Enhanced Security
**Goal:** Implement MFA and device session management.

### Task 2.1: Security Policies Config
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 2.1.1:** Build Password Policy Editor (Toggles/Inputs for: min length, require special chars, require numbers, expiration days).
- **Sub-task 2.1.2:** Build MFA Enforcement Toggle (Options: Optional, Required for Admins Only, Required for All Users).
- **Sub-task 2.1.3:** Build Lockout Policy Editor (Inputs for: Max failed attempts before lockout, Lockout duration in minutes).

### Task 2.2: User Security & Devices
**Role:** `ROLE_USER`
- **Sub-task 2.2.1:** Build MFA Enrollment Flow (Display QR code for Authenticator apps, input verification code, auto-generate and display recovery backup codes).
- **Sub-task 2.2.2:** Build Active Sessions View (List active JWT refresh tokens displaying device type, OS, Location/IP, and Last Active time).
- **Sub-task 2.2.3:** Build Session Revocation (A "Sign Out of Device" button to kill a specific remote session instantly).

---

## Phase 3: OIDC Integration
**Goal:** Manage OAuth2 clients (SPAs, Mobile apps, APIs) that belong to the tenant.

### Task 3.1: OAuth2 Client Management
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 3.1.1:** Build Client Applications List (Cards/Table of registered apps).
- **Sub-task 3.1.2:** Build "Create Client" Wizard (Step 1: Select app type [SPA, Web, M2M, Mobile]. Step 2: Name & Logo).
- **Sub-task 3.1.3:** Build Client Detail Page (Manage `redirect_uris`, allowed `grant_types`, view `client_id`, and a highly secured button to generate/rotate the `client_secret`).

---

## Phase 4: Tenant Customization
**Goal:** Deep branding, RBAC, and bespoke configurations for the B2B organization.

### Task 4.1: RBAC Management
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 4.1.1:** Build Roles List (View standard system roles vs custom roles).
- **Sub-task 4.1.2:** Build Role Editor (Create a custom role name, check/uncheck specific `Authorities` from a grouped list like `[x] user:create`, `[ ] client:write`).

### Task 4.2: Dynamic Branding & Theming
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 4.2.1:** Build Theme Editor (Color picker for primary/accent colors, logo image upload).
- **Sub-task 4.2.2:** Build Live Login Preview (An iframe or CSS sandbox showing what the login screen will look like with the current colors).

### Task 4.3: Email & Registration
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 4.3.1:** Build SMTP Settings Form (Host, port, username, password, TLS toggle, and a "Send Test Email" button).
- **Sub-task 4.3.2:** Build Email Template Editor (Tabbed HTML code editor for modifying the Welcome, Forgot Password, and MFA alert emails).
- **Sub-task 4.3.3:** Build Custom Registration SchemaBuilder (A UI to define custom fields required during signup, e.g., "Employee ID", marking them as required or optional).

---

## Phase 5: Interoperability
**Goal:** Connect to outside enterprise directories and audit activities.

### Task 5.1: Federated Identity (SSO)
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 5.1.1:** Build IdP Connections List (View active connections to Google Workspace, Okta, Azure AD).
- **Sub-task 5.1.2:** Build IdP Configurator (Input Client ID/Secret obtained from the external provider, map external claims to Authenza user fields).

### Task 5.2: Auditing & Impersonation
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 5.2.1:** Build Activity Logs Viewer (A dense, searchable, filterable table of raw auth events and admin config changes).
- **Sub-task 5.2.2:** Build "Login As" Feature (An action button on the User List that generates a temporary impersonation session, strictly logged in the audit trail).

---

## Phase 6: Developer Experience (DX)
**Goal:** Automation and API access for tenant developers.

### Task 6.1: Webhooks
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 6.1.1:** Build Webhook Endpoints List.
- **Sub-task 6.1.2:** Build Webhook Creator (Input destination URL, multi-select trigger events like `user.created` or `password.changed`, display the generated cryptographic signing secret).

### Task 6.2: API Keys
**Role:** `ROLE_TENANT_ADMIN`
- **Sub-task 6.2.1:** Build API Key Generator (Create M2M keys for scripts, define precise scopes, and visually enforce that the secret string is displayed only *once*).

---

## Phase 7: Compliance & Privacy
**Goal:** Give users control over their data to meet GDPR/CCPA.

### Task 7.1: Data Export & Deletion
**Role:** `ROLE_USER`
- **Sub-task 7.1.1:** Build "Download My Data" Action (Button that triggers an async job to compile and email a JSON archive of all the user's data).
- **Sub-task 7.1.2:** Build "Delete Account" Flow (Requires step-up authentication/password re-entry, presents severe warning prompts, triggers final hard delete or anonymization).
