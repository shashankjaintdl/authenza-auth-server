package com.authenza.iam.service;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.enums.UserStatus;
import com.authenza.common.exception.ResourceNotFoundException;
import com.authenza.common.model.iam.User;
import com.authenza.iam.dto.MfaSetupResponse;
import com.authenza.iam.repository.UserRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import static dev.samstevens.totp.util.Utils.getDataUriForImage;

/**
 * MFA enrollment, confirmation, and disablement for the IAM service.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #setupMfa} — generates a fresh TOTP secret, encrypts and stores it
 *       ({@code mfa_enabled=false}), returns the QR code + raw secret for the UI.</li>
 *   <li>{@link #confirmMfa} — verifies the first code entered by the user; if valid,
 *       sets {@code mfa_enabled=true}.</li>
 *   <li>{@link #disableMfa} — verifies a code then clears both {@code mfa_secret}
 *       and sets {@code mfa_enabled=false}.</li>
 * </ol>
 *
 * <p>The secret is encrypted at rest using AES-256-GCM with the key from
 * {@code app.mfa.encryption-key} (must match the key used by auth-server-core).
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final HashingAlgorithm ALGORITHM = HashingAlgorithm.SHA1;
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;

    private final UserRepository userRepository;
    private final SecretKey aesKey;

    public MfaService(UserRepository userRepository,
                      @Value("${app.mfa.encryption-key}") String base64Key) {
        this.userRepository = userRepository;
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "app.mfa.encryption-key must decode to exactly 32 bytes (256-bit AES).");
        }
        this.aesKey = new SecretKeySpec(keyBytes, "AES");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates MFA setup for the given user.
     *
     * <p>Generates a new TOTP secret, stores it encrypted in the DB
     * (but leaves {@code mfa_enabled=false} until the user confirms).
     * Returns the QR code data URI and the raw secret for the user to scan.
     *
     * @param userId the user's database ID
     * @return {@link MfaSetupResponse} containing the QR code data URI and raw secret
     */
    @Transactional
    public MfaSetupResponse setupMfa(Long userId) {
        User user = findOrThrow(userId);

        // Block re-enrollment only if MFA is already fully active (enabled + has a secret).
        // Allow setup when mfa_enabled=true but mfa_secret=null — this is the "admin forced
        // MFA before the user enrolled" scenario, and the user must be allowed through.
        boolean alreadyFullyEnrolled = Boolean.TRUE.equals(user.getMfaEnabled())
                && user.getMfaSecret() != null;
        if (alreadyFullyEnrolled) {
            throw new IllegalStateException("MFA is already active on this account. " +
                    "Disable it first (/mfa/disable) before re-enrolling.");
        }

        String rawSecret = new DefaultSecretGenerator().generate();
        String encryptedSecret = encryptSecret(rawSecret);

        user.setMfaSecret(encryptedSecret);
        // Always reset to false until the user proves they can generate a valid code.
        // This handles the case where an admin set mfa_enabled=true prematurely.
        user.setMfaEnabled(false);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        String tenantId = TenantContextHolder.getTenantId();
        String qrCodeUri = generateQrCodeDataUri(rawSecret, user.getPreferredUsername(), tenantId);

        log.info("[MFA] Setup initiated for user {} in tenant '{}'", userId, tenantId);
        return new MfaSetupResponse(qrCodeUri, rawSecret);
    }

    /**
     * Confirms MFA enrollment by verifying the first code entered by the user.
     * On success, sets {@code mfa_enabled=true}.
     *
     * @param userId the user's database ID
     * @param code   the 6-digit TOTP code from the authenticator app
     * @throws IllegalArgumentException if the code is invalid or MFA was not yet set up
     */
    @Transactional
    public void confirmMfa(Long userId, String code) {
        User user = findOrThrow(userId);

        if (user.getMfaSecret() == null) {
            throw new IllegalArgumentException(
                    "MFA setup has not been initiated. Call /mfa/setup first.");
        }

        // Allow confirmation even if mfa_enabled is already true
        // (e.g. re-confirming after an admin reset, or confirming a freshly generated secret)
        String rawSecret = decryptSecret(user.getMfaSecret());
        if (!verifyCode(rawSecret, code)) {
            throw new IllegalArgumentException(
                    "Invalid verification code. Please ensure your authenticator app is in sync.");
        }

        user.setMfaEnabled(true);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        log.info("[MFA] Confirmed and enabled for user {} in tenant '{}'",
                userId, TenantContextHolder.getTenantId());
    }

    /**
     * Disables MFA for the given user after verifying their current TOTP code.
     * Clears both the secret and the enabled flag.
     *
     * @param userId the user's database ID
     * @param code   a valid 6-digit TOTP code proving possession of the device
     * @throws IllegalArgumentException if MFA is not active or the code is invalid
     */
    @Transactional
    public void disableMfa(Long userId, String code) {
        User user = findOrThrow(userId);

        if (!Boolean.TRUE.equals(user.getMfaEnabled())) {
            throw new IllegalStateException("MFA is not currently enabled for this account.");
        }

        String rawSecret = decryptSecret(user.getMfaSecret());
        if (!verifyCode(rawSecret, code)) {
            throw new IllegalArgumentException(
                    "Invalid verification code. MFA was not disabled.");
        }

        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        log.info("[MFA] Disabled for user {} in tenant '{}'",
                userId, TenantContextHolder.getTenantId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private User findOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    private boolean verifyCode(String rawSecret, String code) {
        try {
            CodeGenerator codeGenerator = new DefaultCodeGenerator(ALGORITHM, DIGITS);
            DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, new SystemTimeProvider());
            verifier.setAllowedTimePeriodDiscrepancy(1); // ±1 window = ±30 s
            return verifier.isValidCode(rawSecret, code);
        } catch (Exception e) {
            log.error("[MFA] Code verification error: {}", e.getMessage());
            return false;
        }
    }

    private String generateQrCodeDataUri(String rawSecret, String username, String tenantId) {
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
            throw new IllegalStateException("QR code generation failed", e);
        }
    }

    private String encryptSecret(String plainSecret) {
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

    private String decryptSecret(String encryptedSecret) {
        try {
            String[] parts = encryptedSecret.split(":", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt MFA secret", e);
        }
    }
}
