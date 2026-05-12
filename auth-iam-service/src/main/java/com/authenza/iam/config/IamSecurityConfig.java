package com.authenza.iam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        // Health check endpoints (for load balancers / Kubernetes probes)
                        .requestMatchers("/actuator/**").permitAll()
                        // ── WebAuthn Passwordless Login ───────────────────────────────────
                        // These two endpoints are called from the login page BEFORE the user
                        // is authenticated (that is the entire point of passwordless auth).
                        // Requiring a JWT here would create a chicken-and-egg loop.
                        .requestMatchers("/webauthn/authenticate/start").permitAll()
                        .requestMatchers("/webauthn/authenticate/finish").permitAll()
                        // All other IAM admin APIs require a valid JWT Bearer token
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults()));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
