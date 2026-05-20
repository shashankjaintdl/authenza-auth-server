# Authenza Auth Server 🚀

Authenza is an enterprise-grade, **Multi-Tenant Identity and Access Management (IAM) System** built on top of **Spring Authorization Server (OAuth2 / OIDC)**. It provides deep, database-level isolation per tenant while delivering a modern, secure, and extensible authentication experience.

## 🏗 System Architecture

Authenza utilizes a strict multi-tenant architecture where **every tenant receives its own physically isolated database schema** dynamically provisioned on the fly. 

### Core Micro-Modules:
- **`auth-master-service`**: The control plane. Responsible for managing `global_accounts` (platform owners), managing billing/subscriptions, and dynamically provisioning new isolated databases for new tenants.
- **`auth-server-core`**: The heavily customized Spring Authorization Server. Handles standard OAuth2 and OpenID Connect flows (`authorization_code`, `refresh_token`), customized JWT claims, and multi-tenant issuer resolution (`/{tenantId}/oauth2/token`).
- **`auth-iam-service`**: The core IAM engine. Handles tenant-specific Users, Groups, RBAC (Role-Based Access Control), Passkeys (WebAuthn), and active session lifecycle management.
- **`auth-tenant-adapter`**: The database routing layer. Uses a custom `TenantRoutingDataSource` and `MultiTenantSecurityFilter` to dynamically switch database connections per request based on the tenant context.
- **`auth-tenant-schema`**: Manages the localized Liquibase migrations to ensure all tenant databases stay structurally synchronized without commingling data.

## 🔑 Key Features & Technical Milestones

### 1. Advanced OIDC & OAuth2 Customization
- **Dynamic Multi-Tenant Issuers:** Token issuers are dynamically resolved per tenant (e.g., `http://localhost:8081/{tenantId}`).
- **Public Client Support:** Engineered custom `AuthenticationConverter` extensions to fully support `refresh_token` grants for public PKCE clients (e.g., Angular SPAs) without requiring client secrets.
- **Custom JWTs:** Tokens are automatically enriched with tenant-specific RBAC roles and permissions.

### 2. Passwordless Authentication (FIDO2 / WebAuthn)
- Implemented robust Passkey registration and authentication using `webauthn-server-core`.
- Users can register device biometrics (TouchID, FaceID, Windows Hello) and name their devices (e.g., "My MacBook").
- Integrated seamlessly with the standard OIDC flow.

### 3. Deep Database Isolation
- At login, the request URI resolves the `tenantId`. The `TenantContextHolder` seamlessly routes all Hibernate/JDBC queries directly to that specific tenant's database connection pool.
- Absolute prevention of Cross-Tenant Session Bleed.

### 4. Active Session Management
- Tokens aren't just stateless. Sessions are tracked in the database, allowing users to view their "Active Devices" and remotely revoke specific sessions.

## 🛠 Tech Stack
- **Backend**: Java 17, Spring Boot 3.x, Spring Security, Spring Authorization Server 1.x, Spring Data JDBC/JPA.
- **Database**: MySQL, Liquibase, HikariCP.
- **Security**: BCrypt, WebAuthn/FIDO2, JWT (Nimbus), PKCE.
- **Frontend (Tenant Portal)**: Angular 17, Tailwind CSS, OIDC-Client.

## 🚀 Getting Started

1. **Spin up Infrastructure:** Use the provided `docker-compose.yml` to start MySQL and Redis.
2. **Boot the Master Service:** Run `auth-master-service` to initialize the master database.
3. **Provision a Tenant:** Send a `POST /admin/tenant` request to the master service to spawn a new isolated tenant workspace.
4. **Boot the Auth Server:** Start `auth-server-core` and point your frontend to your tenant's dynamic OIDC discovery endpoint (`/{tenantId}/.well-known/openid-configuration`).
