package com.authenza.master.controller;

import com.authenza.master.dto.GlobalAccountRequest;
import com.authenza.master.dto.GlobalAccountResponse;
import com.authenza.master.dto.InviteMemberRequest;
import com.authenza.master.model.TenantMembership;
import com.authenza.master.service.GlobalAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Global Identity Registry operations.
 *
 * <p>These endpoints operate on the <strong>master database</strong> only.
 * They manage the lifecycle of platform-level admin accounts ({@code global_accounts})
 * and their tenant memberships ({@code tenant_memberships}).
 *
 * <p>Segregation summary:
 * <ul>
 *   <li>{@code GET /admin/tenant/{tenantId}/members} → "Tenant Members" panel (master DB)</li>
 *   <li>{@code GET /api/v1/users} (auth-iam-service) → "Users" panel (tenant DB)</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/accounts")
public class GlobalAccountController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalAccountController.class);

    private final GlobalAccountService globalAccountService;
    private final com.authenza.master.service.TenantProvisioningService tenantProvisioningService;

    public GlobalAccountController(GlobalAccountService globalAccountService,
                                   com.authenza.master.service.TenantProvisioningService tenantProvisioningService) {
        this.globalAccountService = globalAccountService;
        this.tenantProvisioningService = tenantProvisioningService;
    }

    /**
     * Register a new platform-level admin account.
     * Creates a record in {@code global_accounts} with a BCrypt-hashed password.
     * This must be called before the admin can create or be invited to a tenant.
     *
     * <p>POST /admin/accounts/register
     */
    @PostMapping("/register")
    public ResponseEntity<GlobalAccountResponse> register(
            @Valid @RequestBody GlobalAccountRequest request) {
        GlobalAccountResponse response = globalAccountService.registerAccount(request);
        
        try {
            com.authenza.master.model.GlobalAccount account = globalAccountService.findByEmailOrThrow(request.email());
            tenantProvisioningService.seedShadowUserInSystemAdmin(account);
        } catch (Exception e) {
            log.warn("Failed to automatically seed shadow user into system-admin for new registry account", e);
        }

        return ResponseEntity.status(201).body(response);
    }

    /**
     * Returns all tenants that a given admin account belongs to.
     * Powers the <strong>Tenant Switcher</strong> UI component.
     *
     * <p>GET /admin/accounts/{accountId}/tenants
     */
    @GetMapping("/{accountId}/tenants")
    public ResponseEntity<List<TenantMembership>> getTenants(@PathVariable Long accountId) {
        List<TenantMembership> memberships = globalAccountService.getTenantsForAccount(accountId);
        return ResponseEntity.ok(memberships);
    }

    /**
     * Returns all members (admins) for a given tenant.
     * Powers the <strong>"Tenant Members"</strong> tab in the portal settings.
     *
     * <p>GET /admin/tenant/{tenantId}/members
     */
    @GetMapping("/tenant/{tenantId}/members")
    public ResponseEntity<List<GlobalAccountResponse>> getMembers(@PathVariable String tenantId) {
        List<GlobalAccountResponse> members = globalAccountService.getMembersForTenant(tenantId);
        return ResponseEntity.ok(members);
    }

    /**
     * Invites an existing platform admin to collaborate on a specific tenant.
     * The invitee must already be registered in {@code global_accounts}.
     *
     * <p>POST /admin/tenant/{tenantId}/invite
     *
     * @param inviterAccountId the ID of the admin performing the invite (from auth header in future)
     */
    @PostMapping("/tenant/{tenantId}/invite")
    public ResponseEntity<GlobalAccountResponse> inviteMember(
            @PathVariable String tenantId,
            @RequestParam Long inviterAccountId,
            @Valid @RequestBody InviteMemberRequest request) {
        GlobalAccountResponse response = globalAccountService.inviteMember(
                inviterAccountId, tenantId, request.email(), request.platformRole());
        return ResponseEntity.status(201).body(response);
    }
}
