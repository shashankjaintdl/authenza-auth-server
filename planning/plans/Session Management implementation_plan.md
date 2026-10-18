# Active Session Management Implementation Plan

## Goal
Replace the in-memory `OAuth2AuthorizationService` with a persistent JDBC-backed one, and build session tracking + management APIs so users can view and revoke active devices.

---

## Architecture Overview

```
Login success
     │
     ▼
SecurityConfig.successHandler / MfaAuthenticationFilter
     │ records session (IP, UA, device)
     ▼
user_session table (tenant DB)  ◄──linked by──► oauth2_authorization table (tenant DB)
     │                                                    │
     ▼                                                    ▼
GET /sessions        ◄── auth-iam-service         JdbcOAuth2AuthorizationService
DELETE /sessions/:id     (lists/revokes)           (auth-server-core, revoking deletes row)
```

---

## User Review Required

> [!IMPORTANT]
> **JdbcOAuth2AuthorizationService** requires the tenant `RoutingDataSource` to be injected. We wire it as a `@Bean` in `auth-server-core`. This means token storage automatically uses the active tenant's DB — zero cross-tenant leakage.

> [!WARNING]
> The standard Spring `oauth2_authorization` DDL uses `BLOB` columns. MySQL handles these well but the column sizes must match Spring's `OAuth2AuthorizationRowMapper` exactly. We use Spring's official SQL script as the source of truth.

---

## Proposed Changes

### 1. Database — auth-tenant-schema

#### [NEW] V0.0.8/ddl.xml
Three tables:

**`oauth2_authorization`** — Spring's standard OAuth2 authorization record table:
```sql
CREATE TABLE oauth2_authorization (
  id VARCHAR(100) NOT NULL,
  registered_client_id VARCHAR(100) NOT NULL,
  principal_name VARCHAR(200) NOT NULL,
  authorization_grant_type VARCHAR(100) NOT NULL,
  authorized_scopes VARCHAR(1000),
  attributes BLOB,
  state VARCHAR(500),
  authorization_code_value BLOB,
  authorization_code_issued_at TIMESTAMP,
  authorization_code_expires_at TIMESTAMP,
  authorization_code_metadata BLOB,
  access_token_value BLOB,
  access_token_issued_at TIMESTAMP,
  access_token_expires_at TIMESTAMP,
  access_token_metadata BLOB,
  access_token_type VARCHAR(100),
  access_token_scopes VARCHAR(1000),
  oidc_id_token_value BLOB,
  oidc_id_token_issued_at TIMESTAMP,
  oidc_id_token_expires_at TIMESTAMP,
  oidc_id_token_metadata BLOB,
  refresh_token_value BLOB,
  refresh_token_issued_at TIMESTAMP,
  refresh_token_expires_at TIMESTAMP,
  refresh_token_metadata BLOB,
  user_code_value BLOB,
  user_code_issued_at TIMESTAMP,
  user_code_expires_at TIMESTAMP,
  user_code_metadata BLOB,
  device_code_value BLOB,
  device_code_issued_at TIMESTAMP,
  device_code_expires_at TIMESTAMP,
  device_code_metadata BLOB,
  PRIMARY KEY (id)
);
```

**`oauth2_authorization_consent`** — tracks consent decisions:
```sql
CREATE TABLE oauth2_authorization_consent (
  registered_client_id VARCHAR(100) NOT NULL,
  principal_name VARCHAR(200) NOT NULL,
  authorities VARCHAR(1000) NOT NULL,
  PRIMARY KEY (registered_client_id, principal_name)
);
```

**`user_session`** — our custom device/session tracker:
```sql
CREATE TABLE user_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  authorization_id VARCHAR(100),       -- links to oauth2_authorization.id
  session_token VARCHAR(200) NOT NULL UNIQUE,
  ip_address VARCHAR(45),
  user_agent VARCHAR(500),
  device_name VARCHAR(200),            -- parsed from UA: "Chrome on macOS"
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_active_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  revoked_at TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES application_user(id) ON DELETE CASCADE
);
```

#### [MODIFY] db.changelog-tenant.xml
Add `V0.0.8/ddl.xml` include.

---

### 2. auth-server-core

#### [MODIFY] SecurityConfig.java
Register `JdbcOAuth2AuthorizationService` and `JdbcOAuth2AuthorizationConsentService` beans:
```java
@Bean
public OAuth2AuthorizationService authorizationService(DataSource dataSource) {
    return new JdbcOAuth2AuthorizationService(
        new JdbcTemplate(dataSource),   // RoutingDataSource — tenant-aware
        registeredClientRepository()
    );
}

@Bean
public OAuth2AuthorizationConsentService authorizationConsentService(DataSource dataSource) {
    return new JdbcOAuth2AuthorizationConsentService(
        new JdbcTemplate(dataSource),
        registeredClientRepository()
    );
}
```

#### [NEW] SessionRecordingService.java (in auth-server-core/service)
Records a `user_session` row on successful login:
```java
public void recordSession(Long userId, String authorizationId,
                          HttpServletRequest request)
```
- Parses `User-Agent` header → device name ("Chrome on macOS")
- Extracts `X-Forwarded-For` / `remoteAddr` for IP
- Generates a `session_token` (UUID)

#### [MODIFY] SecurityConfig.successHandler
After successful non-MFA login → call `SessionRecordingService.recordSession()`.

#### [MODIFY] MfaAuthenticationFilter
After TOTP verified → call `SessionRecordingService.recordSession()`.

---

### 3. auth-iam-service

#### [NEW] SessionController.java
```
GET    /api/v1/users/{userId}/sessions          → list active sessions
DELETE /api/v1/users/{userId}/sessions/{id}     → revoke single session
DELETE /api/v1/users/{userId}/sessions          → revoke all sessions (logout everywhere)
```

#### [NEW] SessionService.java
- `listActiveSessions(userId)` — queries `user_session WHERE revoked = false`
- `revokeSession(userId, sessionId)` — sets `revoked = true`, deletes `oauth2_authorization` row via `OAuth2AuthorizationService.remove()`
- `revokeAllSessions(userId)` — revokes all active sessions

#### [NEW] SessionResponse.java (DTO)
```json
{
  "id": 12,
  "deviceName": "Chrome on macOS",
  "ipAddress": "103.56.x.x",
  "createdAt": "2026-04-19T10:00:00Z",
  "lastActiveAt": "2026-04-19T14:00:00Z",
  "current": true
}
```

---

### 4. Tenant-Configurable Session Expiry

#### [MODIFY] application.yaml (auth-server-core)
Add a default application property for session TTL:
```yaml
authenza:
  security:
    session:
      default-ttl-days: 30
```

#### [MODIFY] SessionRecordingService.java
Update the service to dynamically look up the session TTL policy from the `tenant_settings` table (introduced in V0.0.7), falling back to the `application.yaml` default:
- Inject `@Value("${authenza.security.session.default-ttl-days:30}")`.
- Query `tenant_settings` for `setting_key = 'session_ttl_days'`.
- Calculate `expires_at = Instant.now().plus(...)`.
- Include `expires_at` in the `INSERT INTO user_session` statement.

#### [MODIFY] SessionService.java
Update `listActiveSessions(userId)` SQL query to actively filter out expired sessions:
```sql
  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
```

---

## Verification Plan

### Automated
```bash
./gradlew :auth-server-core:compileJava
./gradlew :auth-iam-service:compileJava
./gradlew :auth-tenant-schema:compileJava
```

### Manual
1. Login → `GET /sessions` → see 1 active session with correct IP/device
2. Open incognito → login again → `GET /sessions` → see 2 sessions
3. Check DB → verify `expires_at` is populated correctly.
4. Manually set `expires_at` to the past in DB → `GET /sessions` → verify expired session is hidden.
5. `DELETE /sessions/{id}` → try using old access token → 401 ✅
6. `DELETE /sessions` → all revoked → login required everywhere

---

## Open Questions

> [!NOTE]
> Session revocation makes the `oauth2_authorization` record disappear. For **immediate** token invalidation on resource servers, they need to call the token introspection endpoint (`/oauth2/introspect`) rather than validating JWTs locally. If your resource servers do local JWT validation (which is the default), the access token remains technically valid until it expires. Revocation only truly blocks re-use of the **refresh token**. Want to enable introspection-based validation too?
