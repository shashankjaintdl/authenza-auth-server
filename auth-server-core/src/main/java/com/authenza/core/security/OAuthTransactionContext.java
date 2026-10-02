package com.authenza.core.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Immutable snapshot of the OAuth2 {@code /authorize} request parameters that were
 * active when the user was redirected to the login page.
 *
 * <p>Serialized into a compact HMAC-SHA256–signed token (the {@code tx} query parameter)
 * and passed client-side inside a hidden login form field. This allows the full OAuth2
 * authorization context to survive HTTP session expiry, pod restarts, and load-balancer
 * rerouting — eliminating the {@code MISSING_AUTH_REQUEST} error after long idle periods.
 *
 * <p>All fields match standard OAuth2 / PKCE parameter names to simplify URL reconstruction.
 *
 * @param tenantId            the tenant the authorization flow belongs to
 * @param clientId            the OAuth2 {@code client_id} parameter
 * @param redirectUri         the OAuth2 {@code redirect_uri} parameter
 * @param scope               the OAuth2 {@code scope} parameter
 * @param state               the OAuth2 {@code state} parameter (CSRF protection for the client)
 * @param responseType        the OAuth2 {@code response_type} parameter (usually {@code "code"})
 * @param codeChallenge       the PKCE {@code code_challenge} parameter (may be {@code null})
 * @param codeChallengeMethod the PKCE {@code code_challenge_method} parameter (may be {@code null})
 * @param issuedAt            when this token was created
 * @param expiresAt           when this token expires (default: 24 hours after {@code issuedAt})
 */
public record OAuthTransactionContext(
        String tenantId,
        String clientId,
        String redirectUri,
        String scope,
        String state,
        String responseType,
        String codeChallenge,
        String codeChallengeMethod,
        String nonce,
        Instant issuedAt,
        Instant expiresAt
) {
    /**
     * Jackson deserialization constructor — all fields bound by name so existing
     * serialized tokens remain compatible if new optional fields are added later.
     */
    @JsonCreator
    public OAuthTransactionContext(
            @JsonProperty("tenantId")            String  tenantId,
            @JsonProperty("clientId")            String  clientId,
            @JsonProperty("redirectUri")         String  redirectUri,
            @JsonProperty("scope")               String  scope,
            @JsonProperty("state")               String  state,
            @JsonProperty("responseType")        String  responseType,
            @JsonProperty("codeChallenge")       String  codeChallenge,
            @JsonProperty("codeChallengeMethod") String  codeChallengeMethod,
            @JsonProperty("nonce")               String  nonce,
            @JsonProperty("issuedAt")            Instant issuedAt,
            @JsonProperty("expiresAt")           Instant expiresAt
    ) {
        this.tenantId            = tenantId;
        this.clientId            = clientId;
        this.redirectUri         = redirectUri;
        this.scope               = scope;
        this.state               = state;
        this.responseType        = responseType;
        this.codeChallenge       = codeChallenge;
        this.codeChallengeMethod = codeChallengeMethod;
        this.nonce               = nonce;
        this.issuedAt            = issuedAt;
        this.expiresAt           = expiresAt;
    }
}
