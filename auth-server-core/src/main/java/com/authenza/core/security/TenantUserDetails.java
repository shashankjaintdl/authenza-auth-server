package com.authenza.core.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Objects;

/**
 * Custom {@link org.springframework.security.core.userdetails.UserDetails} implementation
 * that carries the {@code tenantId} of the authenticated principal in the security session.
 */
public class TenantUserDetails extends User {

    private static final long serialVersionUID = 1L;

    private final String tenantId;

    public TenantUserDetails(
            String tenantId,
            String username,
            String password,
            boolean enabled,
            boolean accountNonExpired,
            boolean credentialsNonExpired,
            boolean accountNonLocked,
            Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, accountNonExpired, credentialsNonExpired, accountNonLocked, authorities);
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    }

    public String getTenantId() {
        return tenantId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        TenantUserDetails that = (TenantUserDetails) o;
        return Objects.equals(tenantId, that.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), tenantId);
    }

    @Override
    public String toString() {
        return "TenantUserDetails [tenantId=" + tenantId + ", username=" + getUsername()
                + ", enabled=" + isEnabled() + ", authorities=" + getAuthorities() + "]";
    }
}
