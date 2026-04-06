package com.authenza.common.model.iam;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Table("role")
public class Role {

    @Id
    private Long id;

    // e.g., "ADMIN", "MANAGER", "VIEWER"
    private String name;

    // Human-readable label: "System Administrator"
    private String displayName;

    // e.g., "Full access to all tenant resources"
    private String description;

    // Is this a system-default role that cannot be deleted?
    // e.g., "ADMIN" and "USER" are defaults; "BILLING_MANAGER" is custom
    private Boolean systemDefault;

    // Audit
    private Instant createdAt;
    private Instant updatedAt;

    // ── ManyToMany: Role ↔ Authority ──
    @MappedCollection(idColumn = "role_id")
    private Set<RoleAuthorityRef> authorities = new HashSet<>();

    // Inner class — lives right here, no separate file
    @Table("role_authority")
    public static class RoleAuthorityRef {
        private Long authorityId;

        public Long getAuthorityId() {
            return authorityId;
        }

        public void setAuthorityId(Long authorityId) {
            this.authorityId = authorityId;
        }
    }

    // Getters and Setters...
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getSystemDefault() {
        return systemDefault;
    }

    public void setSystemDefault(Boolean systemDefault) {
        this.systemDefault = systemDefault;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Set<RoleAuthorityRef> getAuthorities() {
        return authorities;
    }

    public void setAuthorities(Set<RoleAuthorityRef> authorities) {
        this.authorities = authorities;
    }
}
