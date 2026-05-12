package com.authenza.iam.service;

import com.authenza.common.model.iam.User;
import com.authenza.iam.model.WebAuthnCredential;
import com.authenza.iam.repository.UserRepository;
import com.authenza.iam.repository.WebAuthnCredentialRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.data.exception.HexException;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bridge between Yubico's {@link CredentialRepository} interface and Authenza's
 * tenant-aware Spring Data JDBC repositories.
 *
 * <p>The Yubico {@link com.yubico.webauthn.RelyingParty} calls these methods to look up
 * registered credentials when verifying authentication assertions. Because this class
 * delegates to {@link WebAuthnCredentialRepository}, which is backed by the
 * multi-tenant {@code RoutingDataSource}, all lookups are automatically scoped to
 * the currently active tenant's isolated database.</p>
 *
 * <p>{@code ByteArray.fromBase64Url()} and {@code ByteArray.fromHex()} throw checked
 * exceptions in webauthn-server-core 2.5.4. We wrap them via {@link #parseBase64Url}
 * and {@link #parseHex} helpers that rethrow as {@link IllegalStateException} — these
 * can only fail if the DB contains corrupt data, which is a fatal configuration error,
 * not a recoverable runtime condition.</p>
 */
class TenantAwareCredentialRepository implements CredentialRepository {

    private final WebAuthnCredentialRepository credentialRepository;
    private final UserRepository userRepository;

    TenantAwareCredentialRepository(
            WebAuthnCredentialRepository credentialRepository,
            UserRepository userRepository) {
        this.credentialRepository = credentialRepository;
        this.userRepository = userRepository;
    }

    /**
     * Returns all credential IDs registered by the given username.
     * Called by the Yubico library during authentication to build the
     * {@code allowCredentials} list sent to the browser.
     */
    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return userRepository.findByEmail(username)
                .or(() -> userRepository.findByPreferredUsername(username))
                .map(user -> credentialRepository.findAllByUserId(user.getId()))
                .orElse(java.util.Collections.emptyList())
                .stream()
                .map(c -> PublicKeyCredentialDescriptor.builder()
                        .id(parseBase64Url(c.getCredentialId()))
                        .build())
                .collect(Collectors.toSet());
    }

    /**
     * Looks up the FIDO2 user handle (opaque bytes) for the given username.
     * We encode the user's database Long ID as 8 big-endian bytes (16 hex chars).
     */
    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return userRepository.findByEmail(username)
                .or(() -> userRepository.findByPreferredUsername(username))
                .map(user -> parseHex(String.format("%016x", user.getId())));
    }

    /**
     * Resolves the username for a given user handle (reverse of getUserHandleForUsername).
     * Used to find the user account from the response's user handle during assertion.
     */
    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        try {
            long userId = Long.parseLong(userHandle.getHex(), 16);
            return userRepository.findById(userId).map(User::getEmail);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Loads the full {@link RegisteredCredential} (public key + sign count) for a given
     * credential ID. Called by the Yubico library during assertion verification to get
     * the stored public key for signature checking.
     */
    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return credentialRepository.findByCredentialId(credentialId.getBase64Url())
                .map(c -> RegisteredCredential.builder()
                        .credentialId(parseBase64Url(c.getCredentialId()))
                        .userHandle(userHandle)
                        .publicKeyCose(parseBase64Url(c.getPublicKeyCose()))
                        .signatureCount(c.getSignCount() != null ? c.getSignCount() : 0L)
                        .build());
    }

    /**
     * Looks up all credentials matching a given credential ID regardless of user handle.
     * Used as a fallback in the Yubico library's assertion verification logic.
     */
    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return credentialRepository.findByCredentialId(credentialId.getBase64Url())
                .map(c -> Set.of(RegisteredCredential.builder()
                        .credentialId(parseBase64Url(c.getCredentialId()))
                        .userHandle(parseHex(String.format("%016x", c.getUserId())))
                        .publicKeyCose(parseBase64Url(c.getPublicKeyCose()))
                        .signatureCount(c.getSignCount() != null ? c.getSignCount() : 0L)
                        .build()))
                .orElse(Set.of());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Parses a base64url string into a {@link ByteArray}.
     * Rethrows the checked {@link Base64UrlException} as an unchecked exception —
     * a parse failure here means the DB contains corrupt credential data.
     */
    private static ByteArray parseBase64Url(String value) {
        try {
            return ByteArray.fromBase64Url(value);
        } catch (Base64UrlException e) {
            throw new IllegalStateException(
                    "Corrupt base64url credential data in DB: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a hex string into a {@link ByteArray}.
     * Rethrows the checked {@link HexException} as an unchecked exception —
     * a parse failure here means the user ID hex encoding is malformed.
     */
    private static ByteArray parseHex(String hex) {
        try {
            return ByteArray.fromHex(hex);
        } catch (HexException e) {
            throw new IllegalStateException(
                    "Corrupt hex user handle: " + e.getMessage(), e);
        }
    }
}
