package com.authenza.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Public SPA OAuth2 client for the Tenant Portal (Auth Code + PKCE).
 * Seeded into {@code auth-master.oauth2_registered_client} on startup.
 */
@ConfigurationProperties(prefix = "app.oidc-portal-client")
public class OidcPortalClientProperties {

    private boolean enabled = true;
    private String clientId = "oidc-client";
    private String clientName = "Tenant Portal OIDC Client";
    /** Row {@code tenant_id}; {@code GLOBAL} is resolvable from any issuer path. */
    private String tenantId = "GLOBAL";
    /** Value stored in client_settings.tenant_id (usually GLOBAL for the portal). */
    private String settingsTenantId = "GLOBAL";
    private Set<String> scopes = Set.of(
            "openid", "profile", "offline_access", "authenza:tenant:admin");
    private Set<String> redirectUris = Set.of(
            "http://localhost:4200/login/callback",
            "http://127.0.0.1:4200/login/callback");
    private Set<String> postLogoutRedirectUris = Set.of(
            "http://localhost:4200/",
            "http://127.0.0.1:4200/");
    private Set<String> grantTypes = Set.of("authorization_code", "refresh_token");
    /** Public client — no secret. */
    private Set<String> authenticationMethods = Set.of("none");
    private boolean requireConsent = false;
    private boolean requireProofKey = true;

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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getSettingsTenantId() {
        return settingsTenantId;
    }

    public void setSettingsTenantId(String settingsTenantId) {
        this.settingsTenantId = settingsTenantId;
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
