package com.authenza.master.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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
        // Strict Validation: Fail Fast if not set
        if (dbUrl == null || username == null || password == null) {
            log.error("CRITICAL ERROR: Master Database environment variables are missing!");
            log.error("Please set: MASTER_DB_URL, MASTER_DB_USER, MASTER_DB_PASS");
            throw new IllegalStateException("Database credentials must be set via environment variables!");
        }

        log.info("Configuring Master Database for URL: {}", dbUrl);

        return DataSourceBuilder.create()
                .url(dbUrl)
                .username(username)
                .password(password)
                .driverClassName(driverClassName != null ? driverClassName : "com.mysql.cj.jdbc.Driver")
                .build();
    }

    /**
     * Standard Spring Security DelegatingPasswordEncoder for
     * {@link com.authenza.master.service.GlobalAccountService}.
     * This ensures the password stored in global_accounts includes the {bcrypt}
     * prefix,
     * which auth-server-core requires when verifying the password during Option B
     * fallback.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Secures the Master Service APIs so the frontend can safely query it.
     * Enforces JWT validation against the auth-server-core.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll() // Health checks
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Allow cross-origin requests from the developer/SPA portals.
     */
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOriginPatterns(java.util.Collections.singletonList("*")); // Allow any origin pattern
                                                                                          // for dev
        configuration
                .setAllowedMethods(java.util.Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"));
        configuration.setAllowedHeaders(java.util.Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}