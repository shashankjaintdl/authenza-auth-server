package com.authenza.iam.dto;

/**
 * Response returned by the MFA setup endpoint containing the QR code
 * and raw secret for the user to scan into their authenticator app.
 */
public class MfaSetupResponse {

    /**
     * A {@code data:image/png;base64,...} URI that can be placed directly
     * in an {@code <img>} tag to display the QR code for scanning.
     */
    private final String qrCodeUri;

    /**
     * The raw base32 TOTP secret — displayed as a fallback for users
     * who cannot scan the QR code (e.g. manual entry in authenticator apps).
     * Treat this as a secret: do not log it.
     */
    private final String secret;

    public MfaSetupResponse(String qrCodeUri, String secret) {
        this.qrCodeUri = qrCodeUri;
        this.secret = secret;
    }

    public String getQrCodeUri() {
        return qrCodeUri;
    }

    public String getSecret() {
        return secret;
    }
}
