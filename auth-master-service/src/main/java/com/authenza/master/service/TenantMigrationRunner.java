package com.authenza.master.service;

import com.authenza.master.model.Tenant;
import com.authenza.master.repository.MasterTenantRepository;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.List;

/**
 * Runs pending Liquibase migrations against ALL existing tenant databases
 * on application startup.
 *
 * <p>When you add a new changeset (e.g., V0.0.3 IAM tables), simply restart
 * the auth-master-service. This class will automatically detect and apply
 * the new migration to every tenant, including system-master.</p>
 *
 * <p>Liquibase tracks which changesets have already been applied via the
 * DATABASECHANGELOG table, so already-migrated changesets are safely skipped.</p>
 */
@Service
public class TenantMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationRunner.class);

    private final MasterTenantRepository repository;
    private final ResourceLoader resourceLoader;

    public TenantMigrationRunner(MasterTenantRepository repository,
                                 ResourceLoader resourceLoader) {
        this.repository = repository;
        this.resourceLoader = resourceLoader;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateAllTenantsOnStartup() {
        log.info("════════════════════════════════════════════════════════════");
        log.info("Starting Liquibase migrations for ALL existing tenants...");
        log.info("════════════════════════════════════════════════════════════");

        List<Tenant> tenants = repository.findAll();

        int success = 0;
        int failed = 0;

        for (Tenant tenant : tenants) {
            try {
                // All tenants (including system-admin) use the tenant changelog.
                // The master changelog is ONLY for the auth_master registry.
                String changelogFile = "db/changelog/tenant/db.changelog-tenant.xml";

                runMigration(tenant, changelogFile);
                success++;
            } catch (Exception e) {
                failed++;
                log.error("Migration FAILED for tenant '{}': {}",
                        tenant.getTenantId(), e.getMessage(), e);
                // Don't throw — continue migrating other tenants
            }
        }

        log.info("════════════════════════════════════════════════════════════");
        log.info("Migration complete. Success: {} | Failed: {} | Total: {}",
                success, failed, tenants.size());
        log.info("════════════════════════════════════════════════════════════");
    }

    private void runMigration(Tenant tenant, String changelogFile) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(tenant.getDriverClassName());
        ds.setUrl(tenant.getJdbcUrl());
        ds.setUsername(tenant.getUsername());
        ds.setPassword(tenant.getEncryptedPassword());

        try (Connection connection = ds.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));

            Liquibase liquibase = new Liquibase(
                    changelogFile,
                    new SpringResourceAccessor(resourceLoader),
                    database
            );

            liquibase.update("");
            log.info("✅ Migrated tenant '{}' successfully", tenant.getTenantId());

        } catch (Exception e) {
            throw new RuntimeException("Migration failed for " + tenant.getTenantId(), e);
        }
    }
}
