# Authenza — Complete Application Flow Diagrams

---

## 1. System Architecture

```mermaid
graph TB
    subgraph Client["Client Layer"]
        Browser["Browser / Angular Portal"]
        Postman["API Client / Postman"]
    end

    subgraph Core["auth-server-core port 8081"]
        LoginUI["Login UI Thymeleaf"]
        MfaSetupUI["MFA Setup UI"]
        MfaVerifyUI["MFA Verify UI"]
        OAuth2["Spring Authorization Server OAuth2"]
        SecurityFilter["Security Filter Chain"]
        IamProxy["IamServiceClient HTTP Proxy"]
    end

    subgraph IAM["auth-iam-service port 8082"]
        UserCtrl["UserController"]
        MfaCtrl["MfaController"]
        SettingsCtrl["TenantSettingsController"]
        UserSvc["UserService"]
        MfaSvc["MfaService"]
    end

    subgraph Master["auth-master-service port 8083"]
        TenantCtrl["TenantRegistrationController"]
        ProvSvc["TenantProvisioningService"]
        MigRunner["TenantMigrationRunner"]
    end

    subgraph Data["Data Layer"]
        MasterDB[("auth-master DB\ntenant registry")]
        TenantDB1[("system-admin DB\nusers and settings")]
        TenantDBN[("acme-corp DB\nusers and settings")]
        Redis[("Redis\nbrute force counters\nand tenant events")]
    end

    Browser --> Core
    Postman --> Core
    Postman --> IAM
    Postman --> Master

    Core --> IAM
    Master --> MasterDB
    Master --> TenantDB1
    Master --> TenantDBN
    Master --> Redis

    IAM --> TenantDB1
    IAM --> TenantDBN
    Core --> TenantDB1
    Core --> TenantDBN
    Core --> Redis
```

---

## 2. Tenant Provisioning Flow

```mermaid
sequenceDiagram
    participant Admin as Admin/API
    participant Master as auth-master-service
    participant MasterDB as auth-master DB
    participant TenantDB as New Tenant DB
    participant Redis as Redis

    Admin->>Master: POST /api/v1/tenants tenantId jdbcUrl
    Master->>MasterDB: INSERT INTO tenant
    Master->>TenantDB: Liquibase run db.changelog-tenant.xml
    Note over TenantDB: Creates tables application_user roles oauth2_registered_client tenant_settings
    TenantDB-->>Master: Migration complete
    Master->>Redis: PUBLISH TenantProvisionedEvent
    Redis-->>Core: auth-server-core registers new DataSource
    Master-->>Admin: 201 Created
```

---

## 3. User Registration Flow

```mermaid
flowchart TD
    A([User visits tenantId/register]) --> B[Fill name email password]
    B --> C{Password meets policy?}
    C -->|No| D[Show validation errors]
    D --> B
    C -->|Yes| E[POST to auth-server-core RegisterController]
    E --> F[Proxy to IAM POST /api/v1/users/register]
    F --> G{Email already exists?}
    G -->|Yes| H[409 Conflict show error]
    H --> B
    G -->|No| I[Create user status=PENDING_VERIFICATION]
    I --> J[Generate email verification token]
    J --> K[Send verification email via Notification Service]
    K --> L[Show Check your email page]
    L --> M([User clicks verification link])
    M --> N[GET tenantId/verify-email?token=xxx]
    N --> O{Token valid and not expired?}
    O -->|No| P[410 Gone expired link]
    O -->|Yes| Q[Set status=ACTIVE]
    Q --> R([Redirect to login])
```

---

## 4. Login Flow with Brute Force Protection

```mermaid
flowchart TD
    A([User visits login]) --> B[Enter username and password]
    B --> C[POST tenantId/login]
    C --> D{Account LOCKED?}
    D -->|Yes still in window| E[Reject Account locked message]
    E --> A
    D -->|No or Lock expired| F{Lock expired?}
    F -->|Yes| G[Auto-unlock set status=ACTIVE clear locked_until]
    G --> H{Password correct?}
    F -->|No| H
    H -->|No| I[Increment failed_attempts in Redis]
    I --> J{Reached max attempts?}
    J -->|Yes| K[Lock account set status=LOCKED locked_until=now+duration]
    K --> L[Show Account locked error]
    J -->|No| M[Show Invalid credentials error]
    M --> B
    H -->|Yes| N[Reset failed_attempts in Redis]
    N --> O{isMfaRequired?}
    O -->|No| P[Login complete OAuth2 flow or dashboard]
    O -->|Yes| Q[Clear SecurityContext user NOT logged in yet]
    Q --> R[Store PENDING_MFA_USERNAME TENANT USER_ID in session]
    R --> S{isMfaFullyEnrolled?}
    S -->|Yes mfa_enabled=true AND secret exists| T([Redirect to mfa-verify])
    S -->|No secret null or mfa_enabled=false| U([Redirect to mfa-setup])
```

---

## 5. MFA Enrollment Flow Setup

```mermaid
flowchart TD
    A([GET tenantId/mfa-setup]) --> B{PENDING_MFA_USERNAME in session?}
    B -->|No| C([Redirect to login])
    B -->|Yes| D[Call IAM POST users/userId/mfa/setup]
    D --> E{Already fully enrolled?}
    E -->|Yes| F[Throw error disable first]
    E -->|No| G[Generate TOTP secret via RFC 6238]
    G --> H[Encrypt secret with AES-256-GCM]
    H --> I[Save encrypted secret to DB]
    I --> J[Reset mfa_enabled=false]
    J --> K[Generate QR code data URI]
    K --> L[Return qrCodeUri and raw secret]
    L --> M[Render mfa-setup.html with QR code]
    M --> N{User action?}
    N -->|Closes browser| O[DB: mfa_enabled=false mfa_secret=set]
    O --> P([Next login: isMfaRequired=true via mfa_secret not null])
    P --> Q([Redirected back to mfa-setup])
    N -->|Enters 6-digit code| R[POST tenantId/mfa-setup code=123456]
    R --> S[Call IAM POST /mfa/confirm]
    S --> T{Code valid within 30s window?}
    T -->|No| U[Show error re-display QR]
    U --> N
    T -->|Yes| V[Set mfa_enabled=true in DB]
    V --> W([Redirect to tenantId/mfa-verify])
```

---

## 6. MFA Login Challenge Flow Verify

```mermaid
flowchart TD
    A([GET tenantId/mfa-verify]) --> B{PENDING_MFA_USERNAME in session?}
    B -->|No| C([Redirect to login])
    B -->|Yes| D[Show 6-digit OTP input with 30s timer]
    D --> E[User enters code]
    E --> F[POST tenantId/mfa-verify code=123456]
    F --> G{PENDING_MFA_USERNAME still in session?}
    G -->|No or Expired| H([Redirect to login])
    G -->|Yes| I[Load encrypted secret from DB]
    I --> J[Decrypt with AES-256-GCM]
    J --> K{TOTP code valid? RFC 6238 plus or minus 1 window}
    K -->|No| L[Show Invalid code error]
    L --> D
    K -->|Yes| M[Clear PENDING_MFA session attributes]
    M --> N[Reload UserDetails from DB]
    N --> O[Set UsernamePasswordAuthenticationToken in SecurityContext]
    O --> P[Reset brute force counter]
    P --> Q{Saved OAuth2 request?}
    Q -->|Yes| R[Continue OAuth2 authorization code flow]
    Q -->|No| S([Redirect to tenantId/dashboard])
```

---

## 7. MFA Disable Flow

```mermaid
flowchart TD
    A([User calls POST /mfa/disable]) --> B[Provide current 6-digit code]
    B --> C{mfa_enabled=true?}
    C -->|No| D[Error: MFA not active]
    C -->|Yes| E[Decrypt current secret]
    E --> F{Code valid?}
    F -->|No| G[Error: invalid code MFA not disabled]
    F -->|Yes| H[Set mfa_enabled=false]
    H --> I[Clear mfa_secret set to null]
    I --> J([MFA fully disabled])
    J --> K[Next login: isMfaRequired=false]
    K --> L([Login completes without MFA challenge])
```

---

## 8. OAuth2 Authorization Code + PKCE Flow

```mermaid
sequenceDiagram
    participant App as Client App Angular
    participant Browser as Browser
    participant Core as auth-server-core
    participant DB as Tenant DB

    App->>Browser: Redirect to oauth2/authorize with client_id and code_challenge
    Browser->>Core: GET tenantId/oauth2/authorize
    Core->>Browser: Redirect to tenantId/login unauthenticated
    Browser->>Core: POST tenantId/login username and password
    Note over Core: Brute force check MFA gate if enabled
    Core->>Browser: Redirect back to oauth2/authorize after MFA
    Core->>Browser: Redirect to redirect_uri with AUTH_CODE
    App->>Core: POST tenantId/oauth2/token with code and code_verifier
    Core->>Core: Verify PKCE SHA256 code_verifier equals code_challenge
    Core->>DB: Load user details roles scopes
    Core-->>App: access_token id_token refresh_token
    App->>Core: GET tenantId/userinfo with Bearer token
    Core-->>App: sub email name roles
```

---

## 9. Password Reset Flow

```mermaid
flowchart TD
    A([User visits /forgot-password]) --> B[Enter email]
    B --> C[POST /forgot-password]
    C --> D{User exists?}
    D -->|No| E[Silent success no email sent]
    D -->|Yes| F[Generate time-limited reset token]
    F --> G[Save token hash to DB with expiry]
    G --> H[Send reset email via Notification Service]
    H --> I[Show Check your email page]
    I --> J([User clicks reset link])
    J --> K[GET /reset-password?token=xxx]
    K --> L{Token valid and not expired?}
    L -->|No| M[410 Gone Error page]
    L -->|Yes| N[Show new password form]
    N --> O[POST /reset-password token and newPassword]
    O --> P{Password meets policy?}
    P -->|No| Q[Show validation errors]
    Q --> N
    P -->|Yes| R[Bcrypt hash new password]
    R --> S[Update password in DB]
    S --> T[Invalidate reset token]
    T --> U([Redirect to login])
```

---

## 10. Tenant MFA Policy Management

```mermaid
flowchart TD
    A([Tenant Admin]) --> B{Action?}

    B -->|Enable org-wide MFA| C[PUT tenantId/api/v1/settings/mfa-policy]
    C --> D[Body: mfaRequiredForAll=true]
    D --> E[UPDATE tenant_settings SET mfa_required_for_all=true]
    E --> F[All users require MFA on next login]
    F --> G{User enrollment state?}
    G -->|mfa_enabled=true AND secret exists| H([Goes to mfa-verify on login])
    G -->|No secret at all| I([Goes to mfa-setup on login])
    G -->|Secret exists but not confirmed| J([Goes to mfa-setup on login])

    B -->|Enable MFA for specific user| K[PATCH user mfa_enabled=true]
    K --> L[Only that user gets MFA challenge]

    B -->|Disable org-wide MFA| M[PUT mfa-policy mfaRequiredForAll=false]
    M --> N[Per-user mfa_enabled flags still respected]
    N --> O[Users with mfa_enabled=false skip MFA]

    B -->|Check current policy| P[GET tenantId/api/v1/settings/mfa-policy]
    P --> Q[Returns mfaRequiredForAll true or false]
```

---

## 11. MFA State Machine

```mermaid
stateDiagram-v2
    [*] --> NoMFA : User created

    state "mfa_enabled=false, mfa_secret=null" as NoMFA
    state "mfa_enabled=false, mfa_secret=SET" as InProgress
    state "mfa_enabled=true, mfa_secret=SET" as Enrolled

    NoMFA --> InProgress : User visits mfa-setup, setupMfa() generates secret, resets mfa_enabled=false
    InProgress --> InProgress : User closes browser, next login forced back to mfa-setup
    InProgress --> Enrolled : User scans QR, enters valid code, confirmMfa() sets mfa_enabled=true
    Enrolled --> NoMFA : disableMfa() with valid code clears secret
    Enrolled --> InProgress : Admin re-triggers setup, new secret generated

    note right of NoMFA
        Login: no MFA challenge
    end note

    note right of InProgress
        Login: redirect to mfa-setup
    end note

    note right of Enrolled
        Login: redirect to mfa-verify
    end note
```
