package com.authenza.adapter.config;

import com.authenza.common.TenantConfig;
import com.authenza.adapter.routing.TenantRoutingDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

@Service
public class TenantLoaderService {

    private final @Qualifier("masterDataSource") DataSource masterDataSource;
    private final TenantRoutingDataSource routingDataSource;

    public TenantLoaderService(DataSource masterDataSource, TenantRoutingDataSource routingDataSource) {
        this.masterDataSource = masterDataSource;
        this.routingDataSource = routingDataSource;
    }

    /**
     * Executes automatically after the application context is ready.
     * Guaranteed to execute FIRST so that connection pools are ready
     * for any other initializers (like SuperAdminClientInitializer).
     */
    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void loadAllTenantsOnStartup() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);
        String sql = "SELECT tenant_id, jdbc_url, username, encrypted_password, driver_class_name FROM tenants";

        List<TenantConfig> tenants = jdbcTemplate.query(sql, (rs, rowNum) -> new TenantConfig(
                rs.getString("tenant_id"),
                rs.getString("jdbc_url"),
                rs.getString("username"),
                rs.getString("encrypted_password"),
                rs.getString("driver_class_name")
        ));

        tenants.forEach(this::registerTenantDataSource);
    }

    /**
     * Registers a tenant's DataSource in the routing DataSource.
     * Public so it can be called both at startup and dynamically
     * when a Redis tenant-provisioned event is received.
     */
    public void registerTenantDataSource(TenantConfig config) {
        if(config.tenantId().contains("master")){
            return;
        }
        // Create a dedicated Hikari CP pool for this specific tenant
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setDriverClassName(config.driverClassName());

        // Pool sizing optimized for normal operation.
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setPoolName("Pool-" + config.tenantId());

        HikariDataSource ds = new HikariDataSource(hikariConfig);

        // Register it in the Dynamic Routing DataSource
        routingDataSource.addTenantDataSource(config.tenantId(), ds);
    }
}

