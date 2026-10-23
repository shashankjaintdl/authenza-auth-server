package com.authenza.core.risk;

import com.authenza.core.util.HmacSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * Signs and verifies the HttpOnly {@code az_trusted_device} cookie.
 * Token format mirrors {@link com.authenza.core.security.OAuthTransactionTokenService}.
 */
@Service
public class TrustedDeviceCookieService {

    private static final Logger log = LoggerFactory.getLogger(TrustedDeviceCookieService.class);

    public static final String COOKIE_NAME = "az_trusted_device";

    private final ObjectMapper objectMapper;
    private final HmacSigner signer;

    public TrustedDeviceCookieService(
            ObjectMapper objectMapper,
            @Value("${app.adaptive.device-hmac-secret:${app.oauth-tx.hmac-secret}}") String hmacSecret) {
        this.objectMapper = objectMapper;
        this.signer = new HmacSigner(hmacSecret);
    }

    public void writeCookie(HttpServletResponse response, String tenantId, Long userId,
                            String fingerprint, int trustDays) {
        Instant now = Instant.now();
        TrustedDeviceToken payload = new TrustedDeviceToken(
                tenantId, userId, fingerprint, now, now.plus(Math.max(1, trustDays), ChronoUnit.DAYS));
        String token = sign(payload);
        int maxAge = (int) ChronoUnit.SECONDS.between(now, payload.expiresAt());
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=" + token
                        + "; Path=/; Max-Age=" + maxAge
                        + "; HttpOnly; Secure; SameSite=Lax");
    }

    public Optional<TrustedDeviceToken> readValidCookie(HttpServletRequest request,
                                                        String expectedTenantId,
                                                        Long expectedUserId,
                                                        String expectedFingerprint) {
        String raw = readCookieValue(request);
        if (raw == null) {
            return Optional.empty();
        }
        return verify(raw, expectedTenantId, expectedUserId, expectedFingerprint);
    }

    public void clearCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax");
    }

    private Optional<TrustedDeviceToken> verify(String token, String tenantId, Long userId, String fingerprint) {
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2) {
                return Optional.empty();
            }
            if (!HmacSigner.constantTimeEquals(parts[1], signer.hmacBase64Url(parts[0]))) {
                return Optional.empty();
            }
            byte[] json = Base64.getUrlDecoder().decode(parts[0]);
            TrustedDeviceToken payload = objectMapper.readValue(json, TrustedDeviceToken.class);
            if (payload.expiresAt() == null || Instant.now().isAfter(payload.expiresAt())) {
                return Optional.empty();
            }
            if (!tenantId.equals(payload.tenantId())
                    || !userId.equals(payload.userId())
                    || !fingerprint.equals(payload.fingerprint())) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (Exception e) {
            log.debug("[TrustedDevice] Cookie verify failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String sign(TrustedDeviceToken payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            return payloadB64 + "." + signer.hmacBase64Url(payloadB64);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign trusted-device cookie", e);
        }
    }

    private static String readCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public record TrustedDeviceToken(
            String tenantId,
            Long userId,
            String fingerprint,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}
