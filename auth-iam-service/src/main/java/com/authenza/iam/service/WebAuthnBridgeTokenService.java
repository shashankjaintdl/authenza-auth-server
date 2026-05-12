package com.authenza.iam.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and validates short-lived HMAC-SHA256 signed bridge tokens.
 *
 * <p>A <em>bridge token</em> is a compact, single-use string that proves to
 * {@code auth-server-core} that a specific user has successfully completed a
 * FIDO2/WebAuthn assertion in {@code auth-iam-service}. It allows the auth
 * server to establish a Spring Security session without the user needing to
 * re-enter a password, completing the passwordless OIDC flow.</p>
 *
 * <h2>Token Format</h2>
 * <pre>
 *   base64url( userId ":" tenantId ":" expiryEpochMs ) + "." + base64url( HMAC-SHA256(payload) )
 * </pre>
 *
 * <h2>Security Properties</h2>
 * <ul>
 *   <li><b>60-second TTL</b> — Tokens expire after 60 seconds; cannot be replayed after that.</li>
 *   <li><b>HMAC-SHA256 signature</b> — The shared secret is never transmitted; only the
 *       signature is. An attacker who intercepts the token cannot forge a different
 *       {@code userId} or {@code tenantId} without knowing the secret.</li>
 *   <li><b>Single-use (recommended)</b> — {@code auth-server-core} should record and reject
 *       previously seen tokens (Redis TTL set = consumed). This prevents replay in the
 *       short 60-second window. Add Redis tracking in Phase 3 if needed.</li>
 * </ul>
 */
@Component
public class WebAuthnBridgeTokenService {

    private static final long TOKEN_TTL_MS = 60_000; // 60 seconds

    private final byte[] secretKeyBytes;

    public WebAuthnBridgeTokenService(@Value("${app.webauthn.bridge-secret}") String bridgeSecret) {
        // The secret is stored as a hex string in YAML for readability.
        this.secretKeyBytes = HexFormat.of().parseHex(bridgeSecret);
    }

    /**
     * Generates a signed bridge token for the given user and tenant.
     *
     * @param userId   the authenticated user's database ID
     * @param tenantId the tenant the user belongs to
     * @return a compact, URL-safe signed token string
     */
    public String generateToken(Long userId, String tenantId) {
        long expiry = System.currentTimeMillis() + TOKEN_TTL_MS;
        String payload = userId + ":" + tenantId + ":" + expiry;
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(payload);
        return payloadB64 + "." + signature;
    }

    /**
     * Validates a bridge token and extracts its claims.
     *
     * @param token the token string from the login page JavaScript
     * @return a {@link BridgeClaims} record with userId and tenantId
     * @throws IllegalArgumentException if the token is malformed, expired, or the signature is invalid
     */
    public BridgeClaims validateToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed bridge token.");
        }

        String payloadB64 = parts[0];
        String receivedSig = parts[1];

        String payload = new String(
                Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);

        String[] fields = payload.split(":");
        if (fields.length != 3) {
            throw new IllegalArgumentException("Malformed bridge token payload.");
        }

        long expiry = Long.parseLong(fields[2]);
        if (System.currentTimeMillis() > expiry) {
            throw new IllegalArgumentException("Bridge token has expired. Please try signing in again.");
        }

        // Verify signature — constant-time comparison to prevent timing attacks
        String expectedSig = sign(payload);
        if (!constantTimeEquals(expectedSig, receivedSig)) {
            throw new IllegalArgumentException("Bridge token signature is invalid.");
        }

        long userId = Long.parseLong(fields[0]);
        String tenantId = fields[1];
        return new BridgeClaims(userId, tenantId);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKeyBytes, "HmacSHA256"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC signature.", e);
        }
    }

    /** Constant-time string comparison to prevent timing-based signature forgery. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * Decoded, validated claims extracted from a bridge token.
     *
     * @param userId   the authenticated user's database ID
     * @param tenantId the tenant the user belongs to
     */
    public record BridgeClaims(Long userId, String tenantId) {}
}
