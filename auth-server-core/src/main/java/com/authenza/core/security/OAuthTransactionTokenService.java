package com.authenza.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Creates and verifies compact HMAC-SHA256–signed tokens that carry the OAuth2
 * {@code /authorize} request context across HTTP session boundaries (the {@code tx} parameter).
 *
 * <h3>Token format</h3>
 * <pre>
 *   Base64url(JSON payload) + "." + Base64url(HMAC-SHA256 of payload)
 * </pre>
 *
 * <h3>Security properties</h3>
 * <ul>
 *   <li><b>Tamper detection:</b> any field modification invalidates the HMAC.</li>
 *   <li><b>Replay prevention:</b> {@code expiresAt} is enforced (24-hour TTL by default).</li>
 *   <li><b>Tenant binding:</b> {@link #verifyContext} asserts {@code token.tenantId == expectedTenantId}.</li>
 *   <li><b>Open-redirect guard:</b> {@code redirect_uri} lives inside the signed payload; the caller
 *       MUST verify it against the registered client via Spring Authorization Server before
 *       redirecting — which the "Replay-on-Authorize" pattern handles automatically because Spring
 *       Authorization Server validates all parameters when it receives the reconstructed
 *       {@code /oauth2/authorize} request.</li>
 * </ul>
 *
 * <p>This service deliberately uses only JDK primitives ({@link Mac}) and Jackson's
 * {@link ObjectMapper} (already present via Spring Boot) — no extra library dependency is required.
 */
@Service
public class OAuthTransactionTokenService {

    private static final Logger log = LoggerFactory.getLogger(OAuthTransactionTokenService.class);

    /** Default token lifetime — long enough to survive overnight idle but short enough to limit exposure. */
    private static final long TOKEN_TTL_HOURS = 24;

    private final ObjectMapper objectMapper;
    private final byte[] hmacKey;

    public OAuthTransactionTokenService(
            ObjectMapper objectMapper,
            @Value("${app.oauth-tx.hmac-secret}") String hmacSecret) {
        this.objectMapper = objectMapper;
        // Accept the secret as a hex string — consistent with app.webauthn.bridge-secret
        this.hmacKey = HexFormat.of().parseHex(hmacSecret);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Extracts OAuth2 parameters from the incoming {@code /oauth2/authorize} request,
     * encodes them into a signed compact token, and returns it.
     *
     * <p>This is called by {@link TenantAwareAuthenticationEntryPoint} when redirecting
     * an unauthenticated user to the login page so the original authorize parameters
     * survive the redirect.
     *
     * @param request  the original {@code /oauth2/authorize} {@link HttpServletRequest}
     * @param tenantId the active tenant (from the path variable or {@code TenantContextHolder})
     * @return a compact {@code Base64url(JSON).Base64url(HMAC)} token string
     * @throws IllegalStateException if HMAC computation fails (should never happen)
     */
    public String signContext(HttpServletRequest request, String tenantId) {
        try {
            Instant now = Instant.now();
            OAuthTransactionContext ctx = new OAuthTransactionContext(
                    tenantId,
                    request.getParameter("client_id"),
                    request.getParameter("redirect_uri"),
                    request.getParameter("scope"),
                    request.getParameter("state"),
                    request.getParameter("response_type"),
                    request.getParameter("code_challenge"),
                    request.getParameter("code_challenge_method"),
                    request.getParameter("nonce"),
                    now,
                    now.plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS)
            );

            String payloadJson = objectMapper.writeValueAsString(ctx);
            String payloadB64  = Base64.getUrlEncoder().withoutPadding()
                                       .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signature   = hmacSign(payloadB64);

            log.debug("[OAuthTx] Signed context for tenant={}, client={}", tenantId, ctx.clientId());
            return payloadB64 + "." + signature;
        } catch (Exception e) {
            log.error("[OAuthTx] Failed to sign transaction context: {}", e.getMessage());
            throw new IllegalStateException("Cannot sign OAuth transaction context.", e);
        }
    }

    /**
     * Verifies the token's HMAC signature, expiry, and tenant binding.
     *
     * <p>Returns an empty {@link Optional} (never throws) for any invalid condition
     * so callers can safely fall through to the next priority (tenant root fallback).
     *
     * @param token            the compact {@code tx} token received from the login form
     * @param expectedTenantId the tenant derived from the request path ({@code /{tenantId}/login})
     * @return the verified {@link OAuthTransactionContext}, or {@link Optional#empty()} if
     *         the token is invalid, expired, tampered with, or bound to a different tenant
     */
    public Optional<OAuthTransactionContext> verifyContext(String token, String expectedTenantId) {
        if (token == null || token.isBlank()) return Optional.empty();

        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                log.warn("[OAuthTx] Malformed token (expected 2 parts, got {})", parts.length);
                return Optional.empty();
            }

            String payloadB64  = parts[0];
            String receivedSig = parts[1];

            // 1. Constant-time HMAC check — prevents timing-oracle attacks
            String expectedSig = hmacSign(payloadB64);
            if (!constantTimeEquals(expectedSig, receivedSig)) {
                log.warn("[OAuthTx] HMAC signature mismatch — token may have been tampered with.");
                return Optional.empty();
            }

            // 2. Decode and parse the JSON payload
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
            OAuthTransactionContext ctx = objectMapper.readValue(payloadJson, OAuthTransactionContext.class);

            // 3. Expiry check
            if (Instant.now().isAfter(ctx.expiresAt())) {
                log.warn("[OAuthTx] Token expired for tenant={}", ctx.tenantId());
                return Optional.empty();
            }

            // 4. Tenant binding assertion — prevents cross-tenant token reuse
            if (!expectedTenantId.equals(ctx.tenantId())) {
                log.warn("[OAuthTx] Tenant mismatch: token={}, expected={}", ctx.tenantId(), expectedTenantId);
                return Optional.empty();
            }

            log.debug("[OAuthTx] Verified context for tenant={}, client={}", ctx.tenantId(), ctx.clientId());
            return Optional.of(ctx);

        } catch (Exception e) {
            log.warn("[OAuthTx] Token verification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reconstructs the full {@code /{tenantId}/oauth2/authorize?...} URL from a verified
     * {@link OAuthTransactionContext}.
     *
     * <p>After redirecting the browser to this URL with an active Spring Security session,
     * Spring Authorization Server receives the request, sees the user is authenticated,
     * validates the client and PKCE parameters, and immediately issues an authorization code
     * to the {@code redirect_uri} — the "Replay-on-Authorize" pattern.
     *
     * @param ctx the verified (non-null) transaction context
     * @return the reconstructed {@code /oauth2/authorize} URL string
     */
    public String buildAuthorizeUrl(OAuthTransactionContext ctx) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromPath("/" + ctx.tenantId() + "/oauth2/authorize")
                .queryParam("response_type", ctx.responseType() != null ? ctx.responseType() : "code")
                .queryParam("client_id",     ctx.clientId())
                .queryParam("redirect_uri",  ctx.redirectUri())
                .queryParam("scope",         ctx.scope());

        if (ctx.state()               != null) builder.queryParam("state",                 ctx.state());
        if (ctx.codeChallenge()       != null) builder.queryParam("code_challenge",        ctx.codeChallenge());
        if (ctx.codeChallengeMethod() != null) builder.queryParam("code_challenge_method", ctx.codeChallengeMethod());
        if (ctx.nonce()               != null) builder.queryParam("nonce",                ctx.nonce());

        return builder.build().toUriString();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private String hmacSign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed.", e);
        }
    }

    /** Constant-time string comparison — prevents timing-oracle attacks on HMAC values. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
