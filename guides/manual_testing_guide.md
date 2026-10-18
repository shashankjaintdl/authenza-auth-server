# Manual Testing Guide — Authenza Auth Server

This document covers all the critical manual test scenarios for the Authenza auth server, organized into:
1. **Regression Tests** — Verify existing functionality is not broken.
2. **New Redis Migration Tests** — Verify the newly migrated WebAuthn, MFA, and Force-Password-Change flows work correctly in a distributed setup.

> **Pre-conditions:**
> - Auth server (`auth-server-core`) is running on `http://localhost:8081`
> - IAM service (`auth-iam-service`) is running on `http://localhost:8082`
> - Redis is running on `localhost:6379`
> - A test tenant (e.g., `acme`) exists in the system
> - A test user exists in the `acme` tenant

---

## Section 1: Regression Tests (Existing Features)

These tests verify that the Redis migration has not broken any existing behavior.

---

### 1.1 Standard Password Login (No MFA)

**Scenario:** A normal user logs in successfully with just username and password.

**Steps:**
1. Navigate to `http://localhost:8081/acme/login`
2. Enter a valid `username` and `password`
3. Click **Sign In**

**Expected Results:**
- ✅ User is redirected to the OAuth2 consent/authorization screen or the tenant root `http://localhost:8081/acme/`
- ✅ No error messages are shown
- ✅ The user session is established (browser has a valid session cookie)

---

### 1.2 Failed Password Login (Wrong Password)

**Scenario:** A user enters an incorrect password.

**Steps:**
1. Navigate to `http://localhost:8081/acme/login`
2. Enter a valid `username` and an **incorrect** `password`
3. Click **Sign In**

**Expected Results:**
- ✅ User is redirected back to `http://localhost:8081/acme/login?error`
- ✅ An error message like "Invalid credentials" is displayed
- ✅ A brute-force counter is incremented in Redis under key `bf:acme:<username>`

---

### 1.3 Account Lockout After Repeated Failures

**Scenario:** Brute-force protection locks the account after exceeding the max attempt threshold.

**Steps:**
1. Navigate to `http://localhost:8081/acme/login`
2. Submit the form **5+ times** with an incorrect password for the same user
3. Attempt login with the **correct** password after being locked

**Expected Results:**
- ✅ After the threshold is reached, the user is redirected to `http://localhost:8081/acme/login?locked`
- ✅ A "locked" message is displayed
- ✅ The account status is updated to `LOCKED` in the tenant database
- ✅ The Redis key `bf:acme:<username>` is set with a TTL (verify with `redis-cli TTL bf:acme:<username>`)

---

### 1.4 Forgot Password Flow

**Scenario:** A user requests a password reset.

**Steps:**
1. Navigate to `http://localhost:8081/acme/forgot-password`
2. Enter the registered email address
3. Click **Send Reset Link**

**Expected Results:**
- ✅ The page shows a confirmation message ("Check your email...")
- ✅ An email is received with a reset link containing a token
- ✅ Clicking the link navigates to `http://localhost:8081/acme/reset-password?token=<token>`
- ✅ Entering a new password successfully updates it and redirects to login

---

### 1.5 User Registration Flow

**Scenario:** A new user registers an account.

**Steps:**
1. Navigate to `http://localhost:8081/acme/register`
2. Fill in name, email, username, and password fields
3. Click **Register**

**Expected Results:**
- ✅ Confirmation page shown ("Verify your email")
- ✅ A verification email is sent to the entered address
- ✅ Clicking the link in the email activates the account and redirects to login

---

### 1.6 Session Revocation (Logout)

**Scenario:** A user logs in, then logs out, and verifies the session is invalidated.

**Steps:**
1. Log in with a valid user
2. Complete the OAuth2 authorization flow to get an access token
3. Call any protected IAM API with the token to confirm it works
4. Call the logout / revoke endpoint
5. Call the same protected IAM API again with the same token

**Expected Results:**
- ✅ The first API call returns `200 OK`
- ✅ After revocation, the second API call returns `401 Unauthorized`
- ✅ A Redis key `authenza:revoked:session:<sessionId>` exists with a TTL (verify with `redis-cli KEYS authenza:revoked:*`)

---

### 1.7 Rate Limiting

**Scenario:** Rate limiting blocks excessive requests from a single tenant.

**Steps:**
1. Send more than 200 requests within 1 minute to any API endpoint with the `X-Tenant-ID: acme` header (using a tool like `ab` or `hey`)
2. Check the HTTP response headers on normal requests

**Expected Results:**
- ✅ Normal requests contain `X-RateLimit-Limit: 200` and `X-RateLimit-Remaining: <n>` headers
- ✅ After limit is exceeded, requests return HTTP `429 Too Many Requests`
- ✅ The `429` response includes `X-RateLimit-Retry-After` and `X-RateLimit-Reset` headers

**Verify Rate Limit Bypass (OIDC Metadata Endpoints):**
- ✅ `GET http://localhost:8081/acme/oauth2/jwks` always returns `200 OK` even if rate limit is exceeded
- ✅ `GET http://localhost:8081/acme/.well-known/openid-configuration` always returns `200 OK` even if rate limit is exceeded

---

## Section 2: New Redis Migration Tests (MFA & WebAuthn)

These tests verify that all the newly migrated features work correctly using the Redis-backed state store.

---

### 2.1 MFA Login — Happy Path

**Scenario:** A user with MFA enabled logs in, scans the Redis state, and completes TOTP verification.

**Pre-condition:** User `alice@acme.com` has `mfa_enabled = true` and has completed MFA enrollment.

**Steps:**
1. Navigate to `http://localhost:8081/acme/login`
2. Enter `alice@acme.com` credentials and click **Sign In**
3. **Immediately after redirect**, check Redis:
   ```bash
   redis-cli KEYS "auth:pending:mfa:*"
   ```
4. Verify the key exists and has a TTL of ~5 minutes:
   ```bash
   redis-cli TTL "auth:pending:mfa:<token>"
   ```
5. Verify the redirect URL contains `?mfaToken=<token>`
6. Enter the correct 6-digit TOTP code from the authenticator app
7. Click **Verify**

**Expected Results:**
- ✅ After password auth, the browser is redirected to `http://localhost:8081/acme/mfa-verify?mfaToken=<uuid>`
- ✅ A Redis key exists at `auth:pending:mfa:<uuid>` with TTL ~300 seconds
- ✅ After TOTP verification, the user is fully authenticated and redirected to the OAuth2 flow or tenant root
- ✅ The Redis key `auth:pending:mfa:<uuid>` is **deleted** from Redis after successful verification
- ✅ No session attributes `PENDING_MFA_*` exist on the HTTP session

---

### 2.2 MFA Login — Invalid TOTP Code

**Scenario:** A user enters a wrong TOTP code.

**Steps:**
1. Complete password login for a user with MFA enabled
2. On the `/mfa-verify` page, enter an **incorrect** 6-digit code (e.g., `000000`)
3. Click **Verify**

**Expected Results:**
- ✅ The user is redirected back to `/mfa-verify?mfaToken=<NEW_token>&error`
- ✅ A new `mfaToken` is present in the URL (previous token was consumed and re-issued)
- ✅ An error message ("Invalid code") is displayed
- ✅ The new Redis key `auth:pending:mfa:<NEW_uuid>` exists with a fresh TTL

---

### 2.3 MFA Login — Expired Token / Direct Navigation

**Scenario:** A user tries to navigate directly to `/mfa-verify` without first completing the password step.

**Steps:**
1. Navigate directly to `http://localhost:8081/acme/mfa-verify` (no `mfaToken` parameter)
2. Also try: `http://localhost:8081/acme/mfa-verify?mfaToken=invalid-token`

**Expected Results:**
- ✅ In both cases, the user is immediately redirected back to `http://localhost:8081/acme/login`
- ✅ No error stack trace or 500 error is shown

---

### 2.4 MFA Setup — First Enrollment

**Scenario:** A user has `mfa_enabled = true` but has never scanned a QR code (first-time setup).

**Pre-condition:** User `bob@acme.com` has `mfa_enabled = true` but `mfa_secret = null` in the database.

**Steps:**
1. Navigate to `http://localhost:8081/acme/login`
2. Enter `bob@acme.com` credentials
3. After password auth, verify the redirect goes to `http://localhost:8081/acme/mfa-setup?mfaToken=<uuid>`
4. Check Redis: `redis-cli KEYS "auth:pending:mfa:*"` — key should exist
5. Scan the QR code or enter the secret into an authenticator app
6. Enter the correct 6-digit code from the app
7. Click **Confirm**

**Expected Results:**
- ✅ The MFA setup page displays a valid QR code
- ✅ After confirming, the user is redirected to `/mfa-verify?mfaToken=<uuid>`
- ✅ `mfa_enabled = true` and `mfa_secret` is persisted in the tenant database
- ✅ Entering the correct TOTP code on the verify page completes login

---

### 2.5 Force Password Change — Standard Login

**Scenario:** An admin user has set `requires_password_change = true` for a user account. The user attempts to log in with a regular password.

**Pre-condition:** User `charlie@acme.com` has `requires_password_change = true` in the database.

**Steps:**
1. Navigate to `http://localhost:8081/acme/login`
2. Enter `charlie@acme.com` credentials
3. After password auth, check the redirect URL
4. Check Redis: `redis-cli KEYS "auth:pending:pwd-change:*"`
5. Enter a **new** password and confirmation on the change page
6. Click **Change Password**

**Expected Results:**
- ✅ The browser is redirected to `/force-password-change?pwdToken=<uuid>`
- ✅ A Redis key exists at `auth:pending:pwd-change:<uuid>` with TTL ~300 seconds
- ✅ After changing password, the user is redirected to `http://localhost:8081/acme/login?passwordChanged=true`
- ✅ The Redis key is **deleted** from Redis after the change
- ✅ `requires_password_change = false` is updated in the tenant database
- ✅ The user can now log in normally with the new password

---

### 2.6 Force Password Change — Mismatched Passwords

**Scenario:** A user enters two different passwords during a forced password change.

**Steps:**
1. Trigger the force-password-change flow (as in 2.5)
2. On the change page, enter `newpassword` and `differentpassword` in the two fields
3. Click **Change Password**

**Expected Results:**
- ✅ The same page is shown with an error message: "Passwords do not match"
- ✅ The `pwdToken` in the URL is refreshed to a new UUID
- ✅ The Redis state is re-saved so the user can retry without going back to login

---

### 2.7 Force Password Change — Direct Navigation (Security Gate)

**Scenario:** A user tries to navigate directly to the force-password-change page without going through login.

**Steps:**
1. Navigate directly to `http://localhost:8081/acme/force-password-change` (no `pwdToken`)
2. Also try: `http://localhost:8081/acme/force-password-change?pwdToken=fake-token`

**Expected Results:**
- ✅ In both cases, the user is immediately redirected back to `http://localhost:8081/acme/login`
- ✅ No error stack trace or 500 error is shown

---

### 2.8 Passkey / WebAuthn Registration

**Scenario:** An authenticated user registers a new fingerprint/passkey credential.

**Pre-condition:** User is logged in. WebAuthn is enabled for the `acme` tenant.

**Steps:**
1. Log in as a normal user
2. Navigate to the passkey management page in the tenant portal
3. Click **Add Passkey** / **Register Fingerprint**
4. Complete the platform authenticator dialog (Touch ID / Windows Hello)
5. Check Redis: `redis-cli KEYS "webauthn:challenge:register:*"` (only briefly visible during ceremony)

**Expected Results:**
- ✅ The registration ceremony dialog appears in the browser
- ✅ After completing biometric authentication, the passkey is saved
- ✅ The new passkey appears in the **Manage Passkeys** list
- ✅ The Redis challenge key `webauthn:challenge:register:<requestId>` is **deleted** after success

---

### 2.9 Passkey / WebAuthn Login (Fingerprint Sign In)

**Scenario:** A user who has registered a passkey authenticates using biometrics.

**Pre-condition:** User has at least one passkey registered.

**Steps:**
1. Navigate to `http://localhost:8081/acme/login`
2. Click **Sign in with Passkey** button
3. Complete the platform authenticator dialog (Touch ID / Windows Hello)
4. Check Redis: `redis-cli KEYS "webauthn:challenge:auth:*"` (only briefly visible)

**Expected Results:**
- ✅ The authentication ceremony dialog appears in the browser
- ✅ After biometric confirmation, the user is fully logged in
- ✅ The user is redirected to the OAuth2 flow or tenant root
- ✅ The Redis challenge key `webauthn:challenge:auth:<requestId>` is **deleted** after success

---

### 2.10 Passkey Login + Force Password Change

**Scenario:** A user logs in via passkey but also has `requires_password_change = true`.

**Pre-condition:** User has a registered passkey AND `requires_password_change = true`.

**Steps:**
1. Navigate to `http://localhost:8081/acme/login`
2. Click **Sign in with Passkey** and complete biometric auth
3. Check the redirect URL

**Expected Results:**
- ✅ The user is **NOT** fully authenticated
- ✅ The browser is redirected to `/force-password-change?pwdToken=<uuid>`
- ✅ A Redis key `auth:pending:pwd-change:<uuid>` exists
- ✅ After changing the password, login completes normally

---

### 2.11 Redis State Expiry (5-Minute Window)

**Scenario:** A pending MFA or password-change state expires after 5 minutes.

**Steps:**
1. Trigger the MFA flow for a user
2. Wait 5 minutes **without** submitting the TOTP code
3. Check Redis: `redis-cli TTL "auth:pending:mfa:<token>"` — should return `-2` (expired)
4. Try to submit the TOTP code with the expired `mfaToken` URL

**Expected Results:**
- ✅ After 5 minutes, the Redis key is automatically deleted (no manual cleanup needed)
- ✅ Submitting the TOTP code with the expired token redirects to `/{tenantId}/login?error`
- ✅ No server-side error or stack trace

---

## Section 3: Redis State Verification Commands

Use these commands to inspect Redis state during testing:

```bash
# List all WebAuthn registration challenges
redis-cli KEYS "webauthn:challenge:register:*"

# List all WebAuthn authentication challenges
redis-cli KEYS "webauthn:challenge:auth:*"

# List all pending MFA states
redis-cli KEYS "auth:pending:mfa:*"

# List all pending force-password-change states
redis-cli KEYS "auth:pending:pwd-change:*"

# Inspect a specific pending state value
redis-cli GET "auth:pending:mfa:<token>"

# Check TTL of a key
redis-cli TTL "auth:pending:mfa:<token>"

# List all revoked session blacklist keys
redis-cli KEYS "authenza:revoked:session:*"

# List all brute-force counter keys
redis-cli KEYS "bf:*"
```

---

## Summary Checklist

| # | Scenario | Category |
|---|---|---|
| 1.1 | Standard password login | Regression |
| 1.2 | Failed login / wrong password | Regression |
| 1.3 | Account lockout after repeated failures | Regression |
| 1.4 | Forgot password email flow | Regression |
| 1.5 | New user registration + email verification | Regression |
| 1.6 | Session revocation after logout | Regression |
| 1.7 | Rate limiting + OIDC endpoint bypass | Regression |
| 2.1 | MFA login — successful TOTP verification | **New** |
| 2.2 | MFA login — wrong TOTP code | **New** |
| 2.3 | MFA — direct navigation / expired token | **New** |
| 2.4 | MFA setup — first enrollment | **New** |
| 2.5 | Force password change — standard login | **New** |
| 2.6 | Force password change — mismatched passwords | **New** |
| 2.7 | Force password change — direct navigation | **New** |
| 2.8 | Passkey registration | **New** |
| 2.9 | Passkey login (fingerprint sign in) | **New** |
| 2.10 | Passkey login + force password change | **New** |
| 2.11 | Redis state expiry (5-minute window) | **New** |
