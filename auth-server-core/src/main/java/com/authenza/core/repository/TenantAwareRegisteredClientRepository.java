package com.authenza.core.repository;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.model.core.TenantRegisteredClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A tenant-aware implementation of Spring Security's
 * {@link RegisteredClientRepository}.
 *
 * <p>
 * This version directly queries the Master Database using NamedParameterJdbcOperations,
 * bypassing Spring Data mapping context metadata lookups to avoid cross-datasource mapping errors.
 * It queries utilizing findByClientIdAndTenantIdIn logic.
 * </p>
 */
public final class TenantAwareRegisteredClientRepository implements RegisteredClientRepository {

    private final NamedParameterJdbcOperations jdbcOperations;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TenantAwareRegisteredClientRepository(
            @Qualifier("masterJdbcOperations") NamedParameterJdbcOperations jdbcOperations) {
        Assert.notNull(jdbcOperations, "jdbcOperations can't be null!");
        this.jdbcOperations = jdbcOperations;
        ClassLoader classLoader = TenantAwareRegisteredClientRepository.class.getClassLoader();
        List<Module> securityModule = SecurityJackson2Modules.getModules(classLoader);
        this.objectMapper.registerModules(securityModule);
        this.objectMapper.registerModules(new OAuth2AuthorizationServerJackson2Module());
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        Assert.notNull(registeredClient, "RegisteredClient must not be null!");
        TenantRegisteredClient entity = toEntity(registeredClient);
        String tenantId = TenantContextHolder.getTenantId();
        entity.setTenantId(tenantId);
        entity.setClientSettings(addTenantIdToSettings(registeredClient.getClientSettings(), tenantId));

        String sql = "INSERT INTO oauth2_registered_client (id, client_id, client_id_issued_at, client_secret, " +
                "client_secret_expires_at, client_name, client_authentication_methods, authorization_grant_types, " +
                "redirect_uris, post_logout_redirect_uris, scopes, client_settings, token_settings, tenant_id) " +
                "VALUES (:id, :clientId, :clientIdIssuedAt, :clientSecret, :clientSecretExpiresAt, :clientName, " +
                ":clientAuthenticationMethods, :authorizationGrantTypes, :redirectUris, :postLogoutRedirectUris, " +
                ":scopes, :clientSettings, :tokenSettings, :tenantId) " +
                "ON DUPLICATE KEY UPDATE client_id = :clientId, client_secret = :clientSecret, " +
                "client_secret_expires_at = :clientSecretExpiresAt, client_name = :clientName, " +
                "client_authentication_methods = :clientAuthenticationMethods, " +
                "authorization_grant_types = :authorizationGrantTypes, redirect_uris = :redirectUris, " +
                "post_logout_redirect_uris = :postLogoutRedirectUris, scopes = :scopes, " +
                "client_settings = :clientSettings, token_settings = :tokenSettings, tenant_id = :tenantId";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", entity.getId())
                .addValue("clientId", entity.getClientId())
                .addValue("clientIdIssuedAt", entity.getClientIdIssuedAt() != null ? java.sql.Timestamp.from(entity.getClientIdIssuedAt()) : null)
                .addValue("clientSecret", entity.getClientSecret())
                .addValue("clientSecretExpiresAt", entity.getClientSecretExpiresAt() != null ? java.sql.Timestamp.from(entity.getClientSecretExpiresAt()) : null)
                .addValue("clientName", entity.getClientName())
                .addValue("clientAuthenticationMethods", entity.getClientAuthenticationMethods())
                .addValue("authorization_grant_types", entity.getAuthorizationGrantTypes())
                .addValue("redirectUris", entity.getRedirectUris())
                .addValue("postLogoutRedirectUris", entity.getPostLogoutRedirectUris())
                .addValue("scopes", entity.getScopes())
                .addValue("clientSettings", entity.getClientSettings())
                .addValue("tokenSettings", entity.getTokenSettings())
                .addValue("tenantId", entity.getTenantId());

        this.jdbcOperations.update(sql, params);
    }

    @Override
    public RegisteredClient findById(String id) {
        Assert.hasText(id, "Id must be present!");
        String tenantId = TenantContextHolder.getTenantId();

        String sql = "SELECT * FROM oauth2_registered_client WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);

        try {
            TenantRegisteredClient client = this.jdbcOperations.queryForObject(sql, params, new TenantRegisteredClientRowMapper());
            if (client == null) {
                return null;
            }
            return getRegisteredClient(client, tenantId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        Assert.hasText(clientId, "Client id must be present!");
        String tenantId = TenantContextHolder.getTenantId();

        // Exact equivalent query logic to: findByClientIdAndTenantIdIn
        String sql = "SELECT * FROM oauth2_registered_client WHERE client_id = :clientId AND tenant_id IN (:tenantIds)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("tenantIds", List.of("GLOBAL", tenantId));

        try {
            TenantRegisteredClient client = this.jdbcOperations.queryForObject(sql, params, new TenantRegisteredClientRowMapper());
            if (client == null) {
                return null;
            }
            return getRegisteredClient(client, tenantId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private RegisteredClient getRegisteredClient(TenantRegisteredClient tenantRegisteredClient, String tenantId) {
        RegisteredClient registeredClient = this.toObject(tenantRegisteredClient);

        String registeredTenantId = registeredClient.getClientSettings().getSetting("tenant_id");
        if (StringUtils.hasText(registeredTenantId)
                && !tenantRegisteredClient.getTenantId().equals("GLOBAL")
                && !registeredTenantId.equalsIgnoreCase("GLOBAL")
                && !registeredTenantId.equalsIgnoreCase(tenantId)) {
            throw new AccessDeniedException("Access denied for this client under the current tenant context.");
        }

        return registeredClient;
    }

    private String addTenantIdToSettings(ClientSettings settings, String tenantId) {
        Map<String, Object> map = new HashMap<>(settings.getSettings());
        map.put("tenant_id", tenantId);
        try {
            return this.objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private RegisteredClient toObject(TenantRegisteredClient client) {
        Set<String> clientAuthenticationMethods = StringUtils.commaDelimitedListToSet(client.getClientAuthenticationMethods());
        Set<String> clientScopes = StringUtils.commaDelimitedListToSet(client.getScopes())
                .stream().map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
        
        clientScopes.add("offline_access");

        Set<String> authorizationGrantTypes = StringUtils.commaDelimitedListToSet(client.getAuthorizationGrantTypes())
                .stream().map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
        authorizationGrantTypes.add("refresh_token");
        Set<String> redirectUris = StringUtils.commaDelimitedListToSet(client.getRedirectUris());
        Set<String> postLogoutRedirectUris = StringUtils.commaDelimitedListToSet(client.getPostLogoutRedirectUris());

        Map<String, Object> clientSettingsMap = new HashMap<>(ClientSettings.builder().build().getSettings());
        clientSettingsMap.putAll(parseMap(client.getClientSettings()));

        Map<String, Object> tokenSettingsMap = new HashMap<>(TokenSettings.builder().build().getSettings());
        tokenSettingsMap.putAll(parseMap(client.getTokenSettings()));

        return RegisteredClient.withId(client.getId())
                .clientName(client.getClientName())
                .clientId(client.getClientId())
                .clientIdIssuedAt(client.getClientIdIssuedAt())
                .clientSecret(client.getClientSecret())
                .clientSecretExpiresAt(client.getClientSecretExpiresAt())
                .redirectUris(uris -> uris.addAll(redirectUris))
                .postLogoutRedirectUris(uris -> uris.addAll(postLogoutRedirectUris))
                .scopes(scopes -> scopes.addAll(clientScopes))
                .authorizationGrantTypes(grantTypes -> authorizationGrantTypes.forEach(gt -> grantTypes.add(resolveAuthorizationGrantType(gt))))
                .clientAuthenticationMethods(methods -> clientAuthenticationMethods.forEach(cam -> methods.add(resolveClientAuthenticationMethod(cam))))
                .clientSettings(ClientSettings.withSettings(clientSettingsMap).build())
                .tokenSettings(TokenSettings.withSettings(tokenSettingsMap).build())
                .build();
    }

    private TenantRegisteredClient toEntity(RegisteredClient registeredClient) {
        List<String> clientAuthenticationMethods = new ArrayList<>(registeredClient.getClientAuthenticationMethods().size());
        registeredClient.getClientAuthenticationMethods().forEach(cam -> clientAuthenticationMethods.add(cam.getValue()));

        List<String> grantTypes = new ArrayList<>(registeredClient.getAuthorizationGrantTypes().size());
        registeredClient.getAuthorizationGrantTypes().forEach(gt -> grantTypes.add(gt.getValue()));

        TenantRegisteredClient client = new TenantRegisteredClient();
        client.setId(registeredClient.getId());
        client.setClientId(registeredClient.getClientId());
        client.setClientIdIssuedAt(registeredClient.getClientIdIssuedAt());
        client.setClientSecretExpiresAt(registeredClient.getClientSecretExpiresAt());
        client.setClientSecret(registeredClient.getClientSecret());
        client.setClientName(registeredClient.getClientName());
        client.setScopes(StringUtils.collectionToCommaDelimitedString(registeredClient.getScopes()));
        client.setClientAuthenticationMethods(StringUtils.collectionToCommaDelimitedString(clientAuthenticationMethods));
        client.setAuthorizationGrantTypes(StringUtils.collectionToCommaDelimitedString(grantTypes));
        client.setPostLogoutRedirectUris(StringUtils.collectionToCommaDelimitedString(registeredClient.getPostLogoutRedirectUris()));
        client.setRedirectUris(StringUtils.collectionToCommaDelimitedString(registeredClient.getRedirectUris()));
        client.setClientSettings(writeMap(registeredClient.getClientSettings().getSettings()));
        client.setTokenSettings(writeMap(registeredClient.getTokenSettings().getSettings()));
        return client;
    }

    private String writeMap(Map<String, Object> objectMap) {
        try {
            return this.objectMapper.writeValueAsString(objectMap);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private Map<String, Object> parseMap(String data) {
        try {
            return this.objectMapper.readValue(data, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static ClientAuthenticationMethod resolveClientAuthenticationMethod(String authenticationMethod) {
        if (ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue().equals(authenticationMethod)) {
            return ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
        } else if (ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue().equals(authenticationMethod)) {
            return ClientAuthenticationMethod.CLIENT_SECRET_POST;
        } else if (ClientAuthenticationMethod.CLIENT_SECRET_JWT.getValue().equals(authenticationMethod)) {
            return ClientAuthenticationMethod.CLIENT_SECRET_JWT;
        } else if (ClientAuthenticationMethod.PRIVATE_KEY_JWT.getValue().equals(authenticationMethod)) {
            return ClientAuthenticationMethod.PRIVATE_KEY_JWT;
        } else if (ClientAuthenticationMethod.NONE.getValue().equals(authenticationMethod)) {
            return ClientAuthenticationMethod.NONE;
        }
        return new ClientAuthenticationMethod(authenticationMethod);
    }

    private static AuthorizationGrantType resolveAuthorizationGrantType(String authorizationGrantType) {
        if (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(authorizationGrantType)) {
            return AuthorizationGrantType.AUTHORIZATION_CODE;
        } else if (AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(authorizationGrantType)) {
            return AuthorizationGrantType.CLIENT_CREDENTIALS;
        } else if (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(authorizationGrantType)) {
            return AuthorizationGrantType.REFRESH_TOKEN;
        } else if (AuthorizationGrantType.DEVICE_CODE.getValue().equals(authorizationGrantType)) {
            return AuthorizationGrantType.DEVICE_CODE;
        } else if (AuthorizationGrantType.JWT_BEARER.getValue().equals(authorizationGrantType)) {
            return AuthorizationGrantType.JWT_BEARER;
        }
        return new AuthorizationGrantType(authorizationGrantType);
    }

    private static class TenantRegisteredClientRowMapper implements RowMapper<TenantRegisteredClient> {
        @Override
        public TenantRegisteredClient mapRow(ResultSet rs, int rowNum) throws SQLException {
            TenantRegisteredClient client = new TenantRegisteredClient();
            client.setId(rs.getString("id"));
            client.setClientId(rs.getString("client_id"));
            client.setClientIdIssuedAt(rs.getTimestamp("client_id_issued_at") != null ? rs.getTimestamp("client_id_issued_at").toInstant() : null);
            client.setClientSecret(rs.getString("client_secret"));
            client.setClientSecretExpiresAt(rs.getTimestamp("client_secret_expires_at") != null ? rs.getTimestamp("client_secret_expires_at").toInstant() : null);
            client.setClientName(rs.getString("client_name"));
            client.setClientAuthenticationMethods(rs.getString("client_authentication_methods"));
            client.setAuthorizationGrantTypes(rs.getString("authorization_grant_types"));
            client.setRedirectUris(rs.getString("redirect_uris"));
            client.setPostLogoutRedirectUris(rs.getString("post_logout_redirect_uris"));
            client.setScopes(rs.getString("scopes"));
            client.setClientSettings(rs.getString("client_settings"));
            client.setTokenSettings(rs.getString("token_settings"));
            client.setTenantId(rs.getString("tenant_id"));
            return client;
        }
    }
}
