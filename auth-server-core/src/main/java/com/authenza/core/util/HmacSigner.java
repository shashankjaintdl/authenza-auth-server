package com.authenza.core.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Reusable HMAC-SHA256 helper for signed cookies / fingerprints.
 * Shared by adaptive trusted-device crypto (and usable by similar token services).
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public HmacSigner(String hexSecret) {
        Objects.requireNonNull(hexSecret, "hexSecret");
        this.key = HexFormat.of().parseHex(hexSecret);
    }

    public HmacSigner(byte[] keyBytes) {
        this.key = Objects.requireNonNull(keyBytes, "keyBytes").clone();
    }

    public byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    public String hmacHex(String material) {
        return HexFormat.of().formatHex(hmac(material.getBytes(StandardCharsets.UTF_8)));
    }

    public String hmacBase64Url(String material) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(material.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < left.length; i++) {
            result |= left[i] ^ right[i];
        }
        return result == 0;
    }
}
