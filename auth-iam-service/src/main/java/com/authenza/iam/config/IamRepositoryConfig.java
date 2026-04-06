package com.authenza.iam.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * Marker config that tells Spring Data JDBC where to find
 * the IAM module's repositories. The actual DataSource and
 * JdbcOperations beans are provided by the shared
 * auth-tenant-adapter module.
 */
@Configuration
@EnableJdbcRepositories(
        basePackages = "com.authenza.iam.repository",
        jdbcOperationsRef = "tenantJdbcOperations"
)
public class IamRepositoryConfig {
}
