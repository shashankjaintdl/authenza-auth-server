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
     * Dynamically adds a new tenant connection pool at runtime.
     * Called when a tenant is identified for the first time or via the Master Service.
     */
    public void addTenantDataSource(String tenantId, DataSource dataSource) {
        this.tenantDataSources.put(tenantId, dataSource);
        this.setTargetDataSources(new HashMap<>(this.tenantDataSources));
        this.afterPropertiesSet(); // Refresh the internal lookup map
    }

    /**
     * Returns true if a DataSource has been registered for the given tenantId.
     */
    public boolean isKnownTenant(String tenantId) {
        return this.tenantDataSources.containsKey(tenantId);
    }
}
