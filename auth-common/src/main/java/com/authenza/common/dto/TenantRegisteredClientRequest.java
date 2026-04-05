package com.authenza.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Objects;
import java.util.Set;

public class TenantRegisteredClientRequest {

    @NotBlank(message = "Client ID is required")
    private String clientId;

    @NotBlank(message = "Client Name is required")
    private String clientName;

    private String clientSecret; // Optional: If empty, service can generate one

    @NotEmpty(message = "At least one authentication method is required")
    private Set<String> clientAuthenticationMethods; // e.g., ["client_secret_basic"]

    @NotEmpty(message = "At least one grant type is required")
    private Set<String> authorizationGrantTypes; // e.g., ["authorization_code", "refresh_token"]

    @NotEmpty(message = "At least one redirect URI is required")
    private Set<String> redirectUris;

    private Set<String> postLogoutRedirectUris;

    @NotEmpty(message = "At least one scope is required")
    private Set<String> scopes; // e.g., ["openid", "profile", "email"]

    // Optional: Settings with sensible defaults
    private boolean requireAuthorizationConsent = true;
    private boolean requireProofKey = false; // PKCE

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

    public Set<String> getClientAuthenticationMethods() {
        return clientAuthenticationMethods;
    }

    public void setClientAuthenticationMethods(Set<String> clientAuthenticationMethods) {
        this.clientAuthenticationMethods = clientAuthenticationMethods;
    }

    public Set<String> getAuthorizationGrantTypes() {
        return authorizationGrantTypes;
    }

    public void setAuthorizationGrantTypes(Set<String> authorizationGrantTypes) {
        this.authorizationGrantTypes = authorizationGrantTypes;
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

    public Set<String> getScopes() {
        return scopes;
    }

    public void setScopes(Set<String> scopes) {
        this.scopes = scopes;
    }

    public boolean isRequireAuthorizationConsent() {
        return requireAuthorizationConsent;
    }

    public void setRequireAuthorizationConsent(boolean requireAuthorizationConsent) {
        this.requireAuthorizationConsent = requireAuthorizationConsent;
    }

    public boolean isRequireProofKey() {
        return requireProofKey;
    }

    public void setRequireProofKey(boolean requireProofKey) {
        this.requireProofKey = requireProofKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TenantRegisteredClientRequest that = (TenantRegisteredClientRequest) o;
        return Objects.equals(clientId, that.clientId) &&
                Objects.equals(clientName, that.clientName) &&
                Objects.equals(scopes, that.scopes) &&
                Objects.equals(redirectUris, that.redirectUris);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId, clientName, scopes, redirectUris);
    }
}