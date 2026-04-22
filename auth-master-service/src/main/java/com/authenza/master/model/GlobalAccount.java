package com.authenza.master.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Represents a global platform-level administrator identity stored in the master database.
 *
 * <p>This is the <strong>single authoritative source</strong> for admin credentials.
 * Each admin has exactly one {@code GlobalAccount} regardless of how many tenants they own.
 *
 * <p><strong>Option B (Passwordless Shadow Login):</strong> Per-tenant {@code application_user}
 * records for admins intentionally have {@code password = NULL}. When
 * {@code JdbcTenantUserDetailsService} detects a NULL password, it falls back to
 * {@code global_accounts.password_hash} in the master DB for authentication.
 * This ensures a single password controls access to all owned tenants.
 */
@Table("global_accounts")
public class GlobalAccount {

    @Id
    private Long id;

    /** Primary login identifier — unique across the entire platform. */
    private String email;

    /**
     * BCrypt-hashed password. This is the ONLY stored credential for this admin.
     * Do NOT copy this into tenant databases — tenant shadow users have NULL passwords.
     */
    @Column("password_hash")
    private String passwordHash;

    @Column("given_name")
    private String givenName;

    @Column("family_name")
    private String familyName;

    /** ACTIVE | SUSPENDED */
    private String status;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("last_login_at")
    private Instant lastLoginAt;

    // ─────────────────────────────────────────────
    // Getters & Setters
    // ─────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }

    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
