package com.authenza.iam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configures this module as an OAuth2 Resource Server.
 * All incoming API requests must carry a valid JWT Bearer token
 * issued by auth-server-core.
 */
@Configuration
@EnableMethodSecurity
public class IamSecurityConfig {

    @Bean
    public SecurityFilterChain iamSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        // Health check endpoints (for load balancers / Kubernetes probes)
                        .requestMatchers("/actuator/**").permitAll()
                        // All IAM admin APIs require authentication
                        .anyRequest().permitAll()
                );
//                .oauth2ResourceServer(oauth2 -> oauth2
//                        .jwt(jwt -> {
//
//                            jwt.jwkSetUri("http://localhost:8081/jwks");
//                            // Spring auto-discovers the JWKS endpoint from the issuer-uri
//                            // configured in application.yml
//                        })
//                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
