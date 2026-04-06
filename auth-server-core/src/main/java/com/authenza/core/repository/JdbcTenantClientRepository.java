package com.authenza.core.repository;

import com.authenza.common.model.core.TenantRegisteredClient;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JdbcTenantClientRepository extends ListCrudRepository<TenantRegisteredClient, String> {

    TenantRegisteredClient findByClientId(String clientId);
}
