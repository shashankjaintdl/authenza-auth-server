package com.authenza.common.model.iam;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("authority")
public class Authority {

    @Id
    private Long id;

    // OAuth2 scope format: "resource:action"
    // e.g., "user:read", "user:write", "order:delete", "report:export"
    private String permission;

    // Human-readable label for the admin dashboard
    // e.g., "Read Users", "Delete Orders"
    private String displayName;

    // Description shown in the consent screen
    // e.g., "Allows reading user profile information"
    private String description;

    // Group authorities in the UI: "User Management", "Billing", "Reports"
    private String category;

    // Audit
    private Instant createdAt;

    // Getters and Setters...

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

