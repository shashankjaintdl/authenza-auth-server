package com.authenza.master.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Represents a link between a {@link GlobalAccount} and a specific tenant,
 * together with the admin's role within that tenant.
 *
 * <p>This is the bridge table that enables the "One Admin, Multiple Tenants" model.
 * A single admin can have multiple memberships — one per tenant they own or collaborate on.
 *
 * <p>The list of memberships for a given {@code tenantId} powers the
 * <strong>"Tenant Members"</strong> UI panel in the dashboard.
 * The list of memberships for a given {@code accountId} powers the
 * <strong>Tenant Switcher</strong> component.
 */
@Table("tenant_memberships")
public class TenantMembership {

    @Id
    private Long id;

    /** FK → global_accounts.id */
    @Column("account_id")
    private Long accountId;

    /** Logical FK → tenants.tenant_id (e.g., "acme-corp") */
    @Column("tenant_id")
    private String tenantId;

    /**
     * The admin's role within this tenant.
     * Values: "OWNER" (provisioned the tenant) | "COLLABORATOR" (invited)
     */
    @Column("platform_role")
    private String platformRole;

    /**
     * The account that sent the invitation.
     * {@code null} for the initial OWNER (self-provisioned).
     */
    @Column("invited_by_account_id")
    private Long invitedByAccountId;

    @Column("created_at")
    private Instant createdAt;

    // ─────────────────────────────────────────────
    // Getters & Setters
    // ─────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getPlatformRole() { return platformRole; }
    public void setPlatformRole(String platformRole) { this.platformRole = platformRole; }

    public Long getInvitedByAccountId() { return invitedByAccountId; }
    public void setInvitedByAccountId(Long invitedByAccountId) { this.invitedByAccountId = invitedByAccountId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
