package com.authenza.iam.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;

/**
 * Redis-backed store for pending WebAuthn challenges (both registration and authentication).
 *
 * <p>Replaces the local {@code ConcurrentHashMap} in {@link WebAuthnService} so that
 * multi-node deployments can correctly verify challenges even when the
 * {@code /start} and {@code /finish} requests land on different pods.
 *
 * <p>Key schema:
 * <ul>
 *   <li>Registration: {@code webauthn:challenge:register:<requestId>}</li>
 *   <li>Authentication: {@code webauthn:challenge:auth:<requestId>}</li>
 * </ul>
 *
 * <p>Both keys are written with a 5-minute TTL and are deleted atomically
 * on first read (compare-and-delete is not needed because a UUID requestId
 * is unguessable and single-use).
 */
@Service
public class RedisWebAuthnChallengeStore {

    private static final Logger log = LoggerFactory.getLogger(RedisWebAuthnChallengeStore.class);

    private static final String PREFIX_REGISTER = "webauthn:challenge:register:";
    private static final String PREFIX_AUTH      = "webauthn:challenge:auth:";
    private static final Duration CHALLENGE_TTL  = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisWebAuthnChallengeStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Registration
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Persists a registration challenge options object in Redis.
     *
     * @param requestId      the unique handle the browser will echo back in /finish
     * @param creationOptions the FIDO2 options containing the challenge bytes
     */
    public void saveRegistrationChallenge(String requestId, PublicKeyCredentialCreationOptions creationOptions) {
        try {
            String json = creationOptions.toJson();
            redis.opsForValue().set(PREFIX_REGISTER + requestId, json, CHALLENGE_TTL);
            log.debug("[WebAuthnChallengeStore] Saved registration challenge for requestId={}", requestId);
        } catch (Exception e) {
            log.error("[WebAuthnChallengeStore] Failed to save registration challenge for requestId={}: {}", requestId, e.getMessage());
            throw new RuntimeException("Failed to store WebAuthn registration challenge.", e);
        }
    }

    /**
     * Retrieves and atomically removes the registration challenge from Redis.
     * Returns {@code null} if expired or not found.
     *
     * @param requestId the handle from the browser's /finish request
     * @return the deserialized creation options, or {@code null} if not found/expired
     */
    public PublicKeyCredentialCreationOptions getAndRemoveRegistrationChallenge(String requestId) {
        String key = PREFIX_REGISTER + requestId;
        String json = redis.opsForValue().getAndDelete(key);
        if (json == null) {
            log.warn("[WebAuthnChallengeStore] Registration challenge not found or expired for requestId={}", requestId);
            return null;
        }
        try {
            return PublicKeyCredentialCreationOptions.fromJson(json);
        } catch (IOException e) {
            log.error("[WebAuthnChallengeStore] Failed to deserialize registration challenge for requestId={}: {}", requestId, e.getMessage());
            throw new RuntimeException("Failed to deserialize WebAuthn registration challenge.", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Authentication
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Persists an authentication assertion request in Redis.
     *
     * @param requestId       the unique handle the browser will echo back in /finish
     * @param assertionRequest the FIDO2 assertion request containing the challenge bytes
     */
    public void saveAuthenticationChallenge(String requestId, AssertionRequest assertionRequest) {
        try {
            String json = assertionRequest.toJson();
            redis.opsForValue().set(PREFIX_AUTH + requestId, json, CHALLENGE_TTL);
            log.debug("[WebAuthnChallengeStore] Saved authentication challenge for requestId={}", requestId);
        } catch (Exception e) {
            log.error("[WebAuthnChallengeStore] Failed to save authentication challenge for requestId={}: {}", requestId, e.getMessage());
            throw new RuntimeException("Failed to store WebAuthn authentication challenge.", e);
        }
    }

    /**
     * Retrieves and atomically removes the authentication assertion request from Redis.
     * Returns {@code null} if expired or not found.
     *
     * @param requestId the handle from the browser's /finish request
     * @return the deserialized assertion request, or {@code null} if not found/expired
     */
    public AssertionRequest getAndRemoveAuthenticationChallenge(String requestId) {
        String key = PREFIX_AUTH + requestId;
        String json = redis.opsForValue().getAndDelete(key);
        if (json == null) {
            log.warn("[WebAuthnChallengeStore] Authentication challenge not found or expired for requestId={}", requestId);
            return null;
        }
        try {
            return AssertionRequest.fromJson(json);
        } catch (IOException e) {
            log.error("[WebAuthnChallengeStore] Failed to deserialize authentication challenge for requestId={}: {}", requestId, e.getMessage());
            throw new RuntimeException("Failed to deserialize WebAuthn authentication challenge.", e);
        }
    }
}
