package com.authenza.core.risk;

import com.authenza.core.util.ClientRequestUtils;
import com.authenza.core.util.HmacSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Server-side device fingerprint via HMAC (no client canvas fingerprinting).
 * Binding includes tenant pepper so fingerprints are not portable across tenants.
 */
@Service
public class DeviceFingerprintService {

    private final HmacSigner signer;

    public DeviceFingerprintService(
            @Value("${app.adaptive.device-hmac-secret:${app.oauth-tx.hmac-secret}}") String hmacSecret) {
        this.signer = new HmacSigner(hmacSecret);
    }

    public String fingerprint(String tenantId, Long userId, String userAgent, String secChUa) {
        String material = safe(tenantId) + "|" + userId + "|"
                + ClientRequestUtils.normalizeUserAgent(userAgent) + "|"
                + safe(secChUa);
        return signer.hmacHex(material);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
