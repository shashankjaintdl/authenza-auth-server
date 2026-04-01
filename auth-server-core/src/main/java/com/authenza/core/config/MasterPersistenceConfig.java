package com.authenza.core.config;


import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import javax.sql.DataSource;

@Configuration
@EnableJdbcRepositories(
        basePackages = "com.authenza.master.repository",
        jdbcOperationsRef = "masterJdbcOperations"
)
public class MasterPersistenceConfig {

    @Bean(name = "masterDataSource")
    @ConfigurationProperties(prefix = "app.datasource.master")
    public DataSource masterDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public NamedParameterJdbcOperations masterJdbcOperations(
            @Qualifier("masterDataSource") DataSource masterDataSource) {
        return new NamedParameterJdbcTemplate(masterDataSource);
    }
}
