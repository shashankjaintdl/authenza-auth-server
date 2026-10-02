package com.authenza.iam.service;

import com.authenza.common.exception.ResourceNotFoundException;
import com.authenza.common.model.iam.User;
import com.authenza.iam.dto.*;
import com.authenza.iam.model.GlobalWebAuthnCredential;
import com.authenza.iam.model.WebAuthnCredential;
import com.authenza.iam.repository.GlobalWebAuthnCredentialRepository;
import com.authenza.iam.repository.UserRepository;
import com.authenza.iam.repository.WebAuthnCredentialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core service for FIDO2 / WebAuthn passwordless authentication.
 *
 * <p>
 * <b>Registration Flow (linking a new passkey to a user account):</b>
 * </p>
 * <ol>
 * <li>Authenticated user calls
 * {@code POST /{userId}/passkeys/register/start}.</li>
 * <li>{@link #startRegistration} generates a challenge + options JSON; stores
 * the pending
 * request in a short-lived in-memory map (keyed by {@code requestId}).</li>
 * <li>Browser calls {@code navigator.credentials.create(options)} → Touch ID /
 * Face ID /
 * Windows Hello dialog appears → returns a signed credential to the
 * JavaScript.</li>
 * <li>JS POSTs credential JSON + requestId to
 * {@code POST /{userId}/passkeys/register/finish}.</li>
 * <li>{@link #finishRegistration} verifies the signature, persists the public
 * key to
 * <b>either</b> {@code global_webauthn_credentials} (master DB — global users)
 * or
 * {@code webauthn_credential} (tenant DB — local employees).</li>
 * </ol>
 *
 * <p>
 * <b>Authentication Flow (logging in with a passkey):</b>
 * </p>
 * <ol>
 * <li>Unauthenticated user calls {@code POST /webauthn/authenticate/start} with
 * their username.</li>
 * <li>{@link #startAuthentication} generates a challenge; sends allowed
 * credential IDs
 * back so the browser knows which keys to prompt.</li>
 * <li>Browser calls {@code navigator.credentials.get()} → biometric dialog →
 * signed assertion.</li>
 * <li>JS POSTs assertion JSON + requestId to
 * {@code POST /webauthn/authenticate/finish}.</li>
 * <li>{@link #finishAuthentication} verifies the assertion, increments the
 * sign-count
 * (replay-attack prevention) in the correct store, and returns a signed bridge
 * token.</li>
 * </ol>
 *
 * <p>
 * <b>Hybrid Credential Routing:</b>
 * The {@link HybridCredentialRepository} passed into {@link RelyingParty}
 * transparently
 * routes all Yubico library callbacks to the correct store (master DB for
 * global users,
 * tenant DB for local employees) without the library needing to know the
 * difference.
 *
 * <p>
 * <b>Challenge Store:</b> Pending registration/authentication challenges are
 * stored
 * in Redis via {@link RedisWebAuthnChallengeStore} with a 5-minute TTL.
 * This enables cross-pod challenge resolution in a horizontally-scaled cluster.
 * </p>
 */
@Service
public class WebAuthnService {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnService.class);

    private final RelyingParty relyingParty;
    private final UserRepository userRepository;
    private final WebAuthnCredentialRepository credentialRepository;
    private final GlobalWebAuthnCredentialRepository globalCredentialRepository;
    private final HybridCredentialRepository hybridCredentialRepository;
    private final ObjectMapper objectMapper;
    private final WebAuthnBridgeTokenService bridgeTokenService;
    private final TenantSettingsService tenantSettingsService;
    private final RedisWebAuthnChallengeStore challengeStore;

    public WebAuthnService(
            UserRepository userRepository,
            WebAuthnCredentialRepository credentialRepository,
            GlobalWebAuthnCredentialRepository globalCredentialRepository,
            ObjectMapper objectMapper,
            WebAuthnBridgeTokenService bridgeTokenService,
            TenantSettingsService tenantSettingsService,
            RedisWebAuthnChallengeStore challengeStore,
            @Qualifier("masterDataSource") DataSource masterDataSource,
            @Value("${app.webauthn.rp-id}") String rpId,
            @Value("${app.webauthn.rp-name}") String rpName,
            @Value("${app.webauthn.origin}") String origin) {

        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.globalCredentialRepository = globalCredentialRepository;
        this.objectMapper = objectMapper;
        this.bridgeTokenService = bridgeTokenService;
        this.tenantSettingsService = tenantSettingsService;
        this.challengeStore = challengeStore;

        // Build the hybrid routing adapter — the pivot between global and local stores.
        this.hybridCredentialRepository = new HybridCredentialRepository(
                credentialRepository,
                globalCredentialRepository,
                userRepository,
                new org.springframework.jdbc.core.JdbcTemplate(masterDataSource),
                tenantSettingsService);

        // RelyingParty is the server-side authority that signs and verifies WebAuthn
        // operations.
        // rpId = the domain name (e.g., "localhost" in dev, "auth.authenza.com" in
        // prod).
        // origin = the exact browser origin that WebAuthn requests come from.
        this.relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id(rpId)
                        .name(rpName)
                        .build())
                // Use HybridCredentialRepository instead of TenantAwareCredentialRepository
                .credentialRepository(hybridCredentialRepository)
                .origins(Set.of(origin, "http://localhost:8081", "http://127.0.0.1:8081"))
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // REGISTRATION
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Step 1 of registration: generates a WebAuthn challenge and credential
     * creation options.
     * The returned options JSON is passed directly to the browser's WebAuthn API.
     *
     * @param userId the database ID of the authenticated user adding a passkey
     * @return a response containing the options JSON and a requestId
     */
    public WebAuthnRegistrationStartResponse startRegistration(Long userId, String attachment) throws Exception {
        checkWebAuthnEnabled(userId);
        User user = findUserOrThrow(userId);

        UserIdentity userIdentity = UserIdentity.builder()
                .name(user.getEmail())
                .displayName(user.getName() != null ? user.getName() : user.getPreferredUsername())
                // FIDO2 user handle — opaque bytes identifying the user; we use their DB id
                .id(ByteArray.fromHex(String.format("%016x", userId)))
                .build();

        AuthenticatorSelectionCriteria.AuthenticatorSelectionCriteriaBuilder authSelectionBuilder = AuthenticatorSelectionCriteria
                .builder()
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
                // Note: excludeCredentials is NOT a builder method in webauthn-server-core
                // 2.5.4.
                // The library automatically populates the excludeCredentials list by calling
                // HybridCredentialRepository.getCredentialIdsForUsername() internally.
                .authenticatorSelection(authSelectionBuilder.build())
                .timeout(60000L)
                .build();

        PublicKeyCredentialCreationOptions creationOptions = relyingParty.startRegistration(startOptions);

        String requestId = UUID.randomUUID().toString();
        challengeStore.saveRegistrationChallenge(requestId, creationOptions);

        String optionsJson = creationOptions.toJson();
        log.info("[WebAuthn] Registration started for userId={}, requestId={}", userId, requestId);
        return new WebAuthnRegistrationStartResponse(optionsJson, requestId);
    }

    /**
     * Step 2 of registration: verifies the browser's credential response and
     * persists
     * the public key to the correct credential store.
     *
     * <p>
     * <b>Routing:</b>
     * <ul>
     * <li>If the user is in {@code global_accounts} → persist to
     * {@code global_webauthn_credentials} (master DB).</li>
     * <li>Otherwise → persist to {@code webauthn_credential} (active tenant DB,
     * unchanged).</li>
     * </ul>
     *
     * @param userId  the database ID of the authenticated user (local
     *                application_user.id)
     * @param request the credential JSON and requestId from the browser
     */
    @Transactional
    public void finishRegistration(Long userId, WebAuthnRegistrationFinishRequest request) throws Exception {
        checkWebAuthnEnabled(userId);
        PublicKeyCredentialCreationOptions options = challengeStore
                .getAndRemoveRegistrationChallenge(request.requestId());
        if (options == null) {
            throw new IllegalArgumentException("WebAuthn registration request expired or not found. Please try again.");
        }

        String credentialJson = request.credentialJson();
        if (!credentialJson.contains("\"clientExtensionResults\"")) {
            credentialJson = credentialJson.substring(0, credentialJson.length() - 1)
                    + ",\"clientExtensionResults\":{}}";
        }

        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc = PublicKeyCredential
                .parseRegistrationResponseJson(credentialJson);

        RegistrationResult result = relyingParty.finishRegistration(
                FinishRegistrationOptions.builder()
                        .request(options)
                        .response(pkc)
                        .build());

        // ── Routing: save to global store if user is a global account ──
        User user = findUserOrThrow(userId);
        Long globalAccountId = hybridCredentialRepository.resolveGlobalAccountId(user.getEmail());

        ByteArray aaguidBytes = result.getAaguid();
        String transports = result.getKeyId().getTransports()
                .map(t -> t.stream().map(AuthenticatorTransport::getId).collect(Collectors.joining(",")))
                .orElse(null);

        boolean useGlobalStore = globalAccountId != null && tenantSettingsService.isGlobalWebAuthnEnabled();

        if (useGlobalStore) {
            // ── Global account path: persist to master DB ──
            GlobalWebAuthnCredential globalCred = new GlobalWebAuthnCredential();
            globalCred.setAccountId(globalAccountId);
            globalCred.setCredentialId(result.getKeyId().getId().getBase64Url());
            globalCred.setPublicKeyCose(result.getPublicKeyCose().getBase64Url());
            globalCred.setSignCount(result.getSignatureCount());
            globalCred.setDisplayName(request.displayName());
            globalCred.setAaguid(aaguidBytes != null ? aaguidBytes.getBase64Url() : null);
            globalCred.setTransports(transports);
            globalCred.setUserVerified(result.isUserVerified());
            globalCred.setCreatedAt(Instant.now());
            globalCredentialRepository.save(globalCred);
            log.info("[WebAuthn] Global credential registered for accountId={}, credentialId={}",
                    globalAccountId, globalCred.getCredentialId());
        } else {
            // ── Local tenant path: persist to tenant DB (unchanged) ──
            WebAuthnCredential credential = new WebAuthnCredential();
            credential.setUserId(userId);
            credential.setCredentialId(result.getKeyId().getId().getBase64Url());
            credential.setPublicKeyCose(result.getPublicKeyCose().getBase64Url());
            credential.setSignCount(result.getSignatureCount());
            credential.setDisplayName(request.displayName());
            credential.setAaguid(aaguidBytes != null ? aaguidBytes.getBase64Url() : null);
            credential.setTransports(transports);
            credential.setUserVerified(result.isUserVerified());
            credential.setCreatedAt(Instant.now());
            credentialRepository.save(credential);
            log.info("[WebAuthn] Local credential registered for userId={}, credentialId={}",
                    userId, credential.getCredentialId());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // AUTHENTICATION
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Step 1 of authentication: generates a WebAuthn assertion challenge.
     * The browser uses the returned allowCredentials list to locate the correct
     * passkey.
     *
     * @param username the username or email the user typed on the login screen
     * @return JSON options and requestId to pass to the browser's WebAuthn API
     */
    public WebAuthnRegistrationStartResponse startAuthentication(String username) throws Exception {
        checkWebAuthnEnabledForUsername(username);
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
        challengeStore.saveAuthenticationChallenge(requestId, assertionRequest);

        log.info("[WebAuthn] Authentication started for username={}, requestId={}", username, requestId);
        return new WebAuthnRegistrationStartResponse(assertionRequest.toJson(), requestId);
    }

    /**
     * Step 2 of authentication: verifies the signed assertion from the browser.
     * On success, updates the sign-count in the correct store and returns a bridge
     * token.
     *
     * <p>
     * <b>Sign-count routing:</b>
     * <ul>
     * <li>If the credential is found in {@code global_webauthn_credentials} →
     * update master DB.</li>
     * <li>If found in local {@code webauthn_credential} → update tenant DB
     * (unchanged).</li>
     * </ul>
     *
     * @param request  the credential JSON and requestId from the browser
     * @param tenantId the tenant this login attempt belongs to (from the URL path)
     * @return a {@link WebAuthnAuthResult} with userId, tenantId, and bridgeToken
     */
    @Transactional
    public WebAuthnAuthResult finishAuthentication(
            WebAuthnAuthenticationFinishRequest request, String tenantId) throws Exception {

        AssertionRequest assertionRequest = challengeStore.getAndRemoveAuthenticationChallenge(request.requestId());
        if (assertionRequest == null) {
            throw new IllegalArgumentException(
                    "WebAuthn authentication request expired or not found. Please try again.");
        }

        String credentialJson = request.credentialJson();
        if (!credentialJson.contains("\"clientExtensionResults\"")) {
            credentialJson = credentialJson.substring(0, credentialJson.length() - 1)
                    + ",\"clientExtensionResults\":{}}";
        }

        PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc = PublicKeyCredential
                .parseAssertionResponseJson(credentialJson);

        AssertionResult result = relyingParty.finishAssertion(
                FinishAssertionOptions.builder()
                        .request(assertionRequest)
                        .response(pkc)
                        .build());

        if (!result.isSuccess()) {
            throw new AssertionFailedException("WebAuthn assertion verification failed.");
        }

        // ── Update sign-count in the correct store ──────────────────────────────
        String credentialId = result.getCredential().getCredentialId().getBase64Url();

        Optional<GlobalWebAuthnCredential> globalCred = tenantSettingsService.isGlobalWebAuthnEnabled()
                ? globalCredentialRepository.findByCredentialId(credentialId)
                : Optional.empty();
        if (globalCred.isPresent()) {
            // Global account path — update master DB
            GlobalWebAuthnCredential gc = globalCred.get();
            gc.setSignCount(result.getSignatureCount());
            gc.setLastUsedAt(Instant.now());
            globalCredentialRepository.save(gc);
            log.info("[WebAuthn] Global assertion success, sign-count updated for credentialId={}", credentialId);
        } else {
            // Local tenant path — update tenant DB (unchanged)
            credentialRepository.findByCredentialId(credentialId).ifPresent(cred -> {
                cred.setSignCount(result.getSignatureCount());
                cred.setLastUsedAt(Instant.now());
                credentialRepository.save(cred);
            });
        }

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
     * Carries the result of a successful WebAuthn authentication back to the
     * controller.
     *
     * @param userId      the authenticated user's database ID
     * @param tenantId    the tenant the user belongs to
     * @param bridgeToken a signed, 60-second token the login page POSTs to
     *                    auth-server-core
     */
    public record WebAuthnAuthResult(Long userId, String tenantId, String bridgeToken) {
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PASSKEY MANAGEMENT (for "Manage Passkeys" settings UI)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns all passkeys registered for the given user.
     * For global accounts (when global WebAuthn is enabled), queries the master DB;
     * otherwise queries the local tenant DB.
     */
    public List<PasskeyResponse> listPasskeys(Long userId) {
        User user = findUserOrThrow(userId);
        Long globalAccountId = hybridCredentialRepository.resolveGlobalAccountId(user.getEmail());

        if (globalAccountId != null && tenantSettingsService.isGlobalWebAuthnEnabled()) {
            return globalCredentialRepository.findAllByAccountId(globalAccountId).stream()
                    .map(c -> new PasskeyResponse(
                            c.getId(), c.getDisplayName(), c.getAaguid(),
                            c.getTransports(), c.getUserVerified(),
                            c.getCreatedAt(), c.getLastUsedAt()))
                    .collect(Collectors.toList());
        }

        return credentialRepository.findAllByUserId(userId).stream()
                .map(c -> new PasskeyResponse(
                        c.getId(), c.getDisplayName(), c.getAaguid(),
                        c.getTransports(), c.getUserVerified(),
                        c.getCreatedAt(), c.getLastUsedAt()))
                .collect(Collectors.toList());
    }

    /**
     * Removes a specific passkey credential. Users can only delete their own keys.
     */
    @Transactional
    public void deletePasskey(Long userId, Long credentialDbId) {
        // Try global store first (only if global passkeys are enabled for this tenant)
        Optional<GlobalWebAuthnCredential> globalCred = tenantSettingsService.isGlobalWebAuthnEnabled()
                ? globalCredentialRepository.findById(credentialDbId)
                : Optional.empty();
        if (globalCred.isPresent()) {
            User user = findUserOrThrow(userId);
            Long globalAccountId = hybridCredentialRepository.resolveGlobalAccountId(user.getEmail());
            if (!globalCred.get().getAccountId().equals(globalAccountId)) {
                throw new SecurityException("Cannot delete another user's passkey.");
            }
            globalCredentialRepository.delete(globalCred.get());
            log.info("[WebAuthn] Global passkey id={} deleted by userId={}", credentialDbId, userId);
            return;
        }

        // Local tenant path (unchanged)
        WebAuthnCredential cred = credentialRepository.findById(credentialDbId)
                .orElseThrow(() -> new ResourceNotFoundException("Passkey not found."));
        if (!cred.getUserId().equals(userId)) {
            throw new SecurityException("Cannot delete another user's passkey.");
        }
        credentialRepository.delete(cred);
        log.info("[WebAuthn] Local passkey id={} deleted by userId={}", credentialDbId, userId);
    }

    /** Updates the display name of a passkey. Ownership is enforced. */
    @Transactional
    public void renamePasskey(Long userId, Long credentialDbId, String newDisplayName) {
        // Try global store first (only if global passkeys are enabled for this tenant)
        Optional<GlobalWebAuthnCredential> globalCred = tenantSettingsService.isGlobalWebAuthnEnabled()
                ? globalCredentialRepository.findById(credentialDbId)
                : Optional.empty();
        if (globalCred.isPresent()) {
            User user = findUserOrThrow(userId);
            Long globalAccountId = hybridCredentialRepository.resolveGlobalAccountId(user.getEmail());
            if (!globalCred.get().getAccountId().equals(globalAccountId)) {
                throw new SecurityException("Cannot rename another user's passkey.");
            }
            globalCred.get().setDisplayName(newDisplayName);
            globalCredentialRepository.save(globalCred.get());
            log.info("[WebAuthn] Global passkey id={} renamed to '{}' by userId={}", credentialDbId, newDisplayName,
                    userId);
            return;
        }

        // Local tenant path (unchanged)
        WebAuthnCredential cred = credentialRepository.findById(credentialDbId)
                .orElseThrow(() -> new ResourceNotFoundException("Passkey not found."));
        if (!cred.getUserId().equals(userId)) {
            throw new SecurityException("Cannot rename another user's passkey.");
        }
        cred.setDisplayName(newDisplayName);
        credentialRepository.save(cred);
        log.info("[WebAuthn] Local passkey id={} renamed to '{}' by userId={}", credentialDbId, newDisplayName, userId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    /**
     * WebAuthn gate for user-id-based calls (registration, list, delete, rename).
     *
     * <p>
     * Two-layer check:
     * <ol>
     * <li>If the user is a <b>global account</b> AND the tenant has opted in to
     * {@code global_webauthn_enabled = true} → allow unconditionally (their
     * passkey is platform-level, not tenant-scoped).</li>
     * <li>Otherwise → enforce the tenant's standard
     * {@code webauthn_fingerprint_enabled}
     * setting, which applies to all local tenant employees as before.</li>
     * </ol>
     *
     * <p>
     * This means a tenant admin who sets
     * {@code webauthn_fingerprint_enabled = false}
     * still fully controls whether passkeys work on their tenant, even for global
     * users,
     * unless they have explicitly opted in to cross-tenant passkey sharing.
     */
    private void checkWebAuthnEnabled(Long userId) {
        User user = findUserOrThrow(userId);
        Long globalAccountId = hybridCredentialRepository.resolveGlobalAccountId(user.getEmail());
        if (globalAccountId != null && tenantSettingsService.isGlobalWebAuthnEnabled()) {
            // Tenant has opted in to global passkeys AND user is a global account → allow
            return;
        }
        // All other cases: respect the standard per-tenant WebAuthn toggle
        enforceWebAuthnTenantSetting();
    }

    /**
     * WebAuthn gate for username-based calls (authentication start).
     *
     * <p>
     * Same two-layer logic as {@link #checkWebAuthnEnabled(Long)}:
     * global users bypass the standard setting only when the tenant has opted in.
     */
    private void checkWebAuthnEnabledForUsername(String username) {
        Long globalAccountId = hybridCredentialRepository.resolveGlobalAccountId(username);
        if (globalAccountId != null && tenantSettingsService.isGlobalWebAuthnEnabled()) {
            return;
        }
        enforceWebAuthnTenantSetting();
    }

    private void enforceWebAuthnTenantSetting() {
        String isEnabled = tenantSettingsService.getSetting("webauthn_fingerprint_enabled");
        if (!"true".equalsIgnoreCase(isEnabled)) {
            throw new AccessDeniedException("WebAuthn / Passkeys are disabled for this tenant.");
        }
    }
}
