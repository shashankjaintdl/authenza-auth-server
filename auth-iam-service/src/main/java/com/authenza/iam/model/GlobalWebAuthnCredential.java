package com.authenza.iam.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Persists a single FIDO2/WebAuthn public-key credential registered by a
 * <strong>globally-registered user</strong> (tenant owner, collaborator, or SaaS operator).
 *
 * <p>Stored in the <strong>master database</strong> ({@code global_webauthn_credentials}),
 * not in any tenant-isolated schema. This allows the passkey to be verified across
 * every tenant the user has access to — registering once, logging in everywhere.
 *
 * <p>The {@code accountId} field is a FK to {@code global_accounts.id}, not to a
 * per-tenant {@code application_user.id}. This is the key structural difference from
 * the tenant-local {@link WebAuthnCredential}.
 *
 * <p>Security fields mirror {@link WebAuthnCredential}:
 * <ul>
 *   <li>{@code credentialId} — FIDO2 Credential ID (base64url). Unique across the platform.</li>
 *   <li>{@code publicKeyCose} — COSE-encoded public key used for signature verification.</li>
 *   <li>{@code signCount} — Replay-attack counter. If it goes backwards, a clone is detected.</li>
 * </ul>
 */
@Table("global_webauthn_credentials")
public class GlobalWebAuthnCredential {

    @Id
    private Long id;

    /** FK → global_accounts.id (the platform-level identity, not a tenant-local user ID). */
    @Column("account_id")
    private Long accountId;

    /** FIDO2 Credential ID — base64url-encoded bytes, globally unique per physical key. */
    @Column("credential_id")
    private String credentialId;

    /** COSE-encoded public key bytes (base64url). Used for signature verification. */
    @Column("public_key_cose")
    private String publicKeyCose;

    /** Monotonic counter sent by the authenticator. Increments on every auth use. */
    @Column("sign_count")
    private Long signCount;

    /** Human-readable label the user assigns at registration (e.g., "My MacBook Touch ID"). */
    @Column("display_name")
    private String displayName;

    /** Authenticator Attestation GUID — identifies the device model. */
    @Column("aaguid")
    private String aaguid;

    /** Comma-separated transport hints: internal, usb, nfc, ble. */
    @Column("transports")
    private String transports;

    /** Whether the authenticator performed user verification (biometric or PIN). */
    @Column("user_verified")
    private Boolean userVerified;

    @Column("created_at")
    private Instant createdAt;

    @Column("last_used_at")
    private Instant lastUsedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

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
