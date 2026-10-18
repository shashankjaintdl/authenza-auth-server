# Authenza E2E Testing Plan

> Covers all implemented endpoints across `auth-master-service` and `auth-iam-service` (both on port 8081).
> Browser flows use **JUnit 5 + Playwright-Java**. REST API calls use **`APIRequestContext`** with `Bearer` or `Basic` auth.

---

## Test Infrastructure

| Item | Value |
|---|---|
| Base URL | `http://localhost:8081` |
| Tenant prefix | `/{tenantId}/` (e.g. `/system-admin/`) |
| Admin credentials | `admin@authenza.io` / `Admin@12345` |
| OAuth2 client (OIDC + Basic) | `super-admin-client` / `super-admin-secret` |
| OAuth2 client (PKCE only) | `oidc-client` |
| Basic Auth Header value | `c3VwZXItYWRtaW4tY2xpZW50OnN1cGVyLWFkbWluLXNlY3JldA==` |

---

## MODULE 1 — OAuth2 / OIDC Authorization Flow

### 1.1 `GET /{tenantId}/oauth2/authorize`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Valid authorization request | `client_id=super-admin-client`, valid `redirect_uri`, `scope=openid profile` | Redirects to login page |
| P2 | Already authenticated user | Valid session cookie + authorize params | Redirects directly to `redirect_uri?code=` |
| P3 | PKCE flow (S256) | `oidc-client`, `code_challenge`, `code_challenge_method=S256` | Redirects to login page |
| N1 | Unknown client_id | `client_id=unknown-client` | `error=unauthorized_client` |
| N2 | Invalid redirect_uri | `redirect_uri=http://evil.com` | `error=invalid_redirect_uri` |
| N3 | Invalid scope | `scope=openid hacking` | `error=invalid_scope` |
| N4 | Missing response_type | No `response_type` param | `error=invalid_request` |
| N5 | Wrong tenant path | `/notexist/oauth2/authorize` | `404` or tenant-not-found |

---

### 1.2 `POST /{tenantId}/login` (Form Login)

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Valid credentials | `username=admin@authenza.io`, `password=Admin@12345` | Redirect to `redirect_uri?code=` |
| P2 | Username different case | `username=ADMIN@AUTHENZA.IO` | Successful (case-insensitive) |
| N1 | Wrong password | Correct email, wrong password | Login page with error |
| N2 | Non-existent user | `ghost@test.com` | Login failure (no enumeration) |
| N3 | Empty credentials | Empty fields | Validation error on page |
| N4 | Locked account | Correct creds for locked user | Error: account locked |
| N5 | Cross-tenant login | User from tenant-a, logging into tenant-b | Login failure |

---

### 1.3 `POST /{tenantId}/oauth2/token`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Valid code exchange (Basic Auth) | Valid `code`, correct `redirect_uri`, Basic header | `200` with `access_token`, `refresh_token`, `id_token` |
| P2 | Valid code exchange (PKCE) | Valid `code`, matching `code_verifier` for `oidc-client` | `200` with `access_token` |
| P3 | Refresh token grant | Valid `refresh_token`, `grant_type=refresh_token` | `200` with new `access_token` |
| P4 | Client credentials grant | `grant_type=client_credentials`, Basic header | `200` with `access_token` |
| N1 | Invalid auth code | `code=fake_code` | `400 invalid_grant` |
| N2 | Code used twice | Same code submitted twice | Second returns `400 invalid_grant` |
| N3 | Wrong redirect_uri | Mismatched `redirect_uri` | `400 invalid_grant` |
| N4 | Wrong client secret | Wrong password in Basic header | `401 invalid_client` |
| N5 | Missing Authorization header | No Basic header for `client_secret_basic` client | `401` |
| N6 | Expired refresh token | Expired `refresh_token` | `400 invalid_grant` |
| N7 | Revoked refresh token | `refresh_token` from revoked session | `400 invalid_grant` |

---

## MODULE 2 — Global Account Management (`/admin/accounts`)

### 2.1 `POST /admin/accounts/register`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Register new admin account | Valid email, password, name | `201` with account; shadow user seeded in system-admin |
| P2 | Shadow user auto-seeded | After register, check IAM user list | Shadow user appears in system-admin |
| N1 | Duplicate email | Same email as existing account | `409 Conflict` |
| N2 | Invalid email format | `notanemail` | `400 Bad Request` |
| N3 | Weak password | `password=123` | `400 Bad Request` |
| N4 | Missing required fields | `{}` body | `400 Bad Request` |

---

### 2.2 `GET /admin/accounts/{accountId}/tenants`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Get tenants for valid account | Valid `accountId` with memberships | `200` list of `TenantMembership` |
| P2 | Account with no tenants | Valid `accountId`, no memberships | `200` empty list |
| N1 | Non-existent accountId | `accountId=99999` | `404` or empty |
| N2 | Non-numeric accountId | `accountId=abc` | `400 Bad Request` |

---

### 2.3 `GET /admin/accounts/tenant/{tenantId}/members`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Get members of existing tenant | `tenantId=system-admin` | `200` list of admins |
| P2 | Tenant with one member | Single-admin tenant | `200` single-entry list |
| N1 | Non-existent tenantId | `tenantId=ghost-tenant` | `200` empty list or `404` |

---

### 2.4 `POST /admin/accounts/tenant/{tenantId}/invite`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Invite existing global account to tenant | Valid email, platformRole, inviterAccountId | `201 Created` |
| N1 | Invite non-registered email | `email=nobody@test.com` | `404` or error |
| N2 | Missing inviterAccountId param | No `inviterAccountId` query param | `400 Bad Request` |
| N3 | Invalid tenantId | `tenantId=ghost-tenant` | Error or `404` |

---

## MODULE 3 — Tenant Registration (`/api/v1/admin/tenant`)

### 3.1 `POST /api/v1/admin/tenant`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Onboard new tenant | Valid `tenantId`, `ownerId`, `accountType` | `200` "Tenant provisioned successfully" |
| P2 | Provision creates DB schema | After POST, navigate to `/{newTenantId}/login` | Login page loads |
| N1 | Missing tenantId | Body missing `tenantId` | `400 Bad Request` |
| N2 | Duplicate tenantId | Same `tenantId` as existing | Server error or conflict |
| N3 | Invalid ownerId format | `ownerId=not-an-email` | `400 Bad Request` |
| N4 | Empty body | `{}` | `400 Bad Request` |

---

### 3.2 `GET /api/v1/admin/tenant?ownerId={email}`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Get tenants by owner | `ownerId=admin@authenza.io` | `200` list of tenants |
| P2 | Owner with no tenants | `ownerId=notenants@test.com` | `200` empty list |
| N1 | Missing ownerId param | No `ownerId` | `400 Bad Request` |

---

### 3.3 `POST /api/v1/admin/tenant/{tenantId}/switch`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Switch to valid tenant | Valid `tenantId` | `200` "Context switched to {tenantId}" |
| N1 | Switch to non-existent tenant | `tenantId=ghost` | `200` no-op (validation gap — known) |

---

### 3.4 `POST /api/v1/admin/tenant/{tenantId}/default`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Set default tenant | Valid `tenantId`, valid `ownerId` | `200` "Default tenant updated" |
| N1 | Missing ownerId | No `ownerId` query param | `400 Bad Request` |

---

## MODULE 4 — User Management (`/{tenantId}/api/v1/users`)

### 4.1 `POST /api/v1/users/register`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Register new user | Valid email, username, password | `201` with `UserResponse`; email sent |
| P2 | User appears in list | After register, GET /api/v1/users | User in `PENDING_VERIFICATION` status |
| N1 | Duplicate email | Same email as existing user | `409 Conflict` |
| N2 | Duplicate username | Same username as existing | `409 Conflict` |
| N3 | Invalid email | `email=not-valid` | `400 Bad Request` |
| N4 | Weak password | `password=abc` | `400 Bad Request` |
| N5 | Missing required fields | `{}` body | `400 Bad Request` |

---

### 4.2 `POST /api/v1/users/create` (Admin Create)

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Admin creates user | Valid `AdminUserCreateRequest` | `201 Created` |
| N1 | Missing fields | `{}` body | `400 Bad Request` |

---

### 4.3 `GET /api/v1/users`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Default list | No query params | `200` first page of 10 |
| P2 | Pagination | `currentPage=1&itemsPerPage=5` | `200` page 2 with 5 results |
| P3 | Sort descending | `sortBy=createdAt&sortOrder=desc` | `200` sorted correctly |
| N1 | Invalid sort field | `sortBy=invalidField` | `400` or safe fallback |

---

### 4.4 `GET /api/v1/users/verify-email?token={token}`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Valid token | Token from registration email | `200` "Email verified" |
| N1 | Invalid token | `token=garbage` | `400` or error |
| N2 | Token used twice | Submit same token twice | Second call errors |
| N3 | Expired token | Expired verification token | Error |

---

### 4.5 `POST /api/v1/users/forgot-password`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Valid email | `{"email": "admin@authenza.io"}` | `200` (anti-enumeration) |
| P2 | Non-existent email | `{"email": "ghost@test.com"}` | `200` same message |
| N1 | Missing email field | `{}` body | `400 Bad Request` |

---

### 4.6 `GET /api/v1/users/validate-reset-token?token={token}`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Valid reset token | Token from reset email | `200` "Token is valid." |
| N1 | Invalid token | `token=abc123fake` | `400` or error |
| N2 | Expired token | Past TTL | Error |

---

### 4.7 `POST /api/v1/users/reset-password`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Valid token + new password | Valid `token`, strong `newPassword` | `200` "Password reset" |
| N1 | Invalid token | Garbage `token` | Error |
| N2 | Weak new password | `newPassword=123` | `400 Bad Request` |
| N3 | Token reused | Same token used twice | Second call fails |

---

### 4.8 `GET /api/v1/users/check-email?email={email}`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Available email | `email=brand-new@test.com` | `200` `{"available": true}` |
| P2 | Taken email | `email=admin@authenza.io` | `200` `{"available": false}` |
| N1 | Missing email param | No `email` query param | `400 Bad Request` |
| N2 | Invalid email format | `email=notanemail` | `400` or `{"available": false}` |

---

### 4.9 `GET /api/v1/users/check-username?username={username}`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Available username | `username=freshuser123` | `200` `{"available": true}` |
| P2 | Taken username | Existing username | `200` `{"available": false}` |
| N1 | Missing username param | No `username` | `400 Bad Request` |

---

### 4.10 `POST /api/v1/users/invite`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Invite new user | Valid email, `X-Inviter-Name` header | `201 Created` |
| P2 | Invite token is valid | Validate invite token via API | Valid response |
| N1 | Duplicate invite | Invite same email twice | `409 Conflict` or error |
| N2 | Missing email | `{}` body | `400 Bad Request` |

---

### 4.11 `POST /api/v1/users/accept-invite`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Accept valid invite | Valid token + new password | `200` user now `ACTIVE` |
| N1 | Invalid token | `token=fake` | Error |
| N2 | Token already used | Same token twice | Second call fails |

---

### 4.12 `GET /api/v1/users/{userId}/profile`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Get existing profile | Valid `userId` | `200` with `UserResponse` |
| N1 | Non-existent userId | `userId=99999` | `404 Not Found` |
| N2 | Non-numeric userId | `userId=abc` | `400 Bad Request` |

---

### 4.13 `PUT /api/v1/users/{userId}/profile`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Update name and phone | Valid `userId`, `UpdateProfileRequest` | `200` with updated profile |
| N1 | Non-existent userId | `userId=99999` | `404 Not Found` |

---

### 4.14 `POST /api/v1/users/{userId}/change-password`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Correct current password | Valid `userId`, correct `currentPassword`, strong `newPassword` | `200` changed |
| N1 | Wrong current password | Incorrect `currentPassword` | `400` or `403` |
| N2 | Same as current | `newPassword == currentPassword` | Error (if enforced) |

---

### 4.15 `POST /api/v1/users/{userId}/force-change-password`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Admin sets new password | Valid `userId`, strong `newPassword` | `200` |
| N1 | Missing newPassword | `{}` body | `400 Bad Request` |
| N2 | Blank newPassword | `{"newPassword": ""}` | `400 Bad Request` |

---

### 4.16 `DELETE /api/v1/users/{userId}`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Delete existing user | Valid `userId` | `200` deleted |
| N1 | Already deleted | Same `userId` again | `404 Not Found` |
| N2 | Non-existent userId | `userId=99999` | `404 Not Found` |

---

### 4.17 `POST /api/v1/users/{userId}/unlock`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Unlock locked account | `userId` of locked user | `200` unlocked |
| N1 | Unlock already-active user | Active `userId` | `200` idempotent or no-op |
| N2 | Non-existent userId | `userId=99999` | `404 Not Found` |

---

### 4.18 `PATCH /api/v1/users/{userId}/email`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Update to new email | Valid `userId`, unique new `email` | `200` updated; `email_verified=false` |
| N1 | Email already in use | `email` of another user | `409 Conflict` |
| N2 | Missing email field | `{}` body | `400 Bad Request` |
| N3 | Blank email | `{"email": ""}` | `400 Bad Request` |
| N4 | Invalid email format | `{"email": "notvalid"}` | `400` validation error |

---

## MODULE 5 — MFA (TOTP) Management

### 5.1 `POST /api/v1/users/{userId}/mfa/setup`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Initiate MFA setup | Valid `userId` | `200` with `qrCodeUri`, `secret` |
| N1 | Non-existent userId | `userId=99999` | `404 Not Found` |
| N2 | MFA already enabled | `mfa_enabled=true` user | Error or re-setup |

---

### 5.2 `POST /api/v1/users/{userId}/mfa/confirm`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Confirm with valid TOTP | Valid `userId`, correct `code` | `200` MFA enabled |
| N1 | Wrong TOTP code | Invalid `code` | `400` invalid code |
| N2 | No MFA setup initiated | User with no pending secret | Error |

---

### 5.3 `POST /api/v1/users/{userId}/mfa/disable`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Disable with valid TOTP | Valid `userId`, correct `code` | `200` MFA disabled |
| N1 | Wrong TOTP code | Invalid `code` | `400` |
| N2 | MFA not enabled | `mfa_enabled=false` user | Error or no-op |

---

## MODULE 6 — Session Management

### 6.1 `GET /api/v1/users/{userId}/sessions`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | List active sessions | Valid `userId` with session | `200` list; `current=true` for active |
| P2 | No sessions | `userId` never logged in | `200` empty list |
| N1 | Non-existent userId | `userId=99999` | `200` empty or `404` |

---

### 6.2 `DELETE /api/v1/users/{userId}/sessions/{sessionId}`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Revoke specific session | Valid `userId`, `sessionId` | `200` `{"revokedSessionId": id}` |
| P2 | Session removed from list | After DELETE, GET sessions | Revoked session absent |
| N1 | Non-existent sessionId | `sessionId=99999` | `404` or error |
| N2 | Revoke already-revoked | Same `sessionId` twice | Error or idempotent `200` |

---

### 6.3 `DELETE /api/v1/users/{userId}/sessions`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Revoke all sessions | `userId` with multiple sessions | `200` `{"revokedCount": N}` |
| P2 | All sessions gone | After DELETE, GET sessions | Empty list |
| N1 | No active sessions | `userId` with no sessions | `200` `{"revokedCount": 0}` |

---

## MODULE 7 — Tenant Settings

### 7.1 `GET /api/v1/settings`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Get all settings | None | `200` flat key-value map |

### 7.2 `GET /api/v1/settings/mfa-policy`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Get MFA policy (default off) | None | `200` `{"mfaRequiredForAll": false}` |

### 7.3 `PUT /api/v1/settings/mfa-policy`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Enable MFA for all | `{"mfaRequiredForAll": true}` | `200` MFA required |
| P2 | Disable MFA for all | `{"mfaRequiredForAll": false}` | `200` enforcement off |
| N1 | Missing field | `{}` | `400 Bad Request` |
| N2 | Wrong type | `{"mfaRequiredForAll": "maybe"}` | Treated as `false` (fallback) |

### 7.4 `PUT /api/v1/settings`

| # | Scenario | Input | Expected |
|---|---|---|---|
| P1 | Set multiple settings | `{"session_ttl_days": "14"}` | `200` settings applied |
| N1 | Empty body | `{}` | `400 Bad Request` |

---

## MODULE 8 — WebAuthn / Passkeys

| # | Endpoint | Scenario | Expected |
|---|---|---|---|
| P1 | `POST /webauthn/authenticate/start` | Valid username | `200` challenge options |
| N1 | `POST /webauthn/authenticate/start` | Unknown username | `404` or anonymous challenge |
| P2 | `POST /webauthn/authenticate/finish` | Valid assertion | `200` authenticated |
| N2 | `POST /webauthn/authenticate/finish` | Tampered signature | `400` or `403` |
| P3 | `POST /{userId}/passkeys/register/start` | Valid `userId` | `200` creation options |
| N3 | `POST /{userId}/passkeys/register/start` | `userId=99999` | `404` |
| P4 | `POST /{userId}/passkeys/register/finish` | Valid assertion | `200` passkey registered |
| N4 | `POST /{userId}/passkeys/register/finish` | Duplicate credential ID | Error |
| P5 | `GET /{userId}/passkeys` | Valid `userId` | `200` list |
| P6 | `DELETE /{userId}/passkeys/{passkeyId}` | Valid `passkeyId` | `200` deleted |
| N5 | `DELETE /{userId}/passkeys/{passkeyId}` | `passkeyId=99999` | `404` |
| P7 | `PATCH /{userId}/passkeys/{passkeyId}` | Valid rename | `200` updated |

---

## MODULE 9 — Full End-to-End Browser Flows

### 9.1 Happy Path — Login + Token

| Step | Action | Expected |
|---|---|---|
| 1 | Navigate to `/{tenantId}/oauth2/authorize` | Redirected to login |
| 2 | Fill valid creds, click Login | Redirect to `redirect_uri?code=...` |
| 3 | POST `code` to `/oauth2/token` with Basic auth | `200` `access_token`, `id_token`, `refresh_token` |
| 4 | Decode JWT `access_token` | Contains `sub`, `tenant_id`, `roles`, `permissions` |

### 9.2 Multi-Tenant Isolation

| Step | Action | Expected |
|---|---|---|
| 1 | Login in `tenant-a`, get token | Token issued for `tenant-a` |
| 2 | Call `tenant-b` IAM API with `tenant-a` token | `401 Unauthorized` |

### 9.3 Session Revocation Lifecycle

| Step | Action | Expected |
|---|---|---|
| 1 | Login + get token | `session_id` present in JWT |
| 2 | GET `/sessions` | Active session listed |
| 3 | DELETE `/sessions/{sessionId}` | `200` revoked |
| 4 | GET `/sessions` | Session no longer listed |
| 5 | Use `refresh_token` | `400 invalid_grant` |

### 9.4 Password Reset Full Flow

| Step | Action | Expected |
|---|---|---|
| 1 | POST `/forgot-password` | `200` always |
| 2 | GET `/validate-reset-token?token=...` | `200` valid |
| 3 | POST `/reset-password` with new password | `200` reset complete |
| 4 | POST `/reset-password` same token again | Error: already used |
| 5 | Login with new password | Login succeeds |

---

## MODULE 10 — Centralized Client Architecture Tests

Tests validation of centralized OAuth2 client registration in Master DB (`auth-master`), `GLOBAL` client wildcards, and cross-tenant client access boundaries.

### 10.1 Master DB Client Resolution & `GLOBAL` Wildcards

| # | Scenario | Input / Setup | Expected Result |
|---|---|---|---|
| C1 | `GLOBAL` client access on Tenant A | Request token via `/system-admin/oauth2/token` with `client_id=super-admin-client` (where `tenant_id='GLOBAL'`) | `200 OK` + Token issued. Global client resolves across any tenant. |
| C2 | `GLOBAL` client access on Tenant B | Request token via `/villsyn/oauth2/token` with `client_id=super-admin-client` | `200 OK` + Token issued. Client matches `tenant_id IN ('GLOBAL', 'villsyn')`. |
| C3 | Direct Master DB Query (No Tenant DB Table) | Execute OAuth2 authorization request while `oauth2_registered_client` is dropped from tenant schema | `200 OK`. Proves lookup bypasses `TenantRoutingDataSource` and queries `masterJdbcOperations` directly. |

### 10.2 Tenant-Specific Client Isolation

| # | Scenario | Input / Setup | Expected Result |
|---|---|---|---|
| C4 | Valid access for tenant-specific client | Client `villsyn-portal-client` with `tenant_id='villsyn'`. Request via `/villsyn/oauth2/token` | `200 OK` + Token issued. Client belongs to matching tenant context. |
| C5 | Cross-tenant access block | Client `villsyn-portal-client` (`tenant_id='villsyn'`). Request via `/system-admin/oauth2/token` | `403 AccessDeniedException` / `invalid_client`. Client restricted to `villsyn` context. |
| C6 | Unknown client ID | Request via `/{tenantId}/oauth2/token` with non-existent `client_id=unknown-client` | `401 Unauthorized` / `invalid_client`. Empty result from `auth-master`. |

### 10.3 Centralized Client Registration API & Persistence

| # | Scenario | Input / Setup | Expected Result |
|---|---|---|---|
| C7 | Register new client via `TenantAwareRegisteredClientRepository` | Invoke `save(registeredClient)` under `TenantContextHolder.setTenantId("villsyn")` | Persisted into `auth-master.oauth2_registered_client` with `tenant_id='villsyn'`. |
| C8 | Register global portal client | Save client entity with explicit `tenant_id='GLOBAL'` | Persisted into `auth-master.oauth2_registered_client`. Accessible across all tenants. |

---

## Priority Matrix

| Priority | Module | Reason |
|---|---|---|
| 🔴 Critical | OAuth2 Authorization + Token (M1) | Core auth infrastructure |
| 🔴 Critical | Centralized Client Architecture (M10) | Multi-tenant client isolation & master DB routing |
| 🔴 Critical | User Registration + Login (4.1, 9.1) | Primary user journey |
| 🟠 High | Session Management (M6) | Security feature |
| 🟠 High | Tenant Provisioning (M2, M3) | SaaS core feature |
| 🟡 Medium | User Management CRUD (M4) | Admin operations |
| 🟡 Medium | MFA (M5) | Security hardening |
| 🟢 Low | Tenant Settings (M7) | Config feature |
| 🟢 Low | WebAuthn (M8) | Advanced feature |
