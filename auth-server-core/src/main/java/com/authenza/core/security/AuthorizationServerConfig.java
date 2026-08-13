package com.authenza.core.security;

import com.authenza.adapter.cache.TenantSettingsCache;
import com.authenza.core.repository.TenantAwareRegisteredClientRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.web.authentication.ClientSecretBasicAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.ClientSecretPostAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.JwtClientAssertionAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.PublicClientAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationConverter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
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

        private final NamedParameterJdbcOperations masterJdbcOperations;
        private final DataSource dataSource;
        // Pre-built JdbcTemplate backed by the tenant RoutingDataSource.
        // Stored as a field to avoid creating a new object on every token issuance.
        private final JdbcTemplate jdbcTemplate;
        private final TenantSettingsCache tenantSettingsCache;

        /**
         * Static fallback — used when no tenant-specific portal_url setting is found.
         */
        @org.springframework.beans.factory.annotation.Value("${app.services.tenant-portal-url:http://localhost:4200}")
        private String defaultPortalUrl;

        public AuthorizationServerConfig(
                        @Qualifier("masterJdbcOperations") NamedParameterJdbcOperations masterJdbcOperations,
                        DataSource dataSource,
                        com.authenza.adapter.cache.TenantSettingsCache tenantSettingsCache) {
                this.masterJdbcOperations = masterJdbcOperations;
                this.dataSource = dataSource;
                this.jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
                this.tenantSettingsCache = tenantSettingsCache;
        }

        @Bean
        @Order(1)
        public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
                        MultiTenantSecurityFilter tenantSecurityFilter,
                        RegisteredClientRepository registeredClientRepository)
                        throws Exception {
                OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = OAuth2AuthorizationServerConfigurer
                                .authorizationServer();

                RequestMatcher tenantEndpointsMatcher = new OrRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher("/{tenantId}/userinfo"),
                                PathPatternRequestMatcher.withDefaults().matcher("/{tenantId}/oauth2/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/{tenantId}/connect/**"),
                                PathPatternRequestMatcher.withDefaults()
                                                .matcher("/{tenantId}/.well-known/openid-configuration"));

        // @formatter:off
        http
                .with(authorizationServerConfigurer, (authorizationServer) ->
                        authorizationServer
                                .clientAuthentication(clientAuth -> {
                                    // Converter: detects public-client refresh_token requests
                                    // (no code_verifier, no client_secret) and produces an
                                    // unauthenticated OAuth2ClientAuthenticationToken for the
                                    // provider below to validate.
                                   DelegatingAuthenticationConverter converters = new DelegatingAuthenticationConverter(
                                            Arrays.asList(
                                                new JwtClientAssertionAuthenticationConverter(),
                                                new ClientSecretBasicAuthenticationConverter(),
                                                new ClientSecretPostAuthenticationConverter(),
                                                new PublicClientAuthenticationConverter(),
                                                new PublicClientRefreshTokenConverter()
                                            )
                                        );
                                    clientAuth.authenticationConverter(converters);

                                    // Provider: validates the unauthenticated token produced above.
                                    // Registered at index 0 so it runs before Spring's default
                                    // PublicClientAuthenticationProvider, which would reject any
                                    // refresh_token request from a public client outright.
                                    clientAuth.authenticationProviders(providers ->
                                        providers.add(0, new PublicClientRefreshTokenAuthenticationProvider(registeredClientRepository))
                                    );
                                })
                                .oidc(oidc -> oidc
                                        .logoutEndpoint(logout -> logout
                                                .logoutResponseHandler((request, response, authentication) -> {
                                                    // SUCCESS CASE: Hint was valid.
                                                    performManualLogout(request);
                                                    performManualRedirect(request, response);
                                                })
                                                .errorResponseHandler((request, response, exception) -> {
                                                    // FAILURE CASE: Hint was missing or expired (OidcLogoutAuthenticationConverter failed).
                                                    // We still want to log them out and redirect for a good user experience.
                                                    performManualLogout(request);
                                                    performManualRedirect(request, response);
                                                })
                                        )
                                )
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
                // ──────────────────────────────────────────────────────────────────
                // EXCEPTION HANDLING: 302 REDIRECT vs 401 UNAUTHORIZED
                // ──────────────────────────────────────────────────────────────────
                // We use defaultAuthenticationEntryPointFor() with an AntPathRequestMatcher
                // to explicitly restrict our custom 302 Login Redirect to the browser-based
                // OAuth2 authorization flow (/{tenantId}/oauth2/authorize).
                // 
                // If we applied this entry point globally, background XHR/API requests
                // (like hitting /userinfo with an expired token) would receive a 302
                // instead of a 401. Browsers silently follow 302s, returning the HTML of
                // the login page as a 200 OK, completely breaking frontend auto-logout logic.
                // 
                // By filtering to /oauth2/authorize, all other API endpoints naturally 
                // fall back to Spring's default behavior: returning a 401 Unauthorized.
                .exceptionHandling((exceptions) -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new TenantAwareAuthenticationEntryPoint("/{tenantId}/login"),
                                PathPatternRequestMatcher.withDefaults().matcher("/{tenantId}/oauth2/authorize")
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
                return new TenantAwareRegisteredClientRepository(this.masterJdbcOperations);
        }

        /**
         * Replaces the default {@code InMemoryOAuth2AuthorizationService} with a
         * tenant-aware JDBC implementation backed by the {@code RoutingDataSource}.
         *
         * <p>
         * Each tenant's authorization records (auth codes, access tokens, refresh
         * tokens) are stored in their own isolated database, preventing cross-tenant
         * token leakage. Tokens survive server restarts and support horizontal scaling.
         */
        @Bean
        public OAuth2AuthorizationService authorizationService(
                        RegisteredClientRepository registeredClientRepository) {
                return new JdbcOAuth2AuthorizationService(
                                new JdbcTemplate(this.dataSource),
                                registeredClientRepository);
        }

        /**
         * Persists OAuth2 consent decisions per user per client in the tenant DB.
         * Replaces the default in-memory consent service.
         */
        @Bean
        public OAuth2AuthorizationConsentService authorizationConsentService(
                        RegisteredClientRepository registeredClientRepository) {
                return new JdbcOAuth2AuthorizationConsentService(
                                new JdbcTemplate(this.dataSource),
                                registeredClientRepository);
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
                } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                }
                return keyPair;
        }

        @Bean
        public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
                return new NimbusJwtEncoder(jwkSource);
        }

        @Bean
        public OAuth2TokenGenerator<?> tokenGenerator(JwtEncoder jwtEncoder,
                        OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer) {
                JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
                jwtGenerator.setJwtCustomizer(jwtTokenCustomizer);
                OAuth2AccessTokenGenerator accessTokenGenerator = new OAuth2AccessTokenGenerator();

                org.springframework.security.crypto.keygen.StringKeyGenerator keyGenerator = new org.springframework.security.crypto.keygen.Base64StringKeyGenerator(
                                java.util.Base64.getUrlEncoder().withoutPadding(), 96);

                return (context) -> {
                        if (OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
                                java.time.Instant issuedAt = java.time.Instant.now();
                                java.time.Instant expiresAt = issuedAt.plus(context.getRegisteredClient()
                                                .getTokenSettings().getRefreshTokenTimeToLive());
                                return new OAuth2RefreshToken(keyGenerator.generateKey(), issuedAt, expiresAt);
                        }

                        return new DelegatingOAuth2TokenGenerator(
                                        jwtGenerator, accessTokenGenerator).generate(context);
                };
        }

        @Bean // <7>
        public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
                return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        }

        @Bean // <8>
        public AuthorizationServerSettings authorizationServerSettings() {
                return AuthorizationServerSettings.builder()
                                .multipleIssuersAllowed(true)
                                .oidcLogoutEndpoint("/connect/logout")
                                .build();
        }

        @Bean
        public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
                return (context) -> {
                        // Customize Access Token and OIDC ID Token
                        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType()) ||
                                        context.getTokenType().getValue().equals("id_token")) {

                                System.out.println(
                                                "DEBUG: Authorized Scopes for token: " + context.getAuthorizedScopes());
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

                                // Bake the requires_password_change flag into the JWT
                                boolean requiresPasswordChange = false;
                                java.util.List<Boolean> pwdChangeResult = jdbcTemplate.query(
                                                "SELECT requires_password_change FROM application_user WHERE preferred_username = ? OR email = ? LIMIT 1",
                                                (rs, rowNum) -> rs.getBoolean("requires_password_change"),
                                                principal.getName(), principal.getName());
                                if (!pwdChangeResult.isEmpty()) {
                                        requiresPasswordChange = Boolean.TRUE.equals(pwdChangeResult.get(0));
                                }
                                context.getClaims().claim("password_change_required", requiresPasswordChange);

                                // ── Resolve User ID and Add to Claims ───────────────────────
                                String username = principal.getName();
                                java.util.List<Long> userIds = jdbcTemplate.queryForList(
                                                "SELECT id FROM application_user WHERE preferred_username = ? OR email = ?",
                                                Long.class, username, username);

                                if (!userIds.isEmpty()) {
                                        Long userId = userIds.get(0);
                                        context.getClaims().claim("user_id", userId.toString());

                                        // ── Link OAuth2 Authorization to Active Session ──────────────
                                        try {
                                                if (context.getAuthorization() != null
                                                                && context.getAuthorization().getId() != null) {
                                                        String authId = context.getAuthorization().getId();
                                                        // Link to the most recent un-linked, non-revoked session for this user.
                                                        // The revoked = false guard prevents a revoked session from being
                                                        // re-linked during a silent re-auth, which would embed a blacklisted
                                                        // session_id into the fresh JWT and cause an immediate 401.
                                                        jdbcTemplate.update(
                                                                        "UPDATE user_session SET authorization_id = ? "
                                                                                        +
                                                                                        "WHERE user_id = ? AND authorization_id IS NULL AND revoked = false "
                                                                                        +
                                                                                        "ORDER BY created_at DESC LIMIT 1",
                                                                        authId, userId);

                                                        // Embed session_id into the JWT so the resource server
                                                        // can blacklist it on revocation for immediate logout.
                                                        // Only select non-revoked sessions to ensure the claim
                                                        // is never set to an already-blacklisted session ID.
                                                        java.util.List<Long> sessionIds = jdbcTemplate.queryForList(
                                                                        "SELECT id FROM user_session WHERE authorization_id = ? AND user_id = ? AND revoked = false LIMIT 1",
                                                                        Long.class, authId, userId);
                                                        if (!sessionIds.isEmpty()) {
                                                                context.getClaims().claim("session_id",
                                                                                sessionIds.get(0).toString());
                                                        }
                                                }
                                        } catch (Exception e) {
                                                // Non-fatal session linking failure
                                        }

                                }
                        }
                };
        }

        private void performManualLogout(jakarta.servlet.http.HttpServletRequest request) {
                // 1. Manually clear the SecurityContext
                org.springframework.security.core.context.SecurityContextHolder.clearContext();

                // 2. Invalidate the HTTP Session explicitly
                jakarta.servlet.http.HttpSession session = request.getSession(false);
                if (session != null) {
                        session.invalidate();
                }
        }

        private void performManualRedirect(jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
                String redirectUri = request.getParameter("post_logout_redirect_uri");
                String clientId = request.getParameter("client_id");

                // If clientId is missing, try to extract it from the id_token_hint JWT
                if (clientId == null) {
                        String idTokenHint = request.getParameter("id_token_hint");
                        if (idTokenHint != null && idTokenHint.contains(".")) {
                                try {
                                        String[] parts = idTokenHint.split("\\.");
                                        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                                        if (payload.contains("\"aud\":")) {
                                                int start = payload.indexOf("\"aud\":\"") + 7;
                                                int end = payload.indexOf("\"", start);
                                                clientId = payload.substring(start, end);
                                        }
                                } catch (Exception e) {
                                        // Ignore parsing errors
                                }
                        }
                }

                boolean isValidRedirect = false;
                if (redirectUri != null && clientId != null) {
                        org.springframework.security.oauth2.server.authorization.client.RegisteredClient client = registeredClientRepository()
                                        .findByClientId(clientId);

                        if (client != null && client.getPostLogoutRedirectUris().contains(redirectUri)) {
                                isValidRedirect = true;
                        }
                }

                if (!isValidRedirect) {
                        redirectUri = resolveFallbackPortalUrl(request);
                }
                response.sendRedirect(redirectUri);
        }

        /**
         * Resolves the portal URL to redirect to after logout when the
         * {@code post_logout_redirect_uri} is missing or invalid.
         *
         * <p>
         * Resolution order:
         * <ol>
         * <li>Extracts the {@code tenantId} from the request path
         * (e.g. {@code /system-admin/connect/logout} → {@code system-admin}).</li>
         * <li>Reads the {@code portal_url} setting from {@code TenantSettingsCache}
         * for that tenant — allows per-tenant portal domains in multi-tenant
         * deployments.</li>
         * <li>Falls back to the application-wide {@code app.services.tenant-portal-url}
         * property if no tenant-specific override is configured.</li>
         * </ol>
         *
         * <p>
         * This is fully dynamic: no restart needed when a tenant's portal URL changes
         * — TenantSettingsCache refreshes every 5 minutes and on explicit invalidation.
         */
        private String resolveFallbackPortalUrl(jakarta.servlet.http.HttpServletRequest request) {
                try {
                        // Extract tenantId from path: /{tenantId}/connect/logout
                        String path = request.getRequestURI();
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                        .compile("^/([^/]+)/")
                                        .matcher(path);

                        if (m.find()) {
                                String tenantId = m.group(1);
                                String tenantPortalUrl = tenantSettingsCache.getSetting(tenantId, "portal_url");
                                if (tenantPortalUrl != null && !tenantPortalUrl.isBlank()) {
                                        return tenantPortalUrl;
                                }
                        }
                } catch (Exception e) {
                        // Non-fatal — fall through to default
                }
                return defaultPortalUrl;
        }
}
