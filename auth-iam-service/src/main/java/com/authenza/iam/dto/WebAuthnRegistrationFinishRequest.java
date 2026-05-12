package com.authenza.iam.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO from the browser after the user's authenticator has created a credential.
 *
 * <p>After {@code navigator.credentials.create()} resolves, the browser sends the
 * resulting {@code PublicKeyCredential} JSON plus the {@code requestId} returned
 * by {@code /webauthn/register/start} so the server can validate the response
 * against the original challenge.</p>
 *
 * @param requestId                The server-assigned ID for this registration session
 *                                 (echoed from the start response).
 * @param credentialJson           The full JSON of the {@code PublicKeyCredential} object
 *                                 returned by the browser's WebAuthn API.
 * @param displayName              Optional human-readable label the user assigns to this
 *                                 key (e.g., "My MacBook Touch ID", "YubiKey Blue").
 */
public record WebAuthnRegistrationFinishRequest(
        @NotBlank String requestId,
        @NotBlank String credentialJson,
        String displayName
) {}
