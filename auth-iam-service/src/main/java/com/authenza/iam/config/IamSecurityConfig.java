package com.authenza.iam.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configures this module as an OAuth2 Resource Server.
 * All incoming API requests must carry a valid JWT Bearer token
 * issued by auth-server-core.
 *
 * <p>
 * A custom {@link RevokedSessionJwtValidator} is wired into the JWT decoder
 * so that revoked sessions are rejected immediately (HTTP 401), enabling
 * real-time forced-logout without waiting for natural token expiry.
 */
@Configuration
@EnableMethodSecurity
public class IamSecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    private final RevokedSessionJwtValidator revokedSessionJwtValidator;

    public IamSecurityConfig(RevokedSessionJwtValidator revokedSessionJwtValidator) {
        this.revokedSessionJwtValidator = revokedSessionJwtValidator;
    }

    @Bean
    public SecurityFilterChain iamSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                // This is a stateless REST API / OAuth2 resource server.
                // Never save failed requests to the HTTP session — that is a web-app pattern.
                // Without this, anonymous API calls generate "Saved request ... ?continue"
                // debug noise and can cause unexpected redirects.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache
                        .requestCache(new org.springframework.security.web.savedrequest.NullRequestCache()))
                .authorizeHttpRequests(authorize -> authorize
                        // Health check endpoints (for load balancers / Kubernetes probes)
                        .requestMatchers("/actuator/**").permitAll()
                        // ── WebAuthn Passwordless Login ───────────────────────────────────
                        // These two endpoints are called from the login page BEFORE the user
                        // is authenticated (that is the entire point of passwordless auth).
                        // Requiring a JWT here would create a chicken-and-egg loop.
                        .requestMatchers("/webauthn/authenticate/start").permitAll()
                        .requestMatchers("/webauthn/authenticate/finish").permitAll()
                        // Public user flows called from auth-server-core UI before login
                        .requestMatchers("/api/v1/users/register").permitAll()
                        .requestMatchers("/api/v1/users/check-email").permitAll()
                        .requestMatchers("/api/v1/users/check-username").permitAll()
                        .requestMatchers("/api/v1/users/verify-email").permitAll()
                        .requestMatchers("/api/v1/users/forgot-password").permitAll()
                        .requestMatchers("/api/v1/users/validate-reset-token").permitAll()
                        .requestMatchers("/api/v1/users/reset-password").permitAll()
                        // MFA setup/confirm during the login flow (no JWT available yet)
                        .requestMatchers("/api/v1/users/*/mfa/setup").permitAll()
                        .requestMatchers("/api/v1/users/*/mfa/confirm").permitAll()
                        // Called from auth-server-core during login flow (before JWT is issued)
                        // when the user has a temporary/admin-assigned password that must be changed
                        .requestMatchers("/api/v1/users/*/force-change-password").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder())));

        return http.build();
    }

    /**
     * JWT decoder that chains standard Spring validators with our custom
     * {@link RevokedSessionJwtValidator} for blacklist-based revocation.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> standardValidators = JwtValidators.createDefaultWithIssuer(issuerUri);

        OAuth2TokenValidator<Jwt> combined = new DelegatingOAuth2TokenValidator<>(
                standardValidators,
                revokedSessionJwtValidator // ← our blacklist check
        );

        decoder.setJwtValidator(combined);
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
