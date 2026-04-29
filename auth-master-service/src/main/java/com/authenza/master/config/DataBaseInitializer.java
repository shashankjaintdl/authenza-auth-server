package com.authenza.master.config;

import com.authenza.common.enums.DBType;
import com.authenza.common.dto.TenantRequest;
import com.authenza.master.dto.GlobalAccountRequest;
import com.authenza.master.repository.GlobalAccountRepository;
import com.authenza.master.repository.MasterTenantRepository;
import com.authenza.master.service.GlobalAccountService;
import com.authenza.master.service.TenantProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * First-time system initializer.
 *
 * <p>On startup, if the {@code system-admin} tenant does not exist,
 * this runner:
 * <ol>
 *   <li>Creates a {@code global_accounts} record for the platform super-admin
 *       (Strategy 3: Global Identity Registry).</li>
 *   <li>Provisions the {@code system-admin} tenant database via Liquibase.</li>
 *   <li>Automatically seeds the super-admin as an OWNER member of the system-admin tenant
 *       and creates a passwordless shadow user (Option B login strategy).</li>
 * </ol>
 *
 * <p>All operations are idempotent — re-running the app after initialization is safe.
 */
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

    @Value("${app.system-tenant.url}")
    private String systemDbUrl;

    // Super-admin credentials (used only for first-time bootstrap)
    @Value("${app.system-admin.email:admin@authenza.io}")
    private String superAdminEmail;

    @Value("${app.system-admin.password:Admin@12345}")
    private String superAdminPassword;

    @Value("${app.system-admin.given-name:Platform}")
    private String superAdminGivenName;

    @Value("${app.system-admin.family-name:Admin}")
    private String superAdminFamilyName;

    private final MasterTenantRepository repository;
    private final TenantProvisioningService provisioningService;
    private final GlobalAccountService globalAccountService;
    private final GlobalAccountRepository globalAccountRepository;

    public DataBaseInitializer(MasterTenantRepository repository,
                               TenantProvisioningService provisioningService,
                               GlobalAccountService globalAccountService,
                               GlobalAccountRepository globalAccountRepository) {
        this.repository = repository;
        this.provisioningService = provisioningService;
        this.globalAccountService = globalAccountService;
        this.globalAccountRepository = globalAccountRepository;
    }

    @Override
    public void run(String... args) {
        String systemTenantId = "system-admin";

        if (repository.findByTenantId(systemTenantId).isPresent()) {
            log.info("System Initialization Skipped: '{}' already exists in Registry.", systemTenantId);
            return;
        }

        log.info("First-time setup: Registering platform super-admin and provisioning system-admin tenant...");

        try {
            // Step 1: Register the global super-admin account (idempotent via existsByEmail check)
            if (!globalAccountRepository.existsByEmail(superAdminEmail)) {
                globalAccountService.registerAccount(new GlobalAccountRequest(
                        superAdminEmail,
                        superAdminPassword,
                        superAdminGivenName,
                        superAdminFamilyName));
                log.info("Super-admin global account created for '{}'", superAdminEmail);
            }

            // Step 2: Provision system-admin tenant.
            // TenantProvisioningService will automatically:
            //   - Run Liquibase migration
            //   - Create the OWNER membership in tenant_memberships
            //   - Seed a passwordless shadow user into system-admin DB
            TenantRequest systemTenant = new TenantRequest(
                    systemTenantId,
                    DBType.parse(dbType),
                    systemDbUrl,
                    username,
                    password,
                    driverClassName,
                    superAdminEmail,  // ownerId = global admin email
                    "PERSONAL",       // system-admin is a platform-internal tenant
                    null,
                    null,
                    true); // isDefault

            this.provisioningService.onboardNewTenant(systemTenant);
            log.info("System tenant '{}' successfully provisioned with super-admin '{}'.",
                    systemTenantId, superAdminEmail);

        } catch (Exception e) {
            log.error("CRITICAL: Failed to initialize system-admin tenant", e);
        }
    }
}
