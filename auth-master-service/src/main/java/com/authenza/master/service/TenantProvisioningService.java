package com.authenza.master.service;

import com.authenza.common.events.TenantProvisionedEvent;
import com.authenza.common.dto.TenantRequest;
import com.authenza.common.enums.TenantStaus;
import com.authenza.master.model.GlobalAccount;
import com.authenza.master.model.Tenant;
import com.authenza.master.repository.GlobalAccountRepository;
import com.authenza.master.repository.MasterTenantRepository;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;

@Service
public class TenantProvisioningService {

    public static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final MasterTenantRepository repository;
    private final TenantEventPublisher tenantEventPublisher;
    private final GlobalAccountService globalAccountService;
    private final GlobalAccountRepository globalAccountRepository;

    @Autowired
    private ResourceLoader resourceLoader;

    @Value("${app.datasource.url}")
    private String masterDbUrl;

    @Value("${app.datasource.username}")
    private String masterDbUsername;

    @Value("${app.datasource.password}")
    private String masterDbPassword;

    @Value("${app.datasource.driverClassName}")
    private String masterDbDriver;

    public TenantProvisioningService(MasterTenantRepository repository,
            TenantEventPublisher tenantEventPublisher,
            GlobalAccountService globalAccountService,
            GlobalAccountRepository globalAccountRepository) {
        this.repository = repository;
        this.tenantEventPublisher = tenantEventPublisher;
        this.globalAccountService = globalAccountService;
        this.globalAccountRepository = globalAccountRepository;
    }

    @Transactional
    public void onboardNewTenant(TenantRequest originalRequest) {
        TenantRequest request = originalRequest;
        if (request.tenantId() == null || request.tenantId().isBlank()) {
            String generatedTenantId = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
            request = new TenantRequest(
                    generatedTenantId,
                    request.dbType(),
                    request.jdbcUrl(),
                    request.username(),
                    request.password(),
                    request.driver(),
                    request.ownerId(),
                    request.accountType(),
                    request.orgName(),
                    request.employeeRange(),
                    request.isDefault()
            );
            log.info("Tenant ID not provided. Auto-generated: {}", generatedTenantId);
        }

        final TenantRequest finalRequest = request;

        log.info("Onboarding new tenant: {}", finalRequest.tenantId());

        // Guard: COMPANY tenants must supply orgName + employeeRange
        if ("COMPANY".equalsIgnoreCase(finalRequest.accountType())) {
            if (finalRequest.orgName() == null || finalRequest.orgName().isBlank()) {
                throw new IllegalArgumentException("orgName is required for COMPANY account type.");
            }
            if (finalRequest.employeeRange() == null || finalRequest.employeeRange().isBlank()) {
                throw new IllegalArgumentException("employeeRange is required for COMPANY account type.");
            }
        }

        String resolvedJdbcUrl = finalRequest.jdbcUrl();
        String resolvedUsername = finalRequest.username();
        String resolvedPassword = finalRequest.password();
        String resolvedDriver = finalRequest.driver();
        String resolvedDbType = finalRequest.dbType() != null ? finalRequest.dbType().getValue() : com.authenza.common.enums.DBType.MYSQL.getValue();

        if (resolvedJdbcUrl == null || resolvedJdbcUrl.isBlank()) {
            log.info("No DB credentials provided for tenant '{}'. Falling back to default platform database provisioning.", finalRequest.tenantId());
            resolvedUsername = masterDbUsername;
            resolvedPassword = masterDbPassword;
            resolvedDriver = masterDbDriver;
            resolvedDbType = com.authenza.common.enums.DBType.MYSQL.getValue();
            
            // Extract base url from master database. 
            // e.g. from jdbc:mysql://localhost:3306/auth-master?createDatabaseIfNotExist=true
            // to   jdbc:mysql://localhost:3306/my_new_tenant?createDatabaseIfNotExist=true
            int lastSlash = masterDbUrl.lastIndexOf("/");
            String base = masterDbUrl.substring(0, lastSlash);
            int qMark = masterDbUrl.indexOf("?", lastSlash);
            String options = qMark != -1 ? masterDbUrl.substring(qMark) : "";
            resolvedJdbcUrl = base + "/" + finalRequest.tenantId() + options;
        }

        // 1. Create the Tenant Domain Object
        Tenant tenant = Tenant.create(
                finalRequest.tenantId(),
                resolvedDbType,
                resolvedJdbcUrl,
                resolvedUsername,
                resolvedPassword,
                resolvedDriver,
                TenantStaus.ACTIVE.name(),
                finalRequest.ownerId(),
                finalRequest.accountType(),
                finalRequest.orgName(),
                finalRequest.employeeRange(),
                finalRequest.isDefault());

        // 1b. Check if this is the user's first tenant. If so, force it to be default.
        if (finalRequest.ownerId() != null && !finalRequest.ownerId().isBlank()) {
            boolean hasOtherTenants = repository.findByOwnerId(finalRequest.ownerId()).iterator().hasNext();
            if (!hasOtherTenants) {
                log.info("First tenant for owner '{}'. Setting as default.", finalRequest.ownerId());
                tenant.setDefault(true);
            }
        }

        // 2. Save metadata to Master Registry
        repository.save(tenant);

        // 3. Build a temporary DataSource for the new tenant's DB
        DriverManagerDataSource tenantDs = buildTenantDataSource(finalRequest, resolvedJdbcUrl, resolvedUsername, resolvedPassword, resolvedDriver);

        // 4. Run Liquibase Schema Migration against the Tenant's Database
        runLiquibaseMigration(finalRequest, tenantDs);

        // 5. Strategy 3 — Global Identity auto-provisioning:
        //    If an ownerId (email) is provided and a global account exists,
        //    create the tenant membership and seed a passwordless shadow user.
        if (finalRequest.ownerId() != null && !finalRequest.ownerId().isBlank()) {
            globalAccountRepository.findByEmail(finalRequest.ownerId()).ifPresentOrElse(
                account -> {
                    // 5a. Register membership in master DB (idempotent)
                    globalAccountService.addMembership(account.getId(), finalRequest.tenantId(), "OWNER");

                    // 5b. Seed a passwordless shadow user in the new tenant DB
                    seedLocalAdminShadow(tenantDs, account);
                },
                () -> log.warn(
                    "ownerId '{}' has no global_accounts record. " +
                    "Shadow user not seeded for tenant '{}'. " +
                    "Register a global account first.", finalRequest.ownerId(), finalRequest.tenantId())
            );
        }

        // 6. Notify auth-server-core via Redis to register the new DataSource
        tenantEventPublisher.publishTenantProvisioned(new TenantProvisionedEvent(
                finalRequest.tenantId(),
                resolvedJdbcUrl,
                resolvedUsername,
                resolvedPassword,
                resolvedDriver));

        log.info("Successfully provisioned database for tenant: {}", finalRequest.tenantId());
    }

    public Iterable<Tenant> getTenantsByOwner(String ownerId) {
        return repository.findByOwnerId(ownerId);
    }

    // ─────────────────────────────────────────────
    // Shadow User Seeding (Option B — Passwordless)
    // ─────────────────────────────────────────────

    /**
     * Finds the existing system-admin tenant and seeds a newly registered global account
     * directly into the system-admin database as a passwordless shadow user.
     * This allows new global admins to instantly log into the Tenant Portal to provision
     * their first tenant workspace.
     *
     * @param account the newly registered global account
     */
    public void seedShadowUserInSystemAdmin(GlobalAccount account) {
        Tenant systemAdmin = repository.findByTenantId("system-admin").orElse(null);
        if (systemAdmin == null) {
            log.warn("Cannot seed shadow user into system-admin because the tenant does not exist yet.");
            return;
        }

        // Add explicit membership to system-admin so they have portal rights
        globalAccountService.addMembership(account.getId(), systemAdmin.getTenantId(), "OWNER");

        TenantRequest request = new TenantRequest(
                systemAdmin.getTenantId(),
                null,
                systemAdmin.getJdbcUrl(),
                systemAdmin.getUsername(),
                systemAdmin.getEncryptedPassword(),
                systemAdmin.getDriverClassName(),
                account.getEmail(),
                "PERSONAL", // system-admin is a platform-internal tenant
                null,
                null,
                true);

        log.info("Automatically seeding shadow admin '{}' into system-admin database...", account.getEmail());
        DataSource systemDs = buildTenantDataSource(request, request.jdbcUrl(), request.username(), request.password(), request.driver());
        seedLocalAdminShadow(systemDs, account);
    }

    /**
     * Seeds a passwordless "shadow" admin user into a newly provisioned tenant database.
     *
     * <p><strong>Why passwordless?</strong> The admin's BCrypt hash lives exclusively in
     * {@code global_accounts} (the master DB). When {@code JdbcTenantUserDetailsService}
     * detects {@code password = NULL} for a user, it falls back to {@code global_accounts}
     * for credential verification — giving the admin a single password that works across
     * all their tenants with zero sync complexity (Option B login strategy).
     *
     * @param tenantDs  the new tenant's DataSource (temporary, for provisioning only)
     * @param account   the global admin account being seeded into this tenant
     */
    private void seedLocalAdminShadow(DataSource tenantDs, GlobalAccount account) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs);
        String email = account.getEmail();

        try {
            // Guard: skip if shadow user already exists (idempotent)
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM application_user WHERE email = ?", Integer.class, email);
            if (count != null && count > 0) {
                log.info("Shadow user '{}' already exists in tenant DB. Skipping seed.", email);
                return;
            }

            String fullName = trim(account.getGivenName()) + " " + trim(account.getFamilyName());

            // Insert shadow user — password uses sentinel value [GLOBAL_ACCOUNT]
            jdbc.update("""
                INSERT INTO application_user
                    (preferred_username, email, given_name, family_name, name,
                     password, status, email_verified, mfa_enabled,
                     phone_number_verified, failed_login_attempts, created_at)
                VALUES (?, ?, ?, ?, ?, '[GLOBAL_ACCOUNT]', 'ACTIVE', true, false, false, 0, NOW())
                """,
                email, email,
                account.getGivenName(), account.getFamilyName(), fullName.trim()
            );

            // Get the newly created user's ID
            Long userId = jdbc.queryForObject(
                "SELECT id FROM application_user WHERE email = ?", Long.class, email);

            // Get the ROLE_TENANT_ADMIN role ID
            Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'ROLE_TENANT_ADMIN'", Long.class);

            // Assign the admin role
            jdbc.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);

            log.info("Seeded passwordless shadow admin '{}' with ROLE_TENANT_ADMIN in tenant DB.", email);

        } catch (Exception e) {
            log.error("Failed to seed shadow admin '{}'. Tenant provisioned but admin must be added manually.", email, e);
            // Non-fatal: the tenant is provisioned; admin can be added later
        }
    }

    private String trim(String value) {
        return value != null ? value : "";
    }

    // ─────────────────────────────────────────────
    // Internal Helpers
    // ─────────────────────────────────────────────

    private DriverManagerDataSource buildTenantDataSource(TenantRequest request, String resolvedJdbcUrl, String resolvedUsername, String resolvedPassword, String resolvedDriver) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(resolvedDriver);
        ds.setUrl(resolvedJdbcUrl);
        ds.setUsername(resolvedUsername);
        ds.setPassword(resolvedPassword);
        return ds;
    }

    private void runLiquibaseMigration(TenantRequest request, DataSource tenantDs) {
        try (Connection connection = tenantDs.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));

            String changeDb = "db/changelog/migration/tenant/db.changelog-tenant.xml";

            Liquibase liquibase = new Liquibase(
                    changeDb,
                    new SpringResourceAccessor(resourceLoader),
                    database);

            liquibase.update(""); // Run all changesets
            log.info("Liquibase migration completed for {}", request.tenantId());

        } catch (Exception e) {
            log.error("Migration failed for tenant: {}", request.tenantId(), e);
            throw new RuntimeException("Database provisioning failed: " + e.getMessage());
        }
    }

    @Transactional
    public void updateLastAccessed(String tenantId) {
        repository.findByTenantId(tenantId).ifPresent(tenant -> {
            tenant.setLastAccessedAt(Instant.now());
            repository.save(tenant);
        });
    }
}
