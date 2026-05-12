package com.authenza.iam.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Persists a single FIDO2/WebAuthn public-key credential registered by a user.
 *
 * <p>Each row represents one physical authenticator device: e.g., a Touch ID MacBook,
 * a YubiKey 5C, or a Windows Hello passkey. A single user can register multiple
 * credentials (one per device they want to use for passwordless login).</p>
 *
 * <p>Key security fields:</p>
 * <ul>
 *   <li>{@code credentialId} — The raw FIDO2 Credential ID (base64url). Sent by the
 *       browser every time the user authenticates with this key.</li>
 *   <li>{@code publicKeyCose} — The COSE-encoded public key bytes (base64url). Used to
 *       cryptographically verify the authenticator's signature on each login.</li>
 *   <li>{@code signCount} — A monotonically-increasing counter the authenticator increments
 *       on every use. If the counter goes backwards, we detect a cloned key attack.</li>
 * </ul>
 */
@Table("webauthn_credential")
public class WebAuthnCredential {

    @Id
    private Long id;

    /** FK → application_user.id */
    private Long userId;

    /** FIDO2 Credential ID — base64url-encoded bytes, globally unique per key. */
    private String credentialId;

    /** COSE-encoded public key bytes (base64url). Used for signature verification. */
    private String publicKeyCose;

    /** Monotonic counter sent by the authenticator. Increments on every auth use. */
    private Long signCount;

    /** Human-readable label the user assigns at registration (e.g., "My iPhone"). */
    private String displayName;

    /** Authenticator Attestation GUID — identifies the model (e.g., "Apple Touch ID"). */
    private String aaguid;

    /** JSON array of transports: ["internal"], ["usb", "nfc"], ["ble"] etc. */
    private String transports;

    /** Whether the authenticator verified the user (e.g., biometric or PIN). */
    private Boolean userVerified;

    private Instant createdAt;
    private Instant lastUsedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }

    public String getPublicKeyCose() { return publicKeyCose; }
    public void setPublicKeyCose(String publicKeyCose) { this.publicKeyCose = publicKeyCose; }

    public Long getSignCount() { return signCount; }
    public void setSignCount(Long signCount) { this.signCount = signCount; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getAaguid() { return aaguid; }
    public void setAaguid(String aaguid) { this.aaguid = aaguid; }

    public String getTransports() { return transports; }
    public void setTransports(String transports) { this.transports = transports; }

    public Boolean getUserVerified() { return userVerified; }
    public void setUserVerified(Boolean userVerified) { this.userVerified = userVerified; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
