# Production Database Separation — system-admin Isolation

## Goal
Cleanly separate `auth-master` DB (registry only) from `system-admin` tenant data.

**Before:**
```
auth-master DB
├── tenant  (registry)
├── application_user  (system-admin users)
├── oauth2_registered_client  (system-admin OAuth2 clients)
└── ... all IAM tables (mixed in)
```

**After:**
```
auth-master DB          ← registry only
└── tenant

system-admin DB         ← isolated tenant
├── application_user
├── oauth2_registered_client
├── roles / permissions
├── tenant_settings
└── ... all IAM tables
```

> [!IMPORTANT]
> This is a **Phase 5 task** — do NOT implement until all Phase 2-4 features are stable. This requires a data migration and brief downtime.

---

## Execution Checklist

- [ ] Step 1: Create `system-admin` MySQL database
- [ ] Step 2: Update `application.yaml`
- [ ] Step 3: Update `DataBaseInitializer.java`
- [ ] Step 4: Run Liquibase migration on new DB
- [ ] Step 5: Migrate existing data (SQL)
- [ ] Step 6: Update tenant registry row
- [ ] Step 7: Verify & test
- [ ] Step 8: Drop migrated tables from auth-master (cleanup)

---

## Step 1 — Create the new database

```sql
-- Run against MySQL as root
CREATE DATABASE `system-admin`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- Grant same credentials used by auth-master-service
GRANT ALL PRIVILEGES ON `system-admin`.* TO 'root'@'localhost';
FLUSH PRIVILEGES;
```

---

## Step 2 — Update `application.yaml`

#### `auth-master-service/src/main/resources/application.yaml`

```yaml
app:
  datasource:
    url: jdbc:mysql://localhost:3306/auth-master?createDatabaseIfNotExist=true
    dbType: MYSQL
    username: root
    password: sjain@123
    driverClassName: com.mysql.cj.jdbc.Driver

  # NEW: dedicated datasource for the system-admin tenant
  system-tenant:
    url: jdbc:mysql://localhost:3306/system-admin?createDatabaseIfNotExist=true
    dbType: MYSQL
    username: root
    password: sjain@123
    driverClassName: com.mysql.cj.jdbc.Driver

spring:
  data:
    redis:
      host: localhost
      port: 6379
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/master/db.changelog-master.xml
```

---

## Step 3 — Update `DataBaseInitializer.java`

#### `auth-master-service/.../config/DataBaseInitializer.java`

```java
@Component
public class DataBaseInitializer implements CommandLineRunner {

    public static final Logger log = LoggerFactory.getLogger(DataBaseInitializer.class);

    // Master DB (registry only) — unchanged
    @Value("${app.datasource.username}")
    private String username;

    @Value("${app.datasource.password}")
    private String password;

    @Value("${app.datasource.driverClassName}")
    private String driverClassName;

    @Value("${app.datasource.dbType}")
    private String dbType;

    // NEW: system-admin gets its own dedicated URL
    @Value("${app.system-tenant.url}")
    private String systemTenantUrl;

    private final MasterTenantRepository repository;
    private final TenantProvisioningService provisioningService;

    public DataBaseInitializer(MasterTenantRepository repository,
                                TenantProvisioningService provisioningService) {
        this.repository = repository;
        this.provisioningService = provisioningService;
    }

    @Override
    public void run(String... args) {
        String systemTenantId = "system-admin";

        if (repository.findByTenantId(systemTenantId).isPresent()) {
            log.info("System Initialization Skipped: '{}' already exists in Registry.",
                     systemTenantId);
            return;
        }

        log.info("First-time setup: Creating System Tenant...");

        try {
            TenantRequest systemTenant = new TenantRequest(
                    systemTenantId,
                    DBType.parse(dbType),
                    systemTenantUrl,   // ← uses system-admin DB, NOT auth-master
                    username,
                    password,
                    driverClassName
            );

            this.provisioningService.onboardNewTenant(systemTenant);
            log.info("system-admin tenant provisioned at: {}", systemTenantUrl);

        } catch (Exception e) {
            log.error("CRITICAL: Failed to initialize system-admin tenant", e);
        }
    }
}
```

---

## Step 4 — Run Liquibase on the new database

After updating the config, on first startup `TenantMigrationRunner` will:
1. Find `system-admin` in the `tenant` registry
2. Run `db.changelog-tenant.xml` against the **new** `system-admin` DB
3. Create all tables fresh in the isolated database

> [!NOTE]
> For a new environment (no existing data) — Steps 5 & 6 below are NOT needed. Just start fresh and Steps 1–4 are sufficient.

---

## Step 5 — Migrate existing data (existing installs only)

Run this SQL to copy all tenant data from `auth-master` → `system-admin`:

```sql
-- ── Connect to auth-master first ──────────────────────────────────
USE `auth-master`;

-- 1. Roles & permissions
INSERT INTO `system-admin`.roles SELECT * FROM `auth-master`.roles;
INSERT INTO `system-admin`.role_permissions SELECT * FROM `auth-master`.role_permissions;

-- 2. Users
INSERT INTO `system-admin`.application_user SELECT * FROM `auth-master`.application_user;
INSERT INTO `system-admin`.user_roles SELECT * FROM `auth-master`.user_roles;

-- 3. OAuth2 clients and sessions
INSERT INTO `system-admin`.oauth2_registered_client
    SELECT * FROM `auth-master`.oauth2_registered_client;
INSERT INTO `system-admin`.oauth2_authorization
    SELECT * FROM `auth-master`.oauth2_authorization;
INSERT INTO `system-admin`.oauth2_authorization_consent
    SELECT * FROM `auth-master`.oauth2_authorization_consent;

-- 4. Tenant settings & security
INSERT INTO `system-admin`.tenant_settings SELECT * FROM `auth-master`.tenant_settings;
INSERT INTO `system-admin`.failed_login_attempts
    SELECT * FROM `auth-master`.failed_login_attempts;

-- Add any other tenant tables (email_verification_tokens, password_reset_tokens, etc.)
```

> [!WARNING]
> Run migrations in a transaction where possible. Verify row counts after each INSERT before proceeding.

---

## Step 6 — Update the tenant registry row

After migration, update the `tenant` row so the routing datasource points to the new DB:

```sql
-- Run against auth-master DB
UPDATE `auth-master`.tenant
SET jdbc_url = 'jdbc:mysql://localhost:3306/system-admin'
WHERE tenant_id = 'system-admin';
```

> [!IMPORTANT]
> This is the critical cut-over step. All subsequent requests for `system-admin` will route to the new database after this update + application restart.

---

## Step 7 — Verify

```bash
# 1. Restart auth-master-service — TenantMigrationRunner will pick up new URL
./gradlew :auth-master-service:bootRun

# 2. Check logs — should see system-admin migrating against new DB
# ✅ Migrated tenant 'system-admin' successfully

# 3. Test login flow
curl -X POST http://localhost:8081/system-admin/login \
     -d "username=admin@example.com&password=yourpassword"

# 4. Verify OAuth2 client still works
curl http://localhost:8081/system-admin/.well-known/openid-configuration

# 5. Verify tenant registry still clean
mysql -u root -p auth-master -e "SELECT tenant_id, jdbc_url FROM tenant;"
# system-admin → jdbc:mysql://localhost:3306/system-admin  ✅
```

---

## Step 8 — Cleanup (after verification only)

Once everything is confirmed working, clean up the orphaned tenant tables from `auth-master`:

```sql
-- Only run AFTER verifying system-admin works on the new database
USE `auth-master`;

DROP TABLE IF EXISTS user_roles;
DROP TABLE IF EXISTS role_permissions;
DROP TABLE IF EXISTS application_user;
DROP TABLE IF EXISTS roles;
DROP TABLE IF EXISTS oauth2_authorization;
DROP TABLE IF EXISTS oauth2_authorization_consent;
DROP TABLE IF EXISTS oauth2_registered_client;
DROP TABLE IF EXISTS tenant_settings;
DROP TABLE IF EXISTS failed_login_attempts;
-- Keep: tenant (registry must stay in auth-master)
```

> [!CAUTION]
> Only execute Step 8 after running the application for at least 24–48 hours on the new setup in staging. Never drop tables immediately after migration.

---

## Rollback Plan

If anything breaks, reverting takes 2 steps:

```sql
-- Step 1: Revert the tenant registry URL
UPDATE `auth-master`.tenant
SET jdbc_url = 'jdbc:mysql://localhost:3306/auth-master'
WHERE tenant_id = 'system-admin';

-- Step 2: auth-master-service restart picks up the old URL again
-- All data is still in auth-master (nothing was deleted yet)
```

Revert `application.yaml` to remove `app.system-tenant.url` and revert `DataBaseInitializer.java`. Done.

---

## Impact Summary

| Component | Changed? | Change |
|---|---|---|
| `application.yaml` | ✅ | Add `app.system-tenant.url` |
| `DataBaseInitializer.java` | ✅ | Use `systemTenantUrl` instead of `dbUrl` |
| `TenantProvisioningService` | ❌ | No change needed |
| `TenantMigrationRunner` | ❌ | No change needed |
| `auth-iam-service` | ❌ | Reads URL from `tenant` registry — auto-updates |
| `auth-server-core` | ❌ | Reads URL from `tenant` registry — auto-updates |
| DB Schema | ❌ | No Liquibase changes — same tenant changelog |
