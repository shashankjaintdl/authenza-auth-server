package com.authenza.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

@Configuration
@EnableJdbcRepositories(
        basePackages = "com.authenza.core.repository.none",
        jdbcOperationsRef = "tenantJdbcOperations"
)
public class TenantPersistenceConfig {

        @Configuration
        @EnableJdbcRepositories(
                basePackages = "com.authenza.core.repository.none",
                jdbcOperationsRef = "masterJdbcOperations"
        )
        public static class MasterRepoConfig {
        }
}
