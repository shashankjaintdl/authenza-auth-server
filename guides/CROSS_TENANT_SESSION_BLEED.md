# Cross-Tenant Session Bleed (Tenant Session Hopping)

In a multi-tenant Identity and Access Management (IAM) architecture—especially one leveraging Spring Security and Spring Authorization Server—one of the most critical vulnerabilities to avoid is **Cross-Tenant Session Bleed**.

## What is Cross-Tenant Session Bleed?

This vulnerability occurs when a user authenticates against **Tenant A**, establishes a valid HTTP session, and then uses that same session to navigate to **Tenant B's** endpoints (such as `/tenant-b/oauth2/authorize`). Because the HTTP Session cookie (`JSESSIONID`) is typically scoped to the domain (e.g., `localhost` or `authenza.com`), the browser sends the session cookie to both.

If the underlying application does not explicitly verify the tenant ownership of the active session, the framework will interpret the request as fully authenticated. Consequently, the Authorization Server may issue an Authorization Code and eventually a JWT for Tenant B, *using the identity from Tenant A*. 

## Architectural Context in Authenza

In Authenza, tenants can have entirely isolated databases.

1. **Authentication:** A user signs in at `/tenant-a/login`. The `MultiTenantSecurityFilter` sets the data source context to Tenant A. Spring Security validates the credentials against Tenant A's database and creates a `SecurityContext` containing the user's `Authentication` object, which is saved in the HTTP session.
2. **The "Hop":** The user edits the URL to `/tenant-b/oauth2/authorize`. 
3. **The Result:** The browser sends the `JSESSIONID`. Spring Security intercepts this, finds an active `SecurityContext`, and considers the request authenticated. The OAuth2 protocol filters happily issue a code for Tenant B, signed by Tenant B's keys, effectively allowing the user from Tenant A to access Tenant B resources.

## How to Fix It: Strict Multi-Tenant Enforcement

To resolve this issue, you must ensure that a user's active session is strictly bound to the tenant they authenticated against.

### The Recommended Solution: Tenant-Aware Principal Enforcement

The most robust way to solve this is to tightly couple the `tenantId` to the user's authenticated principal and enforce a match on every request.

**Step 1: Create a Tenant-Aware UserPrincipal**
When loading the user from the database during authentication, inject the `tenantId` into the Principal:

```java
public class TenantAwareUserDetails implements UserDetails {
    private final String username;
    private final String password;
    private final String tenantId; // Tightly coupled tenant tracking
    
    // constructor, getters, and standard UserDetails overrides...
}
```

**Step 2: Enforce the Match in the Security Filter**
Update the multi-tenant interceptor to compare the incoming URL's tenant against the active session's tenant.

```java
// Inside MultiTenantSecurityFilter.java
String requestedTenantId = resolveTenantId(uri);

// 1. Get the currently logged-in user from the active Spring Security Session
Authentication auth = SecurityContextHolder.getContext().getAuthentication();

if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
    // 2. Extract our custom Principal
    TenantAwareUserDetails user = (TenantAwareUserDetails) auth.getPrincipal();

    // 3. The critical security check
    if (!user.getTenantId().equals(requestedTenantId)) {
        log.warn("SECURITY ALERT: Cross-Tenant access blocked. User '{}' from '{}' attempted to access '{}'", 
                 user.getUsername(), user.getTenantId(), requestedTenantId);
        
        // Destroy the Security Context so Spring completely ignores the old session
        SecurityContextHolder.clearContext();
        
        // Spring Security will now natively treat the request as "unauthenticated"
        // and redirect the user back to `/tenant-b/login`
    }
}
```

### Alternative Solution: Path-Based Session Cookies
A secondary defense-in-depth tactic is configuring the servlet container to scope the `JSESSIONID` to the specific tenant path (e.g., `Path=/tenant-a/`). This prevents the browser from physically sending the cookie to `/tenant-b/`, though explicit backend enforcement (above) is still required to guarantee zero bypassing via custom clients.
