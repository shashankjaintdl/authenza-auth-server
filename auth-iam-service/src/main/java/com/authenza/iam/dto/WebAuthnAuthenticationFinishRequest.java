package com.authenza.iam.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO from the browser after the authenticator signs the authentication challenge.
 *
 * <p>After {@code navigator.credentials.get()} resolves, the browser sends the resulting
 * {@code PublicKeyCredential} (assertion) JSON plus the {@code requestId} from
 * {@code /webauthn/authenticate/start} for challenge verification.</p>
 *
 * @param requestId      The server-assigned ID for this authentication session.
 * @param credentialJson The full JSON of the {@code PublicKeyCredential} assertion
 *                       returned by the browser's WebAuthn API.
 */
public record WebAuthnAuthenticationFinishRequest(
        @NotBlank String requestId,
        @NotBlank String credentialJson
) {}
