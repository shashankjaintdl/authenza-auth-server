package com.authenza.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "app.super-admin-client")
public class SuperAdminClientProperties {

    private boolean enabled = true;
    private String clientId = "super-admin-client";
    private String clientName = "Super Admin Client";
    private String clientSecret = "super-admin-secret";
    private String tenantId = "system-admin";
    private Set<String> scopes = Set.of("openid", "profile", "system:admin", "tenant:manage");
    private Set<String> redirectUris = Set.of("http://localhost:8080/login/oauth2/code/super-admin");
    private Set<String> postLogoutRedirectUris = Set.of("http://localhost:8080/");
    private Set<String> grantTypes = Set.of("authorization_code", "refresh_token", "client_credentials");
    private Set<String> authenticationMethods = Set.of("client_secret_basic");
    private boolean requireConsent = false;
    private boolean requireProofKey = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Set<String> getScopes() {
        return scopes;
    }

    public void setScopes(Set<String> scopes) {
        this.scopes = scopes;
    }

    public Set<String> getRedirectUris() {
        return redirectUris;
    }

    public void setRedirectUris(Set<String> redirectUris) {
        this.redirectUris = redirectUris;
    }

    public Set<String> getPostLogoutRedirectUris() {
        return postLogoutRedirectUris;
    }

    public void setPostLogoutRedirectUris(Set<String> postLogoutRedirectUris) {
        this.postLogoutRedirectUris = postLogoutRedirectUris;
    }

    public Set<String> getGrantTypes() {
        return grantTypes;
    }

    public void setGrantTypes(Set<String> grantTypes) {
        this.grantTypes = grantTypes;
    }

    public Set<String> getAuthenticationMethods() {
        return authenticationMethods;
    }

    public void setAuthenticationMethods(Set<String> authenticationMethods) {
        this.authenticationMethods = authenticationMethods;
    }

    public boolean isRequireConsent() {
        return requireConsent;
    }

    public void setRequireConsent(boolean requireConsent) {
        this.requireConsent = requireConsent;
    }

    public boolean isRequireProofKey() {
        return requireProofKey;
    }

    public void setRequireProofKey(boolean requireProofKey) {
        this.requireProofKey = requireProofKey;
    }
}
