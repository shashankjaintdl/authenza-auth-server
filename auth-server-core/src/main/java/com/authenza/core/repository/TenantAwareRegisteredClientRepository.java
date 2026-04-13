package com.authenza.core.repository;


import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.model.core.TenantRegisteredClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.*;

public final class TenantAwareRegisteredClientRepository implements RegisteredClientRepository {

    private final JdbcTenantClientRepository tenantClientRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TenantAwareRegisteredClientRepository(JdbcTenantClientRepository tenantClientRepository) {
        Assert.notNull(tenantClientRepository, "JdbcTenantClientRepository can't be null!");
        this.tenantClientRepository = tenantClientRepository;
        ClassLoader classLoader = TenantAwareRegisteredClientRepository.class.getClassLoader();
        List<Module> securityModule = SecurityJackson2Modules.getModules(classLoader);
        this.objectMapper.registerModules(securityModule);
        this.objectMapper.registerModules(new OAuth2AuthorizationServerJackson2Module());
    }


    // will use it for like (Github developer)
    @Override
    public void save(RegisteredClient registeredClient) {
        Assert.notNull(registeredClient, "RegisteredClient must not be null!");
        TenantRegisteredClient tenantRegisteredClient = toEntity(registeredClient);
        tenantRegisteredClient.setNew(true);
        this.tenantClientRepository.save(tenantRegisteredClient);
    }

    @Override
    public RegisteredClient findById(String id) {
        Assert.hasText(id, "Id must be present!");
        String tenantId = TenantContextHolder.getTenantId();
        TenantRegisteredClient tenantRegisteredClient = this.tenantClientRepository.findById(id).orElse(null);
        if (tenantRegisteredClient == null)
            throw new IllegalStateException("");

        return getRegisteredClient(tenantRegisteredClient, tenantId);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        Assert.hasText(clientId, "Client id must be present!");
        String tenantId = TenantContextHolder.getTenantId();
        TenantRegisteredClient tenantRegisteredClient = this.tenantClientRepository.findByClientId(clientId);
        if (tenantRegisteredClient == null)
            throw new AccessDeniedException("");
        return getRegisteredClient(tenantRegisteredClient, tenantId);
    }

    private RegisteredClient getRegisteredClient(TenantRegisteredClient tenantRegisteredClient, String tenantId) {
        RegisteredClient registeredClient = this.toObject(tenantRegisteredClient);
        String registeredTenantId = registeredClient.getClientSettings().getSetting("tenant_id");
        if (!StringUtils.hasText(registeredTenantId) || !registeredTenantId.equalsIgnoreCase(tenantId)) {
            throw new AccessDeniedException("");
        }
        return registeredClient;
    }


    private RegisteredClient toObject(TenantRegisteredClient client) {
        Set<String> clientAuthenticationMethods = StringUtils.commaDelimitedListToSet(client.getClientAuthenticationMethods());
        Set<String> clientScopes = StringUtils.commaDelimitedListToSet(client.getScopes());
        Set<String> authorizationGrantTypes = StringUtils.commaDelimitedListToSet(client.getAuthorizationGrantTypes());
        Set<String> redirectUris = StringUtils.commaDelimitedListToSet(client.getRedirectUris());
        Set<String> postLogoutRedirectUris = StringUtils.commaDelimitedListToSet(client.getPostLogoutRedirectUris());

        RegisteredClient.Builder registeredClient = RegisteredClient.withId(client.getId())
                .clientName(client.getClientName())
                .clientId(client.getClientId())
                .clientIdIssuedAt(client.getClientIdIssuedAt())
                .clientSecret(client.getClientSecret())
                .clientSecretExpiresAt(client.getClientSecretExpiresAt())
                .redirectUris(uris -> uris.addAll(redirectUris))
                .postLogoutRedirectUris(uris -> uris.addAll(postLogoutRedirectUris))
                .scopes(scopes -> scopes.addAll(clientScopes))
                .authorizationGrantTypes(grantTypes ->
                        authorizationGrantTypes.forEach(authorizationGrantType ->
                                grantTypes.add(resolveAuthorizationGrantType(authorizationGrantType))
                        )
                )
                .clientAuthenticationMethods(authenticationMethod ->
                        clientAuthenticationMethods.forEach(clientAuthenticationMethod -> authenticationMethod.add(resolveClientAuthenticationMethod(clientAuthenticationMethod)))
                )
                .clientSettings(ClientSettings.withSettings(parseMap(client.getClientSettings())).build())
                .tokenSettings(TokenSettings.withSettings(parseMap(client.getTokenSettings())).build());
        return registeredClient.build();
    }

    private TenantRegisteredClient toEntity(RegisteredClient registeredClient) {
        List<String> clientAuthenticationMethods = new ArrayList<>(registeredClient.getClientAuthenticationMethods().size());
        registeredClient.getClientAuthenticationMethods()
                .forEach(clientAuthenticationMethod -> clientAuthenticationMethods.add(clientAuthenticationMethod.getValue()));

        List<String> grantTypes = new ArrayList<>(registeredClient.getAuthorizationGrantTypes().size());
        registeredClient.getAuthorizationGrantTypes()
                .forEach(grantType -> grantTypes.add(grantType.getValue()));

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
            return this.objectMapper.readValue(data, new TypeReference<>() {
            });
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

}
