package com.authenza.master.config;

import com.authenza.common.enums.DBType;
import com.authenza.common.dto.TenantRequest;
import com.authenza.master.repository.MasterTenantRepository;
import com.authenza.master.service.TenantProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataBaseInitializer implements CommandLineRunner {

    public static final Logger log = LoggerFactory.getLogger(DataBaseInitializer.class);

    @Value("${app.datasource.url}")
    private String dbUrl;

    @Value("${app.datasource.username}")
    private String username;

    @Value("${app.datasource.password}")
    private String password;

    @Value("${app.datasource.driverClassName}")
    private String driverClassName;

    @Value("${app.datasource.dbType}")
    private String dbType;

    private final MasterTenantRepository repository;
    private final TenantProvisioningService provisioningService;

    public DataBaseInitializer(MasterTenantRepository repository, TenantProvisioningService provisioningService) {
        this.repository = repository;
        this.provisioningService = provisioningService;
    }

    @Override
    public void run(String... args) {

        String systemTenantId = "system-admin";
        if (repository.findByTenantId(systemTenantId).isPresent()) {
            log.info("System Initialization Skipped: '{}' already exists in Registry.", systemTenantId);
            return;
        }

        log.info("First-time setup: Creating System Tenant and Super Admin...");

        try {
            // 2. Define the System Tenant (Pass null for ID so DB generates it)
            TenantRequest systemTenant = new TenantRequest(
                    systemTenantId,
                    DBType.parse(dbType),
                    dbUrl,
                    username,
                    password,
                    driverClassName
            );

            this.provisioningService.onboardNewTenant(systemTenant);
        }
        catch (Exception e) {
            log.error("CRITICAL: Failed to initialize system-admin tenant", e);
            // Optional: System.exit(1); // Stop app if system tenant fails
        }
    }
}
