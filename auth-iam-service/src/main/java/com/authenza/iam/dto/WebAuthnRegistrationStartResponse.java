package com.authenza.iam.dto;

/**
 * Response DTO returned to the browser to start a new WebAuthn credential registration.
 *
 * <p>The browser receives this JSON, calls {@code navigator.credentials.create(options)},
 * which prompts the OS biometric dialog (Touch ID, Windows Hello, etc.), and returns
 * a credential that the client POSTs back to {@code /webauthn/register/finish}.</p>
 *
 * @param credentialCreationOptions  The JSON string of a {@code PublicKeyCredentialCreationOptions}
 *                                   object, serialized by the Yubico library. Passed directly
 *                                   to the browser's WebAuthn API.
 * @param requestId                  A server-side session key used to look up the pending
 *                                   registration challenge when finish is called.
 */
public record WebAuthnRegistrationStartResponse(
        String credentialCreationOptions,
        String requestId
) {}
