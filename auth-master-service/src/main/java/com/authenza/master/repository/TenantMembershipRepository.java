package com.authenza.master.repository;

import com.authenza.master.model.TenantMembership;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantMembershipRepository extends ListCrudRepository<TenantMembership, Long> {

    /** All admins that belong to a given tenant — powers the "Tenant Members" UI. */
    List<TenantMembership> findByTenantId(String tenantId);

    /** All tenants an admin belongs to — powers the "Tenant Switcher" UI. */
    List<TenantMembership> findByAccountId(Long accountId);

    /** Check if an admin already has a membership in a specific tenant (prevents duplicates). */
    Optional<TenantMembership> findByAccountIdAndTenantId(Long accountId, String tenantId);

    boolean existsByAccountIdAndTenantId(Long accountId, String tenantId);
}
