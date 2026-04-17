-- ═══════════════════════════════════════════════════════════════
-- V0.0.5: IAM Tables — Seed Default Roles & Authorities
-- ═══════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────
-- 1. Insert Base Authorities
-- ──────────────────────────────────────────────
INSERT INTO authority (id, permission, display_name, description, category) VALUES
(1, 'authenza:platform:admin', 'Platform Admin', 'Global SaaS Management', 'System'),
(2, 'authenza:tenant:admin', 'Tenant Admin', 'Full Access to Tenant Settings', 'Tenant'),
(3, 'authenza:user', 'Standard User', 'Basic end-user profile operations', 'User'),

(4, 'tenant:create', 'Create Tenants', 'Provision new isolated workspaces', 'System'),
(5, 'tenant:delete', 'Delete Tenants', 'Delete entire workspaces completely', 'System'),
(6, 'tenant:update_tier', 'Update Tiers', 'Switch between FREE, PRO, ENTERPRISE', 'System'),
(7, 'platform:billing:read', 'Read Billing', 'View global revenue and MAU counts', 'System'),
(8, 'global:settings:write', 'Update Global Settings', 'JWKS rotation, global feature flags', 'System'),
(9, 'platform:audit:read', 'Platform Audit Logs', 'View master scale audit logs', 'System'),

(10, 'user:create', 'Create/Invite Users', 'Allowed to invite employees', 'Identity'),
(11, 'user:delete', 'Delete Users', 'Offboarding and account removals', 'Identity'),
(12, 'user:impersonate', 'Impersonate Users', 'Login as a specific user without password', 'Identity'),
(13, 'group:write', 'Manage Groups', 'Create and modify user groups', 'Identity'),
(14, 'role:write', 'Manage Roles', 'Create and assign custom RBAC logic', 'Identity'),

(15, 'client:write', 'Manage OAuth2 Clients', 'Create custom SPAs and APIs', 'OAuth2'),
(16, 'idp:write', 'Manage Integrations', 'Connect Google, Okta, Azure AD', 'OAuth2'),

(17, 'theme:write', 'Manage Themes', 'Modify custom colors, logos, layouts', 'Customization'),
(18, 'email:write', 'Manage Email Settings', 'Set custom SMTP and HTML templates', 'Customization'),
(19, 'schema:write', 'Manage Custom Schema', 'Set custom signup metadata fields', 'Customization'),

(20, 'sec:settings:write', 'Security Settings', 'MFA controls, lockout limits, policies', 'Security'),
(21, 'audit:read', 'Audit Logs', 'View localized tenant event history', 'Security'),

(22, 'webhook:write', 'Manage Webhooks', 'Set outbound push notification URLs', 'Developer'),
(23, 'apikey:write', 'Manage API Keys', 'Generate M2M tokens for scripts', 'Developer'),

(24, 'profile:write', 'Update Profile', 'Update basic details', 'Self-Serve'),
(25, 'password:write', 'Update Password', 'Change/reset passwords', 'Self-Serve'),
(26, 'device:write', 'Manage Devices', 'View and revoke active sessions', 'Self-Serve'),
(27, 'mfa:manage', 'Manage MFA', 'Enroll new authenticators', 'Self-Serve');


-- ──────────────────────────────────────────────
-- 2. Insert Default Roles
-- ──────────────────────────────────────────────
INSERT INTO role (id, name, display_name, description, system_default) VALUES
(1, 'ROLE_SYSTEM_ADMIN', 'System Administrator', 'Highest authority. Controls the entire multi-tenant Authenza SaaS.', TRUE),
(2, 'ROLE_TENANT_ADMIN', 'Tenant Administrator', 'Highest authority within an isolated organization or tenant.', TRUE),
(3, 'ROLE_USER', 'Standard User', 'Default end-user within a workspace.', TRUE);


-- ──────────────────────────────────────────────
-- 3. Map Authorities to Roles (role_authority)
-- ──────────────────────────────────────────────

-- ROLE_SYSTEM_ADMIN mapping
INSERT INTO role_authority (role_id, authority_id) VALUES
(1, 1), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9);

-- ROLE_TENANT_ADMIN mapping (Gets all tenant-specific ones)
INSERT INTO role_authority (role_id, authority_id) VALUES
(2, 2), (2, 10), (2, 11), (2, 12), (2, 13), (2, 14), (2, 15),
(2, 16), (2, 17), (2, 18), (2, 19), (2, 20), (2, 21), (2, 22), (2, 23);

-- ROLE_USER mapping (Self-service basics)
INSERT INTO role_authority (role_id, authority_id) VALUES
(3, 3), (3, 24), (3, 25), (3, 26), (3, 27);
