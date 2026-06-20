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

---

## ✅ Prerequisites

Before running Authenza locally, ensure the following tools and services are installed and running.

### 1. Java 17

All backend services require **Java 17** (enforced via Gradle toolchain).

```bash
# Verify installation
java -version   # should print: openjdk 17.x.x

# Install via Homebrew (macOS)
brew install openjdk@17
```

> ⚠️ Java 21+ is **not** supported without modifying the Gradle toolchain configuration.

---

### 2. Gradle (via Wrapper — no install needed)

The project uses the **Gradle wrapper** (`./gradlew`). No global Gradle installation is required.

```bash
# Verify wrapper is executable
./gradlew --version
```

---

### 3. MySQL 8.x

Authenza requires a running **MySQL 8** instance. Each tenant gets its own dynamically provisioned database.

```bash
# Install via Homebrew (macOS)
brew install mysql@8.0
brew services start mysql@8.0

# Verify
mysql -u root -p -e "SELECT VERSION();"
```

**Required databases** (auto-created on first boot if `createDatabaseIfNotExist=true`):

| Database | Used by |
|----------|---------|
| `auth-master` | `auth-master-service` — global tenant registry |
| `system-admin` | `auth-server-core` / `auth-iam-service` — default system tenant |
| `<tenant-id>` | Dynamically created per tenant on provisioning |

> Default credentials used in `application.yaml`: `root / sjain@123`  
> Update `app.datasource.*` in each service's `application.yaml` to match your local MySQL setup.

---

### 4. Redis 7.x

Redis is used for:
- **Session invalidation** via Pub/Sub (`tenant:settings-invalidated`, `tenant:provisioned`)
- **Brute-force protection** counters
- **Tenant settings cache** invalidation across service instances

```bash
# Option A — Docker (recommended, already in docker-compose.yml)
docker-compose up -d redis

# Option B — Homebrew (macOS)
brew install redis
brew services start redis

# Verify Redis is running
redis-cli ping   # should return: PONG
```

Default connection: `localhost:6379` (no password in dev).

---

### 5. Node.js 18+ & npm (for Tenant Portal)

The Angular Tenant Portal (`authenza-tenant-portal`) requires Node.js.

```bash
# Verify
node -v    # should be v18.x or higher
npm -v

# Install via nvm (recommended)
nvm install 18
nvm use 18
```

---

### 6. Angular CLI (for Tenant Portal)

```bash
npm install -g @angular/cli

# Verify
ng version   # should show Angular CLI 17.x
```

---

## 🔌 Service Port Map

| Service | Port | Description |
|---------|------|-------------|
| `auth-master-service` | `8080` | Control plane — tenant provisioning |
| `auth-server-core` | `8081` | OAuth2 / OIDC Authorization Server |
| `auth-iam-service` | `8082` | IAM engine — users, MFA, sessions, passkeys |
| `auth-notification-service` | `8083` | Email / notification dispatcher |
| `authenza-tenant-portal` | `4200` | Angular Admin Portal (dev server) |
| MySQL | `3306` | Relational database |
| Redis | `6379` | Cache & Pub/Sub broker |

---

## ▶️ Boot Order

Services must be started in this order due to inter-service dependencies:

```
1. Infrastructure  →  docker-compose up -d redis
                      (ensure MySQL is running)

2. auth-master-service     →  ./gradlew :auth-master-service:bootRun
   (initializes master DB + registers system-admin tenant)

3. auth-iam-service        →  ./gradlew :auth-iam-service:bootRun
   (runs Liquibase migrations on tenant DBs)

4. auth-server-core        →  ./gradlew :auth-server-core:bootRun
   (OAuth2 / OIDC server — depends on IAM service)

5. auth-notification-service →  ./gradlew :auth-notification-service:bootRun
   (optional — required for email flows: registration, password reset)

6. authenza-tenant-portal  →  npm run start
   (Angular dev server at http://localhost:4200)
```

> 💡 **Tip:** You can boot all backend services in parallel after `auth-master-service` is fully started (look for `"Started AuthenzaMasterServiceApplication"` in logs).

---

## ⚙️ Environment Configuration

Each service has its own `application.yaml` under `src/main/resources/`. Key properties to update for your local environment:

| Property | File | Default |
|----------|------|---------|
| MySQL URL | all `application.yaml` | `jdbc:mysql://localhost:3306/...` |
| MySQL username | all `application.yaml` | `root` |
| MySQL password | all `application.yaml` | `sjain@123` |
| Redis host | all `application.yaml` | `localhost` |
| Redis port | all `application.yaml` | `6379` |
| IAM service URL | `auth-server-core/application.yaml` | `http://localhost:8082` |
| Master service URL | `auth-iam-service/application.yaml` | `http://localhost:8080` |

---

*For full architecture details see [ROADMAP.md](ROADMAP.md) · For sprint progress see [SPRINTS.md](SPRINTS.md)*

