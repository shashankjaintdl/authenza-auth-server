package com.authenza.core.repository.master;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JdbcMasterClientRepository extends ListCrudRepository<RegisteredClient, String> {

    RegisteredClient findByClientIdAndTenantIdIn(String clientId, List<String> tenantIds);

}
