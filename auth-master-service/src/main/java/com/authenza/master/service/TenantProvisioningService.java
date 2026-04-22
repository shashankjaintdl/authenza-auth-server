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
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;

@Service
public class TenantProvisioningService {

    public static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final MasterTenantRepository repository;
    private final TenantEventPublisher tenantEventPublisher;
    private final GlobalAccountService globalAccountService;
    private final GlobalAccountRepository globalAccountRepository;

    @Autowired
    private ResourceLoader resourceLoader;

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
    public void onboardNewTenant(TenantRequest request) {
        log.info("Onboarding new tenant: {}", request.tenantId());

        // 1. Create the Tenant Domain Object
        Tenant tenant = Tenant.create(
                request.tenantId(),
                request.dbType().getValue(),
                request.jdbcUrl(),
                request.username(),
                request.password(),
                request.driver(),
                TenantStaus.ACTIVE.name(),
                request.ownerId());

        // 2. Save metadata to Master Registry
        repository.save(tenant);

        // 3. Build a temporary DataSource for the new tenant's DB
        DriverManagerDataSource tenantDs = buildTenantDataSource(request);

        // 4. Run Liquibase Schema Migration against the Tenant's Database
        runLiquibaseMigration(request, tenantDs);

        // 5. Strategy 3 — Global Identity auto-provisioning:
        //    If an ownerId (email) is provided and a global account exists,
        //    create the tenant membership and seed a passwordless shadow user.
        if (request.ownerId() != null && !request.ownerId().isBlank()) {
            globalAccountRepository.findByEmail(request.ownerId()).ifPresentOrElse(
                account -> {
                    // 5a. Register membership in master DB (idempotent)
                    globalAccountService.addMembership(account.getId(), request.tenantId(), "OWNER");

                    // 5b. Seed a passwordless shadow user in the new tenant DB
                    seedLocalAdminShadow(tenantDs, account);
                },
                () -> log.warn(
                    "ownerId '{}' has no global_accounts record. " +
                    "Shadow user not seeded for tenant '{}'. " +
                    "Register a global account first.", request.ownerId(), request.tenantId())
            );
        }

        // 6. Notify auth-server-core via Redis to register the new DataSource
        tenantEventPublisher.publishTenantProvisioned(new TenantProvisionedEvent(
                request.tenantId(),
                request.jdbcUrl(),
                request.username(),
                request.password(),
                request.driver()));

        log.info("Successfully provisioned database for tenant: {}", request.tenantId());
    }

    public Iterable<Tenant> getTenantsByOwner(String ownerId) {
        return repository.findByOwnerId(ownerId);
    }

    // ─────────────────────────────────────────────
    // Shadow User Seeding (Option B — Passwordless)
    // ─────────────────────────────────────────────

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

    private DriverManagerDataSource buildTenantDataSource(TenantRequest request) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(request.driver());
        ds.setUrl(request.jdbcUrl());
        ds.setUsername(request.username());
        ds.setPassword(request.password());
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
}
