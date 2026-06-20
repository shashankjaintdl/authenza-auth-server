package com.authenza.core.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;

/**
 * An {@link AuthenticationProvider} that authenticates public clients
 * ({@code client_authentication_method=none}) for the {@code refresh_token} grant.
 *
 * <p>Spring's built-in {@code PublicClientAuthenticationProvider} only authenticates
 * public clients during the {@code authorization_code} flow (by verifying a PKCE
 * {@code code_verifier}). Because a refresh-token request carries no
 * {@code code_verifier}, the default provider ignores it, resulting in an
 * {@code invalid_client} / {@code invalid_grant} error.
 *
 * <p>This provider works together with {@link PublicClientRefreshTokenConverter}:
 * the converter creates an unauthenticated {@link OAuth2ClientAuthenticationToken}
 * tagged with {@code grant_type=refresh_token} in its {@code additionalParameters};
 * this provider detects that tag, validates the client against the
 * {@link RegisteredClientRepository}, and returns an authenticated token.
 *
 * <p>Register this provider at index 0 in the client-authentication provider list so
 * it is evaluated before the default {@code PublicClientAuthenticationProvider}:
 * <pre>{@code
 * clientAuth.authenticationProviders(providers ->
 *     providers.add(0, new PublicClientRefreshTokenAuthenticationProvider(repo))
 * );
 * }</pre>
 */
public final class PublicClientRefreshTokenAuthenticationProvider implements AuthenticationProvider {

    private final RegisteredClientRepository registeredClientRepository;

    public PublicClientRefreshTokenAuthenticationProvider(RegisteredClientRepository registeredClientRepository) {
        Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
        this.registeredClientRepository = registeredClientRepository;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        OAuth2ClientAuthenticationToken clientAuthToken =
                (OAuth2ClientAuthenticationToken) authentication;

        // Only handle public (no-secret) clients.
        if (!ClientAuthenticationMethod.NONE.equals(clientAuthToken.getClientAuthenticationMethod())) {
            return null;
        }

        // Only handle refresh_token grant — the additionalParameters map is populated
        // by PublicClientRefreshTokenConverter for exactly this purpose.
        Object grantType = clientAuthToken.getAdditionalParameters() != null
                ? clientAuthToken.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE)
                : null;
        if (!AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grantType)) {
            return null;
        }

        // Look up the client by client_id.
        RegisteredClient registeredClient =
                registeredClientRepository.findByClientId(clientAuthToken.getPrincipal().toString());
        if (registeredClient == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }

        // Confirm the client is actually configured as public (no secret).
        if (!registeredClient.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }

        // Return a fully authenticated token — no secret or code_verifier required.
        return new OAuth2ClientAuthenticationToken(
                registeredClient, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
