package com.authenza.core.service;

import com.authenza.core.config.OidcPortalClientProperties;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Registers the public Tenant Portal OAuth2 client ({@code oidc-client}) into
 * {@code auth-master.oauth2_registered_client} on startup — Auth Code + PKCE, no secret.
 *
 * <p>Idempotent: deletes and re-creates the row so YAML config always wins.</p>
 */
@Component
public class OidcPortalClientInitializer {

    private static final Logger log = LoggerFactory.getLogger(OidcPortalClientInitializer.class);

    private static final String CHECK_SQL =
            "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_id = :clientId";

    private static final String INSERT_SQL = "INSERT INTO oauth2_registered_client " +
            "(id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, " +
            "client_name, client_authentication_methods, authorization_grant_types, " +
            "redirect_uris, post_logout_redirect_uris, scopes, client_settings, token_settings, tenant_id) " +
            "VALUES (:id, :clientId, :clientIdIssuedAt, :clientSecret, :clientSecretExpiresAt, " +
            ":clientName, :clientAuthenticationMethods, :authorizationGrantTypes, " +
            ":redirectUris, :postLogoutRedirectUris, :scopes, :clientSettings, :tokenSettings, :tenantId)";

    private final NamedParameterJdbcOperations jdbcOperations;
    private final OidcPortalClientProperties properties;
    private final ObjectMapper objectMapper;

    public OidcPortalClientInitializer(
            @Qualifier("masterJdbcOperations") NamedParameterJdbcOperations jdbcOperations,
            OidcPortalClientProperties properties) {
        this.jdbcOperations = jdbcOperations;
        this.properties = properties;

        this.objectMapper = new ObjectMapper();
        ClassLoader classLoader = OidcPortalClientInitializer.class.getClassLoader();
        List<Module> securityModules = SecurityJackson2Modules.getModules(classLoader);
        this.objectMapper.registerModules(securityModules);
        this.objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void registerOidcPortalClient() {
        if (!properties.isEnabled()) {
            log.info("OIDC portal client registration is disabled.");
            return;
        }

        try {
            MapSqlParameterSource checkParams = new MapSqlParameterSource("clientId", properties.getClientId());
            Integer count = this.jdbcOperations.queryForObject(CHECK_SQL, checkParams, Integer.class);

            if (count != null && count > 0) {
                this.jdbcOperations.update(
                        "DELETE FROM oauth2_registered_client WHERE client_id = :clientId",
                        checkParams);
                log.info("Deleted existing OIDC portal client '{}' — will re-create with current config.",
                        properties.getClientId());
            }

            Instant now = Instant.now();
            MapSqlParameterSource insertParams = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString())
                    .addValue("clientId", properties.getClientId())
                    .addValue("clientIdIssuedAt", Timestamp.from(now))
                    .addValue("clientSecret", null)
                    .addValue("clientSecretExpiresAt", null)
                    .addValue("clientName", properties.getClientName())
                    .addValue("clientAuthenticationMethods", String.join(",", properties.getAuthenticationMethods()))
                    .addValue("authorizationGrantTypes", String.join(",", properties.getGrantTypes()))
                    .addValue("redirectUris", String.join(",", properties.getRedirectUris()))
                    .addValue("postLogoutRedirectUris", String.join(",", properties.getPostLogoutRedirectUris()))
                    .addValue("scopes", String.join(",", properties.getScopes()))
                    .addValue("clientSettings", buildClientSettingsJson())
                    .addValue("tokenSettings", buildTokenSettingsJson())
                    .addValue("tenantId", properties.getTenantId());

            this.jdbcOperations.update(INSERT_SQL, insertParams);

            log.info(
                    "OIDC portal client '{}' registered (public, PKCE={}) under tenant_id='{}'.",
                    properties.getClientId(),
                    properties.isRequireProofKey(),
                    properties.getTenantId());
        } catch (Exception e) {
            log.error("Failed to register OIDC portal client in master database.", e);
        }
    }

    private String buildClientSettingsJson() {
        try {
            ClientSettings clientSettings = ClientSettings.builder()
                    .requireAuthorizationConsent(properties.isRequireConsent())
                    .requireProofKey(properties.isRequireProofKey())
                    .setting("tenant_id", properties.getSettingsTenantId())
                    .build();
            return objectMapper.writeValueAsString(clientSettings.getSettings());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize OIDC portal client settings", e);
        }
    }

    private String buildTokenSettingsJson() {
        try {
            TokenSettings tokenSettings = TokenSettings.builder()
                    .accessTokenTimeToLive(Duration.ofMinutes(30))
                    .refreshTokenTimeToLive(Duration.ofDays(30))
                    .reuseRefreshTokens(false)
                    .build();
            return objectMapper.writeValueAsString(tokenSettings.getSettings());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize OIDC portal token settings", e);
        }
    }
}
