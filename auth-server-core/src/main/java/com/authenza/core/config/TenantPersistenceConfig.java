package com.authenza.core.config;

import com.authenza.adapter.routing.TenantRoutingDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;


import org.springframework.beans.factory.annotation.Qualifier;


@Configuration
@EnableJdbcRepositories(
        // FIX: Ensure this matches the package of JdbcTenantClientRepository
        basePackages = "com.authenza.core.repository",
        jdbcOperationsRef = "tenantJdbcOperations"
)
public class TenantPersistenceConfig {

//    @Bean
//    public TenantRoutingDataSource tenantRoutingDataSource(
//            @Qualifier("masterDataSource") DataSource masterDataSource) {
//        TenantRoutingDataSource tenantRoutingDataSource = new TenantRoutingDataSource(masterDataSource);
//
//        return tenantRoutingDataSource;
//    }
//
//    @Bean
//    @Primary
//    public DataSource dataSource(TenantRoutingDataSource routingDataSource) {
//        return routingDataSource;
//    }
//
//
//    @Bean(name = "tenantJdbcOperations")
//    @Primary
//    public NamedParameterJdbcOperations tenantJdbcOperations(DataSource dataSource) {
//        return new NamedParameterJdbcTemplate(dataSource);
//    }
}

