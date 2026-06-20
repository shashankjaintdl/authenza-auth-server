package com.authenza.core.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * An {@link AuthenticationConverter} that handles client authentication for public
 * clients (i.e. {@code client_authentication_method=none}) during the
 * {@code refresh_token} grant.
 *
 * <p>Spring's built-in {@code PublicClientAuthenticationConverter} only handles PKCE
 * flows and therefore ignores refresh-token requests from public clients, causing a
 * silent {@code 401 Unauthorized}. This converter fills that gap.
 *
 * <p>It only activates when all of the following are true:
 * <ul>
 *   <li>The HTTP method is {@code POST}.</li>
 *   <li>{@code grant_type=refresh_token}.</li>
 *   <li>A {@code client_id} is present in the form parameters.</li>
 *   <li>Neither a {@code client_secret} nor a Basic-Auth {@code Authorization} header
 *       is present (confidential clients are handled by other converters).</li>
 * </ul>
 *
 * <p>The produced {@link OAuth2ClientAuthenticationToken} carries
 * {@code ClientAuthenticationMethod.NONE} and passes {@code grant_type} in its
 * {@code additionalParameters} map so that
 * {@link PublicClientRefreshTokenAuthenticationProvider} can distinguish this token
 * from an authorization-code token.
 */
public final class PublicClientRefreshTokenConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        // Only handle POST requests.
        if (!HttpMethod.POST.name().equals(request.getMethod())) {
            return null;
        }

        // Only handle refresh_token grant type.
        String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
        if (!AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grantType)) {
            return null;
        }

        // A client_id must be present.
        String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
        if (!StringUtils.hasText(clientId)) {
            return null;
        }

        // If a client_secret or Basic-Auth header is present, a confidential-client
        // converter (e.g. ClientSecretPostAuthenticationConverter) should handle this.
        if (StringUtils.hasText(request.getParameter(OAuth2ParameterNames.CLIENT_SECRET))
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return null;
        }

        // Pass grant_type in additionalParameters so the downstream provider can
        // distinguish a refresh-token client-auth from an authorization-code one.
        Map<String, Object> additionalParameters = new HashMap<>();
        additionalParameters.put(OAuth2ParameterNames.GRANT_TYPE, grantType);

        return new OAuth2ClientAuthenticationToken(
                clientId, ClientAuthenticationMethod.NONE, null, additionalParameters);
    }
}
