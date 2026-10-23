package com.authenza.adapter.routing;

import com.authenza.adapter.context.TenantContextHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private final Map<Object, Object> tenantDataSources = new ConcurrentHashMap<>();
    private final Map<String, String> tenantStatuses = new ConcurrentHashMap<>();

    public TenantRoutingDataSource(@Qualifier("masterDataSource") DataSource masterDataSource) {
        // 1. Mandatory: Set an initial map to satisfy Spring's validation
        this.setTargetDataSources(tenantDataSources);

        // 2. Mandatory: Set a default (the master DB) so the app can boot
        this.setDefaultTargetDataSource(masterDataSource);

        // 3. Optional but good practice: explicitly initialize
        super.afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        // Pulls the active tenant ID set by the Interceptor
        return TenantContextHolder.getTenantId();
    }

    /**
     * Dynamically adds a new tenant connection pool at runtime with active status.
     */
    public void addTenantDataSource(String tenantId, DataSource dataSource, String active) {
        this.tenantDataSources.put(tenantId, dataSource);
        this.tenantStatuses.put(tenantId, active != null ? active : "ACTIVE");
        this.setTargetDataSources(new HashMap<>(this.tenantDataSources));
        this.afterPropertiesSet(); // Refresh the internal lookup map
    }

    /**
     * Dynamically adds a new tenant connection pool at runtime defaulting to ACTIVE.
     */
    public void addTenantDataSource(String tenantId, DataSource dataSource) {
        addTenantDataSource(tenantId, dataSource, "ACTIVE");
    }

    /**
     * Returns true if a DataSource has been registered for the given tenantId.
     */
    public boolean isKnownTenant(String tenantId) {
        return this.tenantDataSources.containsKey(tenantId);
    }

    /**
     * Returns true if the tenant's status is ACTIVE.
     */
    public boolean isTenantActive(String tenantId) {
        String status = tenantStatuses.get(tenantId);
        return status != null && ("ACTIVE".equalsIgnoreCase(status) || "true".equalsIgnoreCase(status));
    }

    /**
     * Updates the status of an existing tenant.
     */
    public void setTenantStatus(String tenantId, String status) {
        if (tenantId != null && status != null) {
            this.tenantStatuses.put(tenantId, status);
        }
    }
}
