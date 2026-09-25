package com.authenza.iam.service;

import com.authenza.common.model.iam.User;
import com.authenza.iam.model.GlobalWebAuthnCredential;
import com.authenza.iam.model.WebAuthnCredential;
import com.authenza.iam.repository.GlobalWebAuthnCredentialRepository;
import com.authenza.iam.repository.UserRepository;
import com.authenza.iam.repository.WebAuthnCredentialRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.data.exception.HexException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hybrid implementation of Yubico's {@link CredentialRepository} that routes
 * passkey
 * lookups between two credential stores based on the authenticating user's
 * identity type:
 *
 * <ul>
 * <li><b>Global account users</b> (tenant owners, collaborators, SaaS
 * operators): credentials
 * are stored in and resolved from
 * {@code auth-master.global_webauthn_credentials}.
 * A single passkey works across all tenants the user has access to.</li>
 * <li><b>Tenant-local employees</b>: credentials are stored in and resolved
 * from
 * the active tenant's isolated {@code webauthn_credential} table (unchanged
 * behaviour).</li>
 * </ul>
 *
 * <h2>User Handle Encoding</h2>
 * <p>
 * The FIDO2 user handle is a fixed 8-byte (16 hex char) value:
 * <ul>
 * <li>Global users: encode {@code global_accounts.id}</li>
 * <li>Local users: encode {@code application_user.id}</li>
 * </ul>
 * <p>
 * The two ID spaces are independent. During assertion,
 * {@link #getUsernameForUserHandle}
 * tries the master DB first, then falls back to the local tenant DB.
 *
 * <h2>Routing Algorithm</h2>
 * <p>
 * Routing is determined by a lightweight {@code global_accounts} lookup keyed
 * by email.
 * This query hits the master DB and is fast (indexed on {@code email}).
 */
class HybridCredentialRepository implements CredentialRepository {

    private static final Logger log = LoggerFactory.getLogger(HybridCredentialRepository.class);

    private final WebAuthnCredentialRepository localCredentialRepo;
    private final GlobalWebAuthnCredentialRepository globalCredentialRepo;
    private final UserRepository userRepository;
    private final JdbcTemplate masterJdbcTemplate;
    private final TenantSettingsService tenantSettingsService;

    HybridCredentialRepository(
            WebAuthnCredentialRepository localCredentialRepo,
            GlobalWebAuthnCredentialRepository globalCredentialRepo,
            UserRepository userRepository,
            JdbcTemplate masterJdbcTemplate,
            TenantSettingsService tenantSettingsService) {
        this.localCredentialRepo = localCredentialRepo;
        this.globalCredentialRepo = globalCredentialRepo;
        this.userRepository = userRepository;
        this.masterJdbcTemplate = masterJdbcTemplate;
        this.tenantSettingsService = tenantSettingsService;
    }

    // ── CredentialRepository Interface ────────────────────────────────────────

    /**
     * Returns all credential IDs registered by the given username.
     * Called by the Yubico library during authentication to build the
     * {@code allowCredentials} list sent to the browser.
     *
     * <p>Only queries global credentials if the current tenant has
     * {@code global_webauthn_enabled = true}.
     */
    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        Long globalAccountId = resolveGlobalAccountId(username);

        if (globalAccountId != null && tenantSettingsService.isGlobalWebAuthnEnabled()) {
            // Global account path — load from master DB only if tenant opted in
            log.debug("[WebAuthn-Hybrid] Loading global credentials for account_id={}", globalAccountId);
            return globalCredentialRepo.findAllByAccountId(globalAccountId).stream()
                    .map(c -> PublicKeyCredentialDescriptor.builder()
                            .id(parseBase64Url(c.getCredentialId()))
                            .build())
                    .collect(Collectors.toSet());
        }

        // Local tenant path — unchanged
        return userRepository.findByEmail(username)
                .or(() -> userRepository.findByPreferredUsername(username))
                .map(user -> localCredentialRepo.findAllByUserId(user.getId()))
                .orElse(Collections.emptyList())
                .stream()
                .map(c -> PublicKeyCredentialDescriptor.builder()
                        .id(parseBase64Url(c.getCredentialId()))
                        .build())
                .collect(Collectors.toSet());
    }

    /**
     * Looks up the FIDO2 user handle (opaque bytes) for the given username.
     * Global accounts encode {@code global_accounts.id}; local users encode
     * {@code application_user.id}.
     */
    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        Long globalAccountId = resolveGlobalAccountId(username);
        if (globalAccountId != null && tenantSettingsService.isGlobalWebAuthnEnabled()) {
            return Optional.of(parseHex(String.format("%016x", globalAccountId)));
        }
        return userRepository.findByEmail(username)
                .or(() -> userRepository.findByPreferredUsername(username))
                .map(user -> parseHex(String.format("%016x", user.getId())));
    }

    /**
     * Resolves the username (email) for a given user handle.
     * Tries global_accounts first (if global WebAuthn is enabled for this tenant),
     * then falls back to the local tenant's application_user.
     */
    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        try {
            long id = Long.parseLong(userHandle.getHex(), 16);

            // Try master DB first only if global passkeys are enabled for this tenant
            if (tenantSettingsService.isGlobalWebAuthnEnabled()) {
                List<String> globalEmails = masterJdbcTemplate.query(
                        "SELECT email FROM global_accounts WHERE id = ? LIMIT 1",
                        (rs, rowNum) -> rs.getString("email"),
                        id);
                if (!globalEmails.isEmpty()) {
                    return Optional.of(globalEmails.get(0));
                }
            }

            // Fall back to tenant-local application_user
            return userRepository.findById(id).map(User::getEmail);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Loads the full {@link RegisteredCredential} (public key + sign count) for a
     * given
     * credential ID + user handle. Called by the Yubico library during assertion
     * verification.
     * Checks global store first (if enabled), then local.
     */
    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        String credentialIdStr = credentialId.getBase64Url();

        // Check global store first only if global passkeys are enabled for this tenant
        if (tenantSettingsService.isGlobalWebAuthnEnabled()) {
            Optional<GlobalWebAuthnCredential> globalCred = globalCredentialRepo.findByCredentialId(credentialIdStr);
            if (globalCred.isPresent()) {
                GlobalWebAuthnCredential c = globalCred.get();
                return Optional.of(RegisteredCredential.builder()
                        .credentialId(parseBase64Url(c.getCredentialId()))
                        .userHandle(userHandle)
                        .publicKeyCose(parseBase64Url(c.getPublicKeyCose()))
                        .signatureCount(c.getSignCount() != null ? c.getSignCount() : 0L)
                        .build());
            }
        }

        // Fall back to local tenant store
        return localCredentialRepo.findByCredentialId(credentialIdStr)
                .map(c -> RegisteredCredential.builder()
                        .credentialId(parseBase64Url(c.getCredentialId()))
                        .userHandle(userHandle)
                        .publicKeyCose(parseBase64Url(c.getPublicKeyCose()))
                        .signatureCount(c.getSignCount() != null ? c.getSignCount() : 0L)
                        .build());
    }

    /**
     * Looks up all credentials matching a credential ID regardless of user handle.
     * Fallback path in the Yubico assertion verification logic.
     * Merges results from both stores (respecting global_webauthn_enabled).
     */
    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        String credentialIdStr = credentialId.getBase64Url();
        Set<RegisteredCredential> results = new java.util.HashSet<>();

        // Global store (only if enabled for this tenant)
        if (tenantSettingsService.isGlobalWebAuthnEnabled()) {
            globalCredentialRepo.findByCredentialId(credentialIdStr)
                    .ifPresent(c -> results.add(RegisteredCredential.builder()
                            .credentialId(parseBase64Url(c.getCredentialId()))
                            .userHandle(parseHex(String.format("%016x", c.getAccountId())))
                            .publicKeyCose(parseBase64Url(c.getPublicKeyCose()))
                            .signatureCount(c.getSignCount() != null ? c.getSignCount() : 0L)
                            .build()));
        }

        // Local tenant store
        localCredentialRepo.findByCredentialId(credentialIdStr)
                .ifPresent(c -> results.add(RegisteredCredential.builder()
                        .credentialId(parseBase64Url(c.getCredentialId()))
                        .userHandle(parseHex(String.format("%016x", c.getUserId())))
                        .publicKeyCose(parseBase64Url(c.getPublicKeyCose()))
                        .signatureCount(c.getSignCount() != null ? c.getSignCount() : 0L)
                        .build()));

        return results;
    }

    // ── Internal Routing Helper ────────────────────────────────────────────────

    /**
     * Looks up the {@code global_accounts.id} for the given email/username.
     * Returns {@code null} if the user is not a globally-registered account.
     *
     * <p>
     * This is the routing pivot: a non-null result means the global store is used;
     * {@code null} means fall through to the local tenant store.
     */
    Long resolveGlobalAccountId(String email) {
        try {
            List<Long> ids = masterJdbcTemplate.query(
                    "SELECT id FROM global_accounts WHERE email = ? LIMIT 1",
                    (rs, rowNum) -> rs.getLong("id"),
                    email);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("[WebAuthn-Hybrid] Could not resolve global_accounts.id for '{}': {}", email, e.getMessage());
            return null;
        }
    }

    // ── ByteArray Parse Helpers ────────────────────────────────────────────────

    private static ByteArray parseBase64Url(String value) {
        try {
            return ByteArray.fromBase64Url(value);
        } catch (Base64UrlException e) {
            throw new IllegalStateException(
                    "Corrupt base64url credential data in DB: " + e.getMessage(), e);
        }
    }

    private static ByteArray parseHex(String hex) {
        try {
            return ByteArray.fromHex(hex);
        } catch (HexException e) {
            throw new IllegalStateException(
                    "Corrupt hex user handle: " + e.getMessage(), e);
        }
    }
}
