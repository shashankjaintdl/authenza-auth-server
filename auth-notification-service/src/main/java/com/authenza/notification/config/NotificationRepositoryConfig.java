package com.authenza.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

@Configuration
@EnableJdbcRepositories(
        basePackages = "com.authenza.notification.repository",
        jdbcOperationsRef = "tenantJdbcOperations"
)
public class NotificationRepositoryConfig {
}
