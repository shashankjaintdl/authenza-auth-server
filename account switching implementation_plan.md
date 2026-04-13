# Technical Design: Multi-Account Switching (Google-Style)

This document outlines how to implement a seamless "Switch Account" feature within the Authenza multi-tenant architecture. 

## Design Objective
Allow a user to stay logged into multiple independent tenant accounts (e.g., `tenant-a`, `tenant-b`) and switch between them via a dropdown menu without re-entering credentials, similar to the Google Account Switcher.

## Proposed Architecture

### 1. Global Identity Registry (Master Service)
To link accounts across isolated databases, we need a "Global Registry" in the **Master Database**. This table acts as the "glue" between different tenant identities.

#### [NEW] Table: `user_identity_mappings` (Master DB)
| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | BIGINT | Primary Key |
| `global_user_id` | UUID | Unique identifier for a "physical person" |
| `tenant_id` | VARCHAR | The tenant this account belongs to |
| `local_user_id` | BIGINT | The user's ID within the specific tenant database |

### 2. Sample Code: The Account Switcher UI
In your **auth-server-core** templates (e.g., in a navigation header), you would fetch the list of linked accounts and render a dropdown.

```html
<!-- Thymeleaf Fragment: account-switcher.html -->
<div class="account-switcher">
    <button class="active-account">
        <span th:text="${currentUser.email}">user@tenant-a.com</span>
        <span class="tenant-badge" th:text="${tenantId}">tenant-a</span>
    </button>
    <div class="dropdown-menu">
        <!-- List other linked accounts -->
        <th:block th:each="link : ${linkedAccounts}">
            <a th:href="@{'/' + ${link.tenantId} + '/switch-context?targetId=' + ${link.localUserId}}"
               class="switch-link">
                <span th:text="${link.email}">user@tenant-b.com</span>
                <span class="tenant-badge" th:text="${link.tenantId}">tenant-b</span>
            </a>
        </th:block>
        <hr/>
        <a th:href="@{'/system-master/login?prompt=select_account'}" class="add-account">
            + Add another account
        </a>
    </div>
</div>
```

### 3. Sample Code: The Switcher Controller
This controller (located in `auth-server-core`) handles the transition logic.

```java
@Controller
public class AccountSwitchController {

    private final IamServiceClient iamClient;
    private final MasterIdentityService masterService;

    @GetMapping("/{tenantId}/switch-context")
    public String switchAccount(@PathVariable String tenantId, 
                                @RequestParam String targetId,
                                HttpServletRequest request) {
        
        // 1. Verify that the currently logged-in user (Session A) 
        //    owns the target account (Identity B) via the Master DB.
        boolean authorized = masterService.verifyLink(
            TenantContextHolder.getTenantId(), 
            request.getUserPrincipal().getName(), 
            tenantId, 
            targetId
        );

        if (!authorized) {
            return "redirect:/error/unauthorized-switch";
        }

        // 2. Issuing a new context transition
        // In a Google-style system, we simply redirect to the new path.
        // Your existing MultiTenantSecurityFilter will catch the new tenantId,
        // and if the browser already has a valid JWT for Tenant B, it works instantly.
        return "redirect:/" + tenantId + "/dashboard";
    }
}
```

### 4. Implementation Steps (Phased)

#### Phase A: Client-Side "Recent Accounts" (Easiest)
*   The browser stores a list of "Recently Logged In" tenant IDs in a cookie or LocalStorage.
*   The UI simply renders links to `/{tenantId}/login`.
*   If the user has a valid session for that tenant, they are logged in automatically via standard Spring Security.

#### Phase B: Server-Side Global Identity
*   Implement the `user_identity_mappings` table.
*   Update `RegisterController` to automatically "link" a new registration to an existing global identity if the email matches.

## User Review Required

> [!IMPORTANT]
> **Privacy Consideration:** Should User A automatically know they have an account in Tenant B just by looking at the switcher? Some organizations might consider "Account Discovery" a privacy leak. We should consider a "Hide from Switcher" setting per tenant.

## Open Questions
- Do you want users to be able to log out of *one* account while staying in others, or should "Sign Out" always be global?
- Should the "Add Another Account" flow use a shared "Master Login" page, or always redirect to the specific tenant's login screen?

---

**Next Step:** Once you approve this design, I will add it as **Phase 9** in your `ROADMAP.md` and we can begin drafting the database migration.
