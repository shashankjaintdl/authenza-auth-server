//package com.authenza.adapter.config;
//
//import com.authenza.adapter.routing.TenantRoutingDataSource;
//import org.springframework.beans.factory.annotation.Qualifier;
//import org.springframework.boot.context.properties.ConfigurationProperties;
//import org.springframework.boot.jdbc.DataSourceBuilder;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Primary;
//import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
//import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
//
//import javax.sql.DataSource;
//
//@Configuration
//public class DataSourceConfig {
//
//    @Bean(name = "masterDataSource")
//    @ConfigurationProperties(prefix = "app.datasource.master")
//    public DataSource masterDataSource() {
//        return DataSourceBuilder.create().build();
//    }
//
//    @Bean
//    public NamedParameterJdbcOperations masterJdbcOperations(
//            @Qualifier("masterDataSource") DataSource masterDataSource) {
//        return new NamedParameterJdbcTemplate(masterDataSource);
//    }
//
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
//}
