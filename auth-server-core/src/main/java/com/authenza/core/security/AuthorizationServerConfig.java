package com.authenza.core.security;

import com.authenza.core.repository.JdbcTenantClientRepository;
import com.authenza.core.repository.TenantAwareRegisteredClientRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.authenza.adapter.context.TenantContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@Configuration
public class AuthorizationServerConfig {


    private final JdbcTenantClientRepository tenantClientRepository;

    public AuthorizationServerConfig(JdbcTenantClientRepository tenantClientRepository) {
        this.tenantClientRepository = tenantClientRepository;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
                                                                      MultiTenantSecurityFilter tenantSecurityFilter)
            throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();

        RequestMatcher tenantEndpointsMatcher = new OrRequestMatcher(
                new AntPathRequestMatcher("/{tenantId}/oauth2/**"),
                new AntPathRequestMatcher("/{tenantId}/.well-known/openid-configuration")
        );

        // @formatter:off
        http
                .with(authorizationServerConfigurer, (authorizationServer) ->
                        authorizationServer
                                .oidc(Customizer.withDefaults())	// Enable OpenID Connect 1.0
                )
                .securityMatcher(tenantEndpointsMatcher)
                .addFilterBefore(tenantSecurityFilter, DisableEncodeUrlFilter.class)
                // Enable CORS so that XHR requests to /.well-known/openid-configuration
                // (and other OAuth2 endpoints) receive proper Access-Control-* headers.
                // Delegates to the CorsConfigurationSource bean in CorsConfig.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests((authorize) ->
                        authorize
                                .requestMatchers("/{tenantId}/.well-known/openid-configuration").permitAll()
                                .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers(tenantEndpointsMatcher))
                // Redirect to the login page when not authenticated from the
                // authorization endpoint
                .exceptionHandling((exceptions) -> exceptions
                        .authenticationEntryPoint(
                                new TenantAwareAuthenticationEntryPoint("/{tenantId}/login")
                        )
                );
        // @formatter:on

        return http.build();
    }

    /**
     * Returns a tenant-aware RegisteredClientRepository.
     * IMPORTANT: Do NOT save/register clients here at startup — there is no tenant
     * context during bean creation, so the RoutingDataSource would fall back to
     * the master DB, which does not have the oauth2_registered_client table.
     *
     * OAuth2 clients should be registered per-tenant during the tenant onboarding
     * process (via the master-service API).
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        return new TenantAwareRegisteredClientRepository(this.tenantClientRepository);
    }

    @Bean // <5>
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        // @formatter:off
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        // @formatter:on
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private static KeyPair generateRsaKey() { // <6>
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return keyPair;
    }

    @Bean // <7>
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean // <8>
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .multipleIssuersAllowed(true)
                .build();
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return (context) -> {
            // Customize Access Token and OIDC ID Token
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType()) ||
                    context.getTokenType().getValue().equals("id_token")) {

                Authentication principal = context.getPrincipal();

                // Extract all granted authorities mapping to role/permission strings
                Set<String> authorities = principal.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());

                // Split into Roles (Start with ROLE_) and Permissions
                Set<String> roles = authorities.stream()
                        .filter(a -> a.startsWith("ROLE_"))
                        .map(a -> a.replaceFirst("ROLE_", ""))
                        .collect(Collectors.toSet());

                Set<String> permissions = authorities.stream()
                        .filter(a -> !a.startsWith("ROLE_"))
                        .collect(Collectors.toSet());

                // Bake into the JWT payload
                context.getClaims().claim("roles", roles);
                context.getClaims().claim("permissions", permissions);

                // Bake the Tenant ID into the JWT to prevent Cross-Tenant bleed
                String tenantId = TenantContextHolder.getTenantId();
                if (tenantId != null) {
                    context.getClaims().claim("tenant_id", tenantId);
                }
            }
        };
    }

}
