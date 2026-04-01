package com.authenza.master.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import javax.sql.DataSource;

@Configuration
public class MasterDatabaseConfig {

    public static final Logger log = LoggerFactory.getLogger(MasterDatabaseConfig.class);

    @Value("${app.datasource.url}")
    private String dbUrl;

    @Value("${app.datasource.username}")
    private String username;

    @Value("${app.datasource.password}")
    private String password;

    @Value("${app.datasource.driverClassName}")
    private String driverClassName;

    @Value("${app.datasource.dbType}")
    private String dbType;

    @Bean
    public DataSource masterDataSource() {
        // 1. Retrieve credentials from Environment Variables
//        String dbUrl = System.getenv("MASTER_DB_URL");
//        String dbUser = System.getenv("MASTER_DB_USER");
//        String dbPass = System.getenv("MASTER_DB_PASS");
//        String dbDriver = System.getenv("MASTER_DB_DRIVER"); // e.g., com.mysql.cj.jdbc.Driver

        // 2. Strict Validation: Fail Fast if not set
        if (dbUrl == null || username == null || password == null) {
            log.error("CRITICAL ERROR: Master Database environment variables are missing!");
            log.error("Please set: MASTER_DB_URL, MASTER_DB_USER, MASTER_DB_PASS");
            throw new IllegalStateException("Database credentials must be set via environment variables!");
        }

        log.info("Configuring Master Database for URL: {}", dbUrl);

        // 3. Build the DataSource for the Master Registry
        return DataSourceBuilder.create()
                .url(dbUrl)
                .username(username)
                .password(password)
                .driverClassName(driverClassName != null ? driverClassName : "com.mysql.cj.jdbc.Driver")
                .build();
    }
}