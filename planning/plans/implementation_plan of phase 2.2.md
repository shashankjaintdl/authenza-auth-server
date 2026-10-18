# Multi-Factor Authentication (TOTP) Implementation Plan

## Background

The codebase already has scaffolding for MFA:
- `User` model has `mfaEnabled: Boolean` (already a DB column from V0.0.3).
- `VerificationTokenType.MFA_BACKUP_CODE` enum value already exists.
- The DB schema has `mfa_enabled BOOLEAN` in `application_user` already.

What is **missing**:
1. `application_user.mfa_secret` column (the encrypted TOTP secret).
2. The actual TOTP library and service logic.
3. The multi-step login journey (password → MFA challenge → full auth).
4. Enrollment / management API endpoints.
5. The `mfa-verify.html` Thymeleaf page.

---

## Architecture Overview

```
STEP 1 — Password OK (form POST to /{tenantId}/login)
     │
     └─ If mfa_enabled = true:
           │  Store username + tenant + savedRequest in HTTP session
           └─ Redirect → /{tenantId}/mfa-verify  (NOT fully authenticated yet)
     │
     └─ If mfa_enabled = false:
           └─ Fully authenticate normally (existing flow)

STEP 2 — TOTP code form POST to /{tenantId}/mfa-verify
     │
     ├─ Valid code → authenticate principal in SecurityContext + redirect to saved request
     └─ Invalid code → redirect back to /{tenantId}/mfa-verify?error
```

Spring Security does **not natively support** multi-step form login. We implement this as a **custom filter + session-based pending auth** pattern — the cleanest approach for the existing architecture.

---

## Proposed Changes

### Component 1 — DB Schema

#### [NEW] `auth-tenant-schema/.../V0.0.6/ddl.xml`
Adds `mfa_secret VARCHAR(64)` (base32-encoded, AES-encrypted at rest) to `application_user`.

> `mfa_enabled` already exists (V0.0.3). No duplicate migration needed for it.

#### MODIFY `db.changelog-tenant.xml`
Add `<include file="V0.0.6/ddl.xml" .../>`.

---

### Component 2 — TOTP Library Dependency

#### MODIFY `auth-server-core/build.gradle`
Add the `dev.samstevens.totp:totp-spring-boot-starter` library (the most widely-used Spring-native TOTP library — Google Authenticator compatible, QR code generation included):

```gradle
// TOTP (RFC 6238) for Google Authenticator MFA
implementation 'dev.samstevens.totp:totp-spring-boot-starter:1.7.1'
```

#### MODIFY `auth-iam-service/build.gradle` 
Same dependency (for enrollment API in IAM service).

---

### Component 3 — TOTP Service (`auth-server-core`)

#### [NEW] `TotpService.java` (in `com.authenza.core.security`)

Wraps the `dev.samstevens.totp` library providing:
- `generateSecret()` → base32 secret string
- `getQrCodeDataUri(secret, username, tenantId)` → base64 PNG data URI for the QR
- `verifyCode(secret, code)` → boolean (with ±1 time-window tolerance)

---

### Component 4 — Multi-Step Login Filter (`auth-server-core`)

#### [NEW] `MfaAuthenticationFilter.java` (in `com.authenza.core.security`)

A `OncePerRequestFilter` that intercepts `POST /{tenantId}/mfa-verify`:
1. Reads `pendingMfaUsername` and `pendingMfaTenant` from the HTTP session.
2. If absent → redirect to `/{tenantId}/login` (session expired).
3. Fetches `mfa_secret` from DB for the user.
4. Calls `TotpService.verifyCode(secret, submittedCode)`.
5. On success:
   - Loads full `UserDetails` via `JdbcTenantUserDetailsService`.
   - Creates `UsernamePasswordAuthenticationToken` and sets it in `SecurityContextHolder`.
   - Persists it in the session via `HttpSessionSecurityContextRepository`.
   - Clears session pending-MFA attributes.
   - Redirects to the saved OAuth2 authorize URL (or `/{tenantId}/`).
6. On failure:
   - Records failed brute-force attempt.
   - Redirects to `/{tenantId}/mfa-verify?error`.

#### [NEW] `MfaChallengeController.java` (in `com.authenza.core.web`)

Serves the MFA challenge page:
- `GET /{tenantId}/mfa-verify` → renders `mfa-verify.html`.
- Gating: if session has no `pendingMfaUsername`, redirect to login.

---

### Component 5 — Modified Login Success Handler (`auth-server-core`)

#### MODIFY `SecurityConfig.java` — `tenantAwareAuthenticationSuccessHandler()`

After successful password auth, check if MFA is enabled **before** completing authentication:

```java
// After password validates successfully:
UserDetails details = userDetailsService.loadUserByUsername(username);
boolean mfaEnabled = isMfaEnabled(username); // JDBC query

if (mfaEnabled) {
    // Store pending state & saved request in session — do NOT set SecurityContext auth
    request.getSession().setAttribute("pendingMfaUsername", username);
    request.getSession().setAttribute("pendingMfaTenant", tenantId);
    // Preserve the saved OAuth2 authorize request in session (already there)
    response.sendRedirect("/" + tenantId + "/mfa-verify");
    return;
}

// MFA not enabled — proceed as normal
bruteForceProtectionService.resetFailedAttempts(username, tenantId);
// ... existing redirect logic
```

The success handler needs access to a lightweight DB query to check `mfa_enabled`. This is done by injecting `JdbcTemplate` (or a thin `MfaStatusService`).

---

### Component 6 — New Thymeleaf Template

#### [NEW] `auth-server-core/src/main/resources/templates/mfa-verify.html`

Matching the dark-mode split-layout design of `login.html`:
- 6-digit numeric input (auto-focus, auto-submit on 6 chars).
- Error banner for `?error` param.
- "Use a backup code" link.
- Timer/refresh hint ("Codes rotate every 30 seconds").

---

### Component 7 — Permit MFA Endpoints in Security Config

#### MODIFY `SecurityConfig.java` — `defaultSecurityFilterChain()`

```java
.requestMatchers("/{tenantId}/mfa-verify").permitAll()
```

And register `MfaAuthenticationFilter` in the chain:

```java
.addFilterAfter(mfaAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
```

---

### Component 8 — MFA Enrollment API (`auth-iam-service`)

#### [NEW] `MfaService.java` (in `com.authenza.iam.service`)

- `setupMfa(Long userId)` → generates TOTP secret, saves **unconfirmed** `mfa_secret` to DB, returns QR code data URI + the raw secret (for manual entry).
- `confirmMfa(Long userId, String code)` → verifies code against stored secret; if valid, sets `mfa_enabled = true`.
- `disableMfa(Long userId, String code)` → verifies code, then sets `mfa_enabled = false`, clears `mfa_secret`.

#### MODIFY `UserController.java` — add MFA management endpoints

```
POST /api/v1/users/{userId}/mfa/setup    → returns { qrCodeUri, secret }
POST /api/v1/users/{userId}/mfa/confirm  → body: { code }
POST /api/v1/users/{userId}/mfa/disable  → body: { code }
```

#### MODIFY `IamServiceClient.java` (in `auth-server-core`)

Add proxy methods for the three MFA endpoints above.

---

### Component 9 — DB Column for Pending/Confirmed Secret

The `User` entity and table need `mfa_secret` (nullable). The flow for a new enrollment:

1. `setupMfa` → stores secret in `mfa_secret`, but `mfa_enabled` stays `false`.
2. `confirmMfa` → validates TOTP, sets `mfa_enabled = true`. Account is now MFA-protected.
3. If the user never confirms → `mfa_secret` exists but `mfa_enabled = false` → treated as MFA off.

---

## File Summary

| File | Action | Module |
|--|--|--|
| `V0.0.6/ddl.xml` | NEW | `auth-tenant-schema` |
| `db.changelog-tenant.xml` | MODIFY | `auth-tenant-schema` |
| `auth-server-core/build.gradle` | MODIFY | `auth-server-core` |
| `auth-iam-service/build.gradle` | MODIFY | `auth-iam-service` |
| `TotpService.java` | NEW | `auth-server-core` |
| `MfaAuthenticationFilter.java` | NEW | `auth-server-core` |
| `MfaChallengeController.java` | NEW | `auth-server-core` |
| `SecurityConfig.java` | MODIFY | `auth-server-core` |
| `JdbcTenantUserDetailsService.java` | MODIFY | `auth-server-core` |
| `mfa-verify.html` | NEW | `auth-server-core` |
| `MfaService.java` | NEW | `auth-iam-service` |
| `UserController.java` | MODIFY | `auth-iam-service` |
| `User.java` | MODIFY | `auth-common` |
| `IamServiceClient.java` | MODIFY | `auth-server-core` |
| `application.yaml` (auth-server-core) | MODIFY | `auth-server-core` |

---

## User Review Required

> [!IMPORTANT]
> **MFA secret encryption**: TOTP secrets should be encrypted at rest (AES-256). This requires a static `app.mfa.encryption-key` in `application.yaml`. The plan will implement this. **You must set a real key in production** — the default dev key in YAML is insecure.

> [!IMPORTANT]
> **Intercepting Spring Security's success handler for the MFA redirect**: The cleanest approach is to intercept the authenticated principal *inside the success handler* — checking `mfa_enabled` via a JdbcTemplate select, then *not* completing the security context setup if MFA is pending, instead storing state in session. The user won't be considered "authenticated" by Spring Security until after they pass the TOTP check. This is slightly non-standard but is the established pattern for multi-step auth without a full SAML/OAuth2 custom flow.

> [!WARNING]
> **Backup codes**: The existing `VerificationTokenType.MFA_BACKUP_CODE` enum suggests backup codes were planned. The current plan focuses on TOTP only. Backup code generation (8x random codes stored hashed) can be implemented as a follow-up.

---

## Verification Plan

### Automated (Build)
```bash
./gradlew :auth-server-core:compileJava :auth-iam-service:compileJava
```

### Manual Flow Testing
1. Call `POST /api/v1/users/{userId}/mfa/setup` from IAM → get QR code.
2. Scan QR in Google Authenticator.
3. Call `POST /api/v1/users/{userId}/mfa/confirm` with a valid code → MFA enabled.
4. Attempt login → confirm redirect to `/{tenantId}/mfa-verify`.
5. Enter correct TOTP code → confirm full login completes.
6. Enter wrong code → confirm `?error` banner.
7. Call `POST /api/v1/users/{userId}/mfa/disable` → MFA disabled.
8. Login again → confirm direct auth (no MFA step).
