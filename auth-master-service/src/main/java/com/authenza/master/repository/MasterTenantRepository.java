package com.authenza.master.repository;

import com.authenza.master.model.Tenant;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MasterTenantRepository extends ListCrudRepository<Tenant, Long> {

    // Used by auth-server-core to find where a tenant's DB is located
    Optional<Tenant> findByTenantId(String tenantId);

    // Check if a tenant exists before initialization
    boolean existsByTenantId(String tenantId);
}
