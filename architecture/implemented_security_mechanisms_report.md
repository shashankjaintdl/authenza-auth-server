# Report: Implemented Security Gates & Multi-Tenant Routing Mechanisms

This report details the exact implementations and code structures of the security filters, WebAuthn bridge, multi-step MFA authentication flows, and routing policies currently present in the codebase.

---

## 1. Multi-Step MFA Authentication Flow

The multi-step MFA authentication flow separates initial credential verification from TOTP verification to secure sessions before authorization context is fully granted.

### Interceptor: `MfaAuthenticationFilter`
* **Route Intercepted:** `POST /{tenantId}/mfa-verify`
* **Pending Session Management:** Uses `AuthPendingStateStore` to retrieve pending user context (`username`, `tenantId`, `userId`) from Redis using a short-lived, single-use `mfaToken`.
* **Flow Pipeline:**
  1. Validates the presence of `mfaToken` in request parameters.
  2. Resolves the tenant database context (`TenantContextHolder.setTenantId(tenantId)`).
  3. Decrypts and verifies the submitted 6-digit TOTP code using `TotpService`.
  4. **On Success:** Creates a complete `UsernamePasswordAuthenticationToken`, sets the Spring Security `SecurityContext`, records the active session via `SessionRecordingService` for device tracking, and redirects the user to the originally requested OAuth2 authorize page.
  5. **On Failure:** Increments brute-force count, generates a new one-time token, and redirects back to `/{tenantId}/mfa-verify?mfaToken={newMfaToken}&error`.

---

## 2. Passwordless Login: WebAuthn Bridge Flow

Because the FIDO2 library (`yubico/webauthn-server-core`) lives in the isolated `auth-iam-service` (Port 8082) while the Spring Security Session context lives in `auth-server-core` (Port 8081), a secure bridge protocol is implemented.

### Controller: `WebAuthnBridgeController`
* **Route:** `POST /{tenantId}/webauthn/bridge`
* **HMAC-SHA256 Signed Bridge Token:**
  * When authentication succeeds in `auth-iam-service`, it generates a short-lived (60-second) signed token: `Base64(userId:tenantId:expiryTime).Signature`.
  * The frontend redirects to `auth-server-core` posting this token.
* **Bridge Flow Pipeline:**
  1. Validates the token's HMAC signature using a shared secret.
  2. Rejects tokens past their 60-second TTL to prevent replay attacks.
  3. Executes a reverse lookup (userId to username) in the resolved tenant database schema.
  4. Evaluates account state constraints (locked, disabled).
  5. Evaluates the **Force Password Change Gate** (see Section 3).
  6. Authenticates the Spring Security context and updates the HTTP Session.

---

## 3. Forced Password Change Gate

If an administrator creates a temporary credential for an employee or resets their password, the user must be forced to update it before entering any secure session.

### Controller: `ForcePasswordChangeController`
* **Routes:** `GET /{tenantId}/force-password-change` and `POST /{tenantId}/force-password-change`
* **Bridge integration:** Both the standard form login handler and the `WebAuthnBridgeController` check if the user is flagged with `requires_password_change`.
* **Gate Enforcement:**
  * If flagged, authentication is halted. The active `SecurityContext` is explicitly wiped.
  * A `pwdToken` is generated in `AuthPendingStateStore` to secure the password change request.
  * The user is redirected to the password update page. Once updated successfully via `IamServiceClient`, the user is returned to the login screen with a `passwordChanged=true` parameter.

---

## 4. Multi-Tenant Routing & Switching Policy

Every tenant must reside inside physically isolated databases. Dynamic switching depends on intercepting incoming requests before they reach core filters.

### Filter: `MultiTenantSecurityFilter`
* **Route Intercepted:** Runs at the front of the filter chain (excluding static resources and error views).
* **DataSource Dynamic Context:**
  1. Extracts `tenantId` from the URL request path (e.g., `/villsyn/oauth2/authorize` -> `villsyn`).
  2. Queries the `TenantRoutingDataSource` to verify if the tenant is active and registered.
  3. Binds the context using `TenantContextHolder.setTenantId(tenantId)`.
  4. Configures the dynamically constructed `AuthorizationServerContext` with the custom path issuer (e.g. `http://auth.server/villsyn`) to enforce proper OIDC token claims isolation.
  5. **Context Cleanup:** Standard `try ... finally` block always invokes `TenantContextHolder.clear()` and `AuthorizationServerContextHolder.resetContext()` to prevent thread-local leakage.

### Switch Policy: `V0.0.11/ddl.xml`
* Introduces the `tenant_switch_policy` configuration parameter into the `tenant_settings` table.
* **Default Policy:** `REQUIRE_PASSWORD` (Zero Trust). Cross-tenant session mismatches discovered in the filter chain immediately terminate the current session, clear security contexts, and redirect to the specific target login screen.
