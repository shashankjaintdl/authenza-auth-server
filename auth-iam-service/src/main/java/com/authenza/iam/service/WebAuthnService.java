package com.authenza.iam.service;

import com.authenza.common.exception.ResourceNotFoundException;
import com.authenza.common.model.iam.User;
import com.authenza.iam.dto.*;
import com.authenza.iam.model.WebAuthnCredential;
import com.authenza.iam.repository.UserRepository;
import com.authenza.iam.repository.WebAuthnCredentialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Core service for FIDO2 / WebAuthn passwordless authentication.
 *
 * <p><b>Registration Flow (linking a new passkey to a user account):</b></p>
 * <ol>
 *   <li>Authenticated user calls {@code POST /{userId}/passkeys/register/start}.</li>
 *   <li>{@link #startRegistration} generates a challenge + options JSON; stores the pending
 *       request in a short-lived in-memory map (keyed by {@code requestId}).</li>
 *   <li>Browser calls {@code navigator.credentials.create(options)} → Touch ID / Face ID /
 *       Windows Hello dialog appears → returns a signed credential to the JavaScript.</li>
 *   <li>JS POSTs credential JSON + requestId to {@code POST /{userId}/passkeys/register/finish}.</li>
 *   <li>{@link #finishRegistration} verifies the signature, persists the public key to the
 *       {@code webauthn_credential} table.</li>
 * </ol>
 *
 * <p><b>Authentication Flow (logging in with a passkey):</b></p>
 * <ol>
 *   <li>Unauthenticated user calls {@code POST /webauthn/authenticate/start} with their username.</li>
 *   <li>{@link #startAuthentication} generates a challenge; sends allowed credential IDs
 *       back so the browser knows which keys to prompt.</li>
 *   <li>Browser calls {@code navigator.credentials.get()} → biometric dialog → signed assertion.</li>
 *   <li>JS POSTs assertion JSON + requestId to {@code POST /webauthn/authenticate/finish}.</li>
 *   <li>{@link #finishAuthentication} verifies the assertion, increments the sign-count
 *       (replay-attack prevention), and returns the authenticated userId.</li>
 * </ol>
 *
 * <p><b>In-Memory Challenge Cache:</b> Pending registration/authentication requests are stored
 * in a {@link ConcurrentHashMap} with a 5-minute TTL. In a production cluster, replace this
 * with Redis to share challenge state across instances.</p>
 */
@Service
public class WebAuthnService {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnService.class);

    /**
     * TTL for pending WebAuthn challenges (both registration and authentication).
     * Challenges not completed within this window are automatically discarded.
     */
    private static final long CHALLENGE_TTL_MS = 5 * 60 * 1_000;

    private final RelyingParty relyingParty;
    private final UserRepository userRepository;
    private final WebAuthnCredentialRepository credentialRepository;
    private final ObjectMapper objectMapper;
    private final WebAuthnBridgeTokenService bridgeTokenService;

    /**
     * Pending challenges keyed by a random requestId UUID.
     * Value is a pair of (challenge-data, creation-timestamp).
     */
    private final ConcurrentHashMap<String, PendingRequest<?>> pendingRequests = new ConcurrentHashMap<>();

    public WebAuthnService(
            UserRepository userRepository,
            WebAuthnCredentialRepository credentialRepository,
            ObjectMapper objectMapper,
            WebAuthnBridgeTokenService bridgeTokenService,
            @Value("${app.webauthn.rp-id}") String rpId,
            @Value("${app.webauthn.rp-name}") String rpName,
            @Value("${app.webauthn.origin}") String origin) {

        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.objectMapper = objectMapper;
        this.bridgeTokenService = bridgeTokenService;

        // RelyingParty is the server-side authority that signs and verifies WebAuthn operations.
        // rpId   = the domain name (e.g., "localhost" in dev, "auth.authenza.com" in prod).
        // origin = the exact browser origin that WebAuthn requests come from.
        this.relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id(rpId)
                        .name(rpName)
                        .build())
                .credentialRepository(new TenantAwareCredentialRepository(credentialRepository, userRepository))
                .origins(Set.of(origin, "http://localhost:8081", "http://127.0.0.1:8081"))
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // REGISTRATION
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Step 1 of registration: generates a WebAuthn challenge and credential creation options.
     * The returned options JSON is passed directly to the browser's WebAuthn API.
     *
     * @param userId the database ID of the authenticated user adding a passkey
     * @return a response containing the options JSON and a requestId
     */
    public WebAuthnRegistrationStartResponse startRegistration(Long userId, String attachment) throws Exception {
        User user = findUserOrThrow(userId);

        UserIdentity userIdentity = UserIdentity.builder()
                .name(user.getEmail())
                .displayName(user.getName() != null ? user.getName() : user.getPreferredUsername())
                // FIDO2 user handle — opaque bytes identifying the user; we use their DB id
                .id(ByteArray.fromHex(String.format("%016x", userId)))
                .build();

        AuthenticatorSelectionCriteria.AuthenticatorSelectionCriteriaBuilder authSelectionBuilder = AuthenticatorSelectionCriteria.builder()
                .userVerification(UserVerificationRequirement.REQUIRED);

        if ("platform".equalsIgnoreCase(attachment)) {
            authSelectionBuilder.authenticatorAttachment(AuthenticatorAttachment.PLATFORM);
            // Enforce Resident Key for the modern "Passkey" experience
            authSelectionBuilder.residentKey(ResidentKeyRequirement.REQUIRED);
        } else if ("cross-platform".equalsIgnoreCase(attachment)) {
            authSelectionBuilder.authenticatorAttachment(AuthenticatorAttachment.CROSS_PLATFORM);
            authSelectionBuilder.residentKey(ResidentKeyRequirement.PREFERRED);
        }

        StartRegistrationOptions startOptions = StartRegistrationOptions.builder()
                .user(userIdentity)
                // Note: excludeCredentials is NOT a builder method in webauthn-server-core 2.5.4.
                // The library automatically populates the excludeCredentials list by calling
                // TenantAwareCredentialRepository.getCredentialIdsForUsername() internally.
                .authenticatorSelection(authSelectionBuilder.build())
                .timeout(60000L)
                .build();

        PublicKeyCredentialCreationOptions creationOptions = relyingParty.startRegistration(startOptions);

        String requestId = UUID.randomUUID().toString();
        pendingRequests.put(requestId, new PendingRequest<>(creationOptions, System.currentTimeMillis()));
        evictExpiredRequests();

        String optionsJson = creationOptions.toJson();
        log.info("[WebAuthn] Registration started for userId={}, requestId={}", userId, requestId);
        return new WebAuthnRegistrationStartResponse(optionsJson, requestId);
    }

    /**
     * Step 2 of registration: verifies the browser's credential response and persists
     * the public key to the database.
     *
     * @param userId  the database ID of the authenticated user
     * @param request the credential JSON and requestId from the browser
     */
    @Transactional
    public void finishRegistration(Long userId, WebAuthnRegistrationFinishRequest request) throws Exception {
        PendingRequest<?> pending = getAndRemovePending(request.requestId());

        @SuppressWarnings("unchecked")
        PublicKeyCredentialCreationOptions options = (PublicKeyCredentialCreationOptions) pending.data();

        String credentialJson = request.credentialJson();
        if (!credentialJson.contains("\"clientExtensionResults\"")) {
            credentialJson = credentialJson.substring(0, credentialJson.length() - 1) + ",\"clientExtensionResults\":{}}";
        }

        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc =
                PublicKeyCredential.parseRegistrationResponseJson(credentialJson);

        RegistrationResult result = relyingParty.finishRegistration(
                FinishRegistrationOptions.builder()
                        .request(options)
                        .response(pkc)
                        .build());

        // Build and persist the credential record
        WebAuthnCredential credential = new WebAuthnCredential();
        credential.setUserId(userId);
        credential.setCredentialId(result.getKeyId().getId().getBase64Url());
        credential.setPublicKeyCose(result.getPublicKeyCose().getBase64Url());
        credential.setSignCount(result.getSignatureCount());
        credential.setDisplayName(request.displayName());
        // getAaguid() in webauthn-server-core 2.5.4 returns a nullable ByteArray directly,
        // not Optional<AAGUID>. Store as base64url string; null if attestation omitted it.
        ByteArray aaguidBytes = result.getAaguid();
        credential.setAaguid(aaguidBytes != null ? aaguidBytes.getBase64Url() : null);
        credential.setTransports(result.getKeyId().getTransports()
                .map(t -> t.stream().map(AuthenticatorTransport::getId).collect(Collectors.joining(",")))
                .orElse(null));
        credential.setUserVerified(result.isUserVerified());
        credential.setCreatedAt(Instant.now());

        credentialRepository.save(credential);
        log.info("[WebAuthn] Credential registered for userId={}, credentialId={}",
                userId, credential.getCredentialId());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // AUTHENTICATION
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Step 1 of authentication: generates a WebAuthn assertion challenge.
     * The browser uses the returned allowCredentials list to locate the correct passkey.
     *
     * @param username the username or email the user typed on the login screen
     * @return JSON options and requestId to pass to the browser's WebAuthn API
     */
    public WebAuthnRegistrationStartResponse startAuthentication(String username) throws Exception {
        User user = userRepository.findByEmail(username)
                .orElseGet(() -> userRepository.findByPreferredUsername(username).orElse(null));
        if (user == null) {
            throw new ResourceNotFoundException("User not found: " + username);
        }

        StartAssertionOptions assertionOptions = StartAssertionOptions.builder()
                .username(username)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build();

        AssertionRequest assertionRequest = relyingParty.startAssertion(assertionOptions);

        String requestId = UUID.randomUUID().toString();
        pendingRequests.put(requestId, new PendingRequest<>(assertionRequest, System.currentTimeMillis()));
        evictExpiredRequests();

        log.info("[WebAuthn] Authentication started for username={}, requestId={}", username, requestId);
        return new WebAuthnRegistrationStartResponse(assertionRequest.toJson(), requestId);
    }

    /**
     * Step 2 of authentication: verifies the signed assertion from the browser.
     * On success, updates the sign-count and returns a {@link WebAuthnAuthResult}
     * containing the authenticated userId, the tenantId, and a signed bridge token
     * that the login page JavaScript can POST to {@code auth-server-core} to
     * complete the OIDC session without a password.
     *
     * @param request  the credential JSON and requestId from the browser
     * @param tenantId the tenant this login attempt belongs to (from the URL path)
     * @return a {@link WebAuthnAuthResult} with userId, tenantId, and bridgeToken
     */
    @Transactional
    public WebAuthnAuthResult finishAuthentication(
            WebAuthnAuthenticationFinishRequest request, String tenantId) throws Exception {
        PendingRequest<?> pending = getAndRemovePending(request.requestId());

        @SuppressWarnings("unchecked")
        AssertionRequest assertionRequest = (AssertionRequest) pending.data();

        String credentialJson = request.credentialJson();
        if (!credentialJson.contains("\"clientExtensionResults\"")) {
            credentialJson = credentialJson.substring(0, credentialJson.length() - 1) + ",\"clientExtensionResults\":{}}";
        }

        PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc =
                PublicKeyCredential.parseAssertionResponseJson(credentialJson);

        AssertionResult result = relyingParty.finishAssertion(
                FinishAssertionOptions.builder()
                        .request(assertionRequest)
                        .response(pkc)
                        .build());

        if (!result.isSuccess()) {
            throw new AssertionFailedException("WebAuthn assertion verification failed.");
        }

        // Update sign counter — critical for detecting cloned/replayed authenticators
        String credentialId = result.getCredential().getCredentialId().getBase64Url();
        credentialRepository.findByCredentialId(credentialId).ifPresent(cred -> {
            cred.setSignCount(result.getSignatureCount());
            cred.setLastUsedAt(Instant.now());
            credentialRepository.save(cred);
        });

        // Parse userId from the FIDO2 user handle (stored as hex-encoded 8 bytes)
        Long userId = Long.parseLong(
                result.getCredential().getUserHandle().getHex(), 16);

        // Issue a short-lived (60s) HMAC-signed bridge token so auth-server-core
        // can verify this assertion result and establish the Spring Security session.
        String bridgeToken = bridgeTokenService.generateToken(userId, tenantId);

        log.info("[WebAuthn] Authentication successful for userId={}, tenantId={}, credentialId={}",
                userId, tenantId, credentialId);
        return new WebAuthnAuthResult(userId, tenantId, bridgeToken);
    }

    /**
     * Carries the result of a successful WebAuthn authentication back to the controller.
     *
     * @param userId      the authenticated user's database ID
     * @param tenantId    the tenant the user belongs to
     * @param bridgeToken a signed, 60-second token the login page POSTs to auth-server-core
     */
    public record WebAuthnAuthResult(Long userId, String tenantId, String bridgeToken) {}

    // ──────────────────────────────────────────────────────────────────────────
    // PASSKEY MANAGEMENT (for "Manage Passkeys" settings UI)
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns all passkeys registered for the given user (for the UI list). */
    public List<PasskeyResponse> listPasskeys(Long userId) {
        return credentialRepository.findAllByUserId(userId).stream()
                .map(c -> new PasskeyResponse(
                        c.getId(), c.getDisplayName(), c.getAaguid(),
                        c.getTransports(), c.getUserVerified(),
                        c.getCreatedAt(), c.getLastUsedAt()))
                .collect(Collectors.toList());
    }

    /** Removes a specific passkey credential. Users can only delete their own keys. */
    @Transactional
    public void deletePasskey(Long userId, Long credentialDbId) {
        WebAuthnCredential cred = credentialRepository.findById(credentialDbId)
                .orElseThrow(() -> new ResourceNotFoundException("Passkey not found."));
        if (!cred.getUserId().equals(userId)) {
            throw new SecurityException("Cannot delete another user's passkey.");
        }
        credentialRepository.delete(cred);
        log.info("[WebAuthn] Passkey id={} deleted by userId={}", credentialDbId, userId);
    }

    /** Updates the human-readable display name of a passkey. Ownership is enforced. */
    @Transactional
    public void renamePasskey(Long userId, Long credentialDbId, String newDisplayName) {
        WebAuthnCredential cred = credentialRepository.findById(credentialDbId)
                .orElseThrow(() -> new ResourceNotFoundException("Passkey not found."));
        if (!cred.getUserId().equals(userId)) {
            throw new SecurityException("Cannot rename another user's passkey.");
        }
        cred.setDisplayName(newDisplayName);
        credentialRepository.save(cred);
        log.info("[WebAuthn] Passkey id={} renamed to '{}' by userId={}", credentialDbId, newDisplayName, userId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    private Set<PublicKeyCredentialDescriptor> getExistingCredentials(Long userId) {
        Set<PublicKeyCredentialDescriptor> descriptors = new java.util.HashSet<>();
        for (WebAuthnCredential c : credentialRepository.findAllByUserId(userId)) {
            try {
                descriptors.add(PublicKeyCredentialDescriptor.builder()
                        .id(ByteArray.fromBase64Url(c.getCredentialId()))
                        .build());
            } catch (com.yubico.webauthn.data.exception.Base64UrlException e) {
                log.warn("[WebAuthn] Skipping unparseable credentialId for userId={}: {}", userId, e.getMessage());
            }
        }
        return descriptors;
    }

    private PendingRequest<?> getAndRemovePending(String requestId) {
        PendingRequest<?> pending = pendingRequests.remove(requestId);
        if (pending == null) {
            throw new IllegalArgumentException("WebAuthn request expired or not found. Please try again.");
        }
        if (System.currentTimeMillis() - pending.createdAt() > CHALLENGE_TTL_MS) {
            throw new IllegalArgumentException("WebAuthn challenge has expired (5-minute window). Please restart.");
        }
        return pending;
    }

    /** Removes all entries from the pending request map that have exceeded the TTL. */
    private void evictExpiredRequests() {
        long now = System.currentTimeMillis();
        pendingRequests.entrySet().removeIf(e -> (now - e.getValue().createdAt()) > CHALLENGE_TTL_MS);
    }

    /** Simple wrapper to hold challenge data alongside its creation timestamp for TTL checking. */
    private record PendingRequest<T>(T data, long createdAt) {}
}
