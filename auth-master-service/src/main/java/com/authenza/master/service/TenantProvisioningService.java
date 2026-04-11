package com.authenza.master.service;

import com.authenza.common.events.TenantProvisionedEvent;
import com.authenza.common.dto.TenantRequest;
import com.authenza.common.enums.TenantStaus;
import com.authenza.master.model.Tenant;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;

@Service
public class TenantProvisioningService {

    public static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final MasterTenantRepository repository;
    private final TenantEventPublisher tenantEventPublisher;

    @Autowired
    private ResourceLoader resourceLoader;

    public TenantProvisioningService(MasterTenantRepository repository,
                                     TenantEventPublisher tenantEventPublisher) {
        this.repository = repository;
        this.tenantEventPublisher = tenantEventPublisher;
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
                request.password(), // In production, encrypt this first!
                request.driver(),
                TenantStaus.ACTIVE.name()
        );

        // 2. Save metadata to Master Registry
        repository.save(tenant);

        // 3. Run Liquibase Schema Migration against the Tenant's Database
        runLiquibaseMigration(request, "system-admin".equals(request.tenantId()));

        // 4. Notify auth-server-core via Redis to register the new DataSource
        tenantEventPublisher.publishTenantProvisioned(new TenantProvisionedEvent(
                request.tenantId(),
                request.jdbcUrl(),
                request.username(),
                request.password(),
                request.driver()
        ));

        log.info("Successfully provisioned database for tenant: {}", request.tenantId());
    }

    private void runLiquibaseMigration(TenantRequest request, boolean isMaster) {
        // Create a temporary connection to the customer's DB
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(request.driver());
        ds.setUrl(request.jdbcUrl());
        ds.setUsername(request.username());
        ds.setPassword(request.password());

        try (Connection connection = ds.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));

            String changeDb = "db/changelog/tenant/db.changelog-tenant.xml";;
//            if(isMaster){
//                changeDb = "db/changelog/master/db.changelog-master.xml";
//            }

            // This pulls the XML from the 'auth-tenant-schema' module
            Liquibase liquibase = new Liquibase(
                    changeDb,
                    new SpringResourceAccessor(resourceLoader),
                    database
            );

            liquibase.update(""); // Run all changesets
            log.info("Liquibase migration completed for {}", request.tenantId());

        } catch (Exception e) {
            log.error("Migration failed for tenant: {}", request.tenantId(), e);
            throw new RuntimeException("Database provisioning failed: " + e.getMessage());
        }
    }
}

