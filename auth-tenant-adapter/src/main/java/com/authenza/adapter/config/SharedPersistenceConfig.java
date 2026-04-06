
package com.authenza.adapter.config; // Inside auth-tenant-adapter

import com.authenza.adapter.routing.TenantRoutingDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class SharedPersistenceConfig {

    // 1. MASTER DATABASE
    @Bean(name = "masterDataSource")
    @ConfigurationProperties(prefix = "app.datasource.master")
    public DataSource masterDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public NamedParameterJdbcOperations masterJdbcOperations(@Qualifier("masterDataSource") DataSource masterDataSource) {
        return new NamedParameterJdbcTemplate(masterDataSource);
    }

    // 2. TENANT DATABASE (Routing)
    @Bean
    public TenantRoutingDataSource tenantRoutingDataSource(@Qualifier("masterDataSource") DataSource masterDataSource) {
        return new TenantRoutingDataSource(masterDataSource);
    }

    @Bean
    @Primary
    public DataSource dataSource(TenantRoutingDataSource routingDataSource) {
        return routingDataSource;
    }

    @Bean(name = "tenantJdbcOperations")
    @Primary
    public NamedParameterJdbcOperations tenantJdbcOperations(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
