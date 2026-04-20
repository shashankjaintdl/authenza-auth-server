package com.authenza.core.security;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static dev.samstevens.totp.util.Utils.getDataUriForImage;

/**
 * Service for all TOTP (Time-Based One-Time Password) operations.
 *
 * <p>Features:
 * <ul>
 *   <li>RFC 6238 compliant — compatible with Google Authenticator, Authy, etc.</li>
 *   <li>Secret is AES-256-GCM encrypted before storage; only the encrypted form
 *       ever persists in the database.</li>
 *   <li>QR code generation returns a {@code data:image/png;base64,...} URI
 *       consumable directly by an {@code <img>} tag.</li>
 *   <li>Verification uses a ±1 time-window tolerance (30 seconds each side)
 *       to compensate for clock skew.</li>
 * </ul>
 */
@Service
public class TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpService.class);

    // AES-GCM constants
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    // TOTP settings (matches Google Authenticator defaults)
    private static final HashingAlgorithm ALGORITHM = HashingAlgorithm.SHA1;
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;

    private final SecretKey aesKey;

    public TotpService(@Value("${app.mfa.encryption-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "app.mfa.encryption-key must decode to exactly 32 bytes (256-bit AES key). " +
                    "Generate one with: openssl rand -base64 32");
        }
        this.aesKey = new SecretKeySpec(keyBytes, "AES");
    }

    // ─────────────────────────────────────────────
    // Secret management
    // ─────────────────────────────────────────────

    /**
     * Generates a fresh, cryptographically secure TOTP secret (base32, 32 chars).
     */
    public String generateSecret() {
        return new DefaultSecretGenerator().generate();
    }

    /**
     * Encrypts the raw base32 TOTP secret for database storage using AES-256-GCM.
     * The output is {@code base64(iv) + ":" + base64(ciphertext)}.
     */
    public String encryptSecret(String plainSecret) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plainSecret.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt MFA secret", e);
        }
    }

    /**
     * Decrypts a stored MFA secret back to the raw base32 TOTP string.
     */
    public String decryptSecret(String encryptedSecret) {
        try {
            String[] parts = encryptedSecret.split(":", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt MFA secret", e);
        }
    }

    // ─────────────────────────────────────────────
    // QR Code
    // ─────────────────────────────────────────────

    /**
     * Generates a QR code data URI (PNG, base64-encoded) for the given raw TOTP secret.
     * The issuer label uses the format {@code Authenza ({tenantId})} so users can
     * identify the account in their authenticator app.
     *
     * @param rawSecret  the plain (NOT encrypted) base32 TOTP secret
     * @param username   the account username shown in the authenticator
     * @param tenantId   the tenant name used as the OTP issuer label
     * @return           a {@code data:image/png;base64,...} URI
     */
    public String generateQrCodeDataUri(String rawSecret, String username, String tenantId) {
        QrData qrData = new QrData.Builder()
                .label(username)
                .secret(rawSecret)
                .issuer("Authenza (" + tenantId + ")")
                .algorithm(ALGORITHM)
                .digits(DIGITS)
                .period(PERIOD_SECONDS)
                .build();

        QrGenerator generator = new ZxingPngQrGenerator();
        try {
            byte[] imageBytes = generator.generate(qrData);
            return getDataUriForImage(imageBytes, generator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new IllegalStateException("Failed to generate MFA QR code", e);
        }
    }

    // ─────────────────────────────────────────────
    // Code verification
    // ─────────────────────────────────────────────

    /**
     * Verifies a 6-digit TOTP code against the provided raw (plain) secret.
     * Allows ±1 time-step tolerance (±30 s) for clock skew.
     *
     * @param rawSecret  the plain base32 TOTP secret (NOT the encrypted form)
     * @param code       the 6-digit code entered by the user
     * @return           {@code true} if the code is valid within the time window
     */
    public boolean verifyCode(String rawSecret, String code) {
        if (rawSecret == null || code == null || code.length() != DIGITS) {
            return false;
        }
        try {
            TimeProvider timeProvider = new SystemTimeProvider();
            CodeGenerator codeGenerator = new DefaultCodeGenerator(ALGORITHM, DIGITS);
            CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
            // timePeriodDiscrepancy = 1 means ±1 time window (±30 s) tolerance
            ((DefaultCodeVerifier) verifier).setAllowedTimePeriodDiscrepancy(1);
            return verifier.isValidCode(rawSecret, code);
        } catch (Exception e) {
            log.error("[TOTP] Code verification error: {}", e.getMessage());
            return false;
        }
    }
}
