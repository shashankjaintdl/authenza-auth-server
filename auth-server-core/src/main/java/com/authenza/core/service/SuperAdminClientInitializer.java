package com.authenza.core.service;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.core.config.SuperAdminClientProperties;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Registers a default super-admin OAuth2 client into the super-admin's
 * TENANT database on application startup. Uses the TenantRoutingDataSource
 * with TenantContextHolder set to the super-admin tenant ID.
 *
 * <p>The master database (auth_master) only contains the tenants registry.
 * All OAuth2 client data lives in tenant-specific schemas.</p>
 *
 * <p>This initializer is idempotent — it deletes and re-creates the client
 * on every startup to ensure settings are always up-to-date.</p>
 */
@Component
public class SuperAdminClientInitializer {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminClientInitializer.class);

    private static final String CHECK_SQL =
            "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_id = ?";

    private static final String INSERT_SQL =
            "INSERT INTO oauth2_registered_client " +
            "(id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, " +
            "client_name, client_authentication_methods, authorization_grant_types, " +
            "redirect_uris, post_logout_redirect_uris, scopes, client_settings, token_settings) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;
    private final SuperAdminClientProperties properties;
    private final ObjectMapper objectMapper;

    public SuperAdminClientInitializer(
            DataSource dataSource,
            PasswordEncoder passwordEncoder,
            SuperAdminClientProperties properties) {
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;

        // Configure ObjectMapper with Spring Security modules
        this.objectMapper = new ObjectMapper();
        ClassLoader classLoader = SuperAdminClientInitializer.class.getClassLoader();
        List<Module> securityModules = SecurityJackson2Modules.getModules(classLoader);
        this.objectMapper.registerModules(securityModules);
        this.objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void registerSuperAdminClient() {
        if (!properties.isEnabled()) {
            log.info("Super admin client registration is disabled.");
            return;
        }

        try {
            // Set the tenant context so the TenantRoutingDataSource routes
            // to the super-admin's dedicated database (e.g., auth_super_admin)
            TenantContextHolder.setTenantId(properties.getTenantId());

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            // Delete any existing row to ensure settings JSON is always up-to-date
            Integer count = jdbcTemplate.queryForObject(
                    CHECK_SQL, Integer.class, properties.getClientId());

            if (count != null && count > 0) {
                jdbcTemplate.update(
                        "DELETE FROM oauth2_registered_client WHERE client_id = ?",
                        properties.getClientId());
                log.info("Deleted existing super admin client '{}' — will re-create with current config.",
                        properties.getClientId());
            }

            // Build and insert the super admin client
            String id = UUID.randomUUID().toString();
            String encodedSecret = passwordEncoder.encode(properties.getClientSecret());
            Instant now = Instant.now();

            String authMethods = String.join(",", properties.getAuthenticationMethods());
            String grantTypes = String.join(",", properties.getGrantTypes());
            String redirectUris = String.join(",", properties.getRedirectUris());
            String postLogoutUris = String.join(",", properties.getPostLogoutRedirectUris());
            String scopes = String.join(",", properties.getScopes());

            String clientSettingsJson = buildClientSettingsJson();
            String tokenSettingsJson = buildTokenSettingsJson();

            jdbcTemplate.update(INSERT_SQL,
                    id,
                    properties.getClientId(),
                    now,
                    encodedSecret,
                    null,
                    properties.getClientName(),
                    authMethods,
                    grantTypes,
                    redirectUris,
                    postLogoutUris,
                    scopes,
                    clientSettingsJson,
                    tokenSettingsJson
            );

            log.info("Super admin client '{}' registered successfully in tenant database '{}'.",
                    properties.getClientId(), properties.getTenantId());

        } catch (Exception e) {
            log.error("Failed to register super admin client in tenant database.", e);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private String buildClientSettingsJson() {
        try {
            // Use Spring's ClientSettings builder for proper structure
            ClientSettings clientSettings = ClientSettings.builder()
                    .requireAuthorizationConsent(properties.isRequireConsent())
                    .requireProofKey(properties.isRequireProofKey())
                    .setting("tenant_id", properties.getTenantId())
                    .build();

            // Serialize the settings map — the Security-aware ObjectMapper
            // will include @class type metadata as needed
            return objectMapper.writeValueAsString(clientSettings.getSettings());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize client settings", e);
        }
    }

    private String buildTokenSettingsJson() {
        try {
            // Use Spring's TokenSettings builder with defaults
            TokenSettings tokenSettings = TokenSettings.builder().build();

            // Serialize the settings map — Duration, OAuth2TokenFormat, etc.
            // will be properly serialized with @class metadata
            return objectMapper.writeValueAsString(tokenSettings.getSettings());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize token settings", e);
        }
    }
}

