package com.authenza.master.service;

import com.authenza.master.dto.GlobalAccountRequest;
import com.authenza.master.dto.GlobalAccountResponse;
import com.authenza.master.model.GlobalAccount;
import com.authenza.master.model.TenantMembership;
import com.authenza.master.repository.GlobalAccountRepository;
import com.authenza.master.repository.TenantMembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages platform-level admin accounts and their tenant memberships.
 *
 * <p>This service is the heart of the <strong>Global Identity Registry</strong> (Strategy 3).
 * It operates exclusively on the <strong>master database</strong> and is responsible for:
 * <ul>
 *   <li>Creating and managing {@code global_accounts} records</li>
 *   <li>Maintaining {@code tenant_memberships} (linking admins to tenants)</li>
 * </ul>
 *
 * <p>Per-tenant shadow user seeding is handled by {@link TenantProvisioningService}.
 */
@Service
public class GlobalAccountService {

    private static final Logger log = LoggerFactory.getLogger(GlobalAccountService.class);

    private final GlobalAccountRepository accountRepository;
    private final TenantMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;

    public GlobalAccountService(GlobalAccountRepository accountRepository,
                                TenantMembershipRepository membershipRepository,
                                PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ─────────────────────────────────────────────
    // Account Management
    // ─────────────────────────────────────────────

    /**
     * Registers a new platform-level admin account.
     * The password is BCrypt-hashed here and stored only in {@code global_accounts}.
     * Per-tenant shadow users will have {@code password = NULL} (Option B).
     *
     * @param request the registration details
     * @return a safe response DTO (no password hash exposed)
     * @throws IllegalArgumentException if the email is already registered
     */
    @Transactional
    public GlobalAccountResponse registerAccount(GlobalAccountRequest request) {
        if (accountRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException(
                    "A global account with email '" + request.email() + "' already exists.");
        }

        GlobalAccount account = new GlobalAccount();
        account.setEmail(request.email());
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setGivenName(request.givenName());
        account.setFamilyName(request.familyName());
        account.setStatus("ACTIVE");
        account.setCreatedAt(Instant.now());

        GlobalAccount saved = accountRepository.save(account);
        log.info("Registered new global account for '{}'", saved.getEmail());
        return toResponse(saved, null);
    }

    /**
     * Looks up a global account by email.
     * Used by {@code JdbcTenantUserDetailsService} as a login fallback
     * when a shadow user's password is {@code NULL}.
     *
     * @param email the admin's email address
     * @return the GlobalAccount, or throws if not found
     */
    public GlobalAccount findByEmailOrThrow(String email) {
        return accountRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No global account found for email: " + email));
    }

    // ─────────────────────────────────────────────
    // Membership Management
    // ─────────────────────────────────────────────

    /**
     * Links a global admin account to a tenant with the specified platform role.
     * Idempotent — if the membership already exists, it is skipped.
     *
     * @param accountId    the global_accounts primary key
     * @param tenantId     the tenant identifier (e.g., "acme-corp")
     * @param platformRole "OWNER" or "COLLABORATOR"
     */
    @Transactional
    public void addMembership(Long accountId, String tenantId, String platformRole) {
        if (membershipRepository.existsByAccountIdAndTenantId(accountId, tenantId)) {
            log.info("Membership already exists for account '{}' in tenant '{}'. Skipping.", accountId, tenantId);
            return;
        }

        TenantMembership membership = new TenantMembership();
        membership.setAccountId(accountId);
        membership.setTenantId(tenantId);
        membership.setPlatformRole(platformRole);
        membership.setCreatedAt(Instant.now());

        membershipRepository.save(membership);
        log.info("Created '{}' membership for account '{}' in tenant '{}'", platformRole, accountId, tenantId);
    }

    /**
     * Invites an existing global admin as a collaborator to a tenant.
     * The invitee must already have a {@code global_accounts} record.
     *
     * @param inviterAccountId  the account doing the inviting
     * @param tenantId          the tenant to invite into
     * @param inviteeEmail      the email of the admin being invited
     * @return the invitee's account response with their new membership role
     */
    @Transactional
    public GlobalAccountResponse inviteMember(Long inviterAccountId, String tenantId,
                                               String inviteeEmail, String platformRole) {
        GlobalAccount invitee = accountRepository.findByEmail(inviteeEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No global account found for email: " + inviteeEmail +
                        ". The user must register first before being invited."));

        if (membershipRepository.existsByAccountIdAndTenantId(invitee.getId(), tenantId)) {
            throw new IllegalArgumentException(
                    "'" + inviteeEmail + "' is already a member of tenant '" + tenantId + "'.");
        }

        TenantMembership membership = new TenantMembership();
        membership.setAccountId(invitee.getId());
        membership.setTenantId(tenantId);
        membership.setPlatformRole(platformRole != null ? platformRole : "COLLABORATOR");
        membership.setInvitedByAccountId(inviterAccountId);
        membership.setCreatedAt(Instant.now());
        membershipRepository.save(membership);

        log.info("Account '{}' invited '{}' as '{}' to tenant '{}'",
                inviterAccountId, inviteeEmail, platformRole, tenantId);
        return toResponse(invitee, membership.getPlatformRole());
    }

    /**
     * Returns all admins (members) for a given tenant.
     * Powers the <strong>"Tenant Members"</strong> tab in the portal.
     */
    public List<GlobalAccountResponse> getMembersForTenant(String tenantId) {
        return membershipRepository.findByTenantId(tenantId).stream()
                .map(m -> accountRepository.findById(m.getAccountId())
                        .map(account -> toResponse(account, m.getPlatformRole()))
                        .orElse(null))
                .filter(r -> r != null)
                .collect(Collectors.toList());
    }

    /**
     * Returns all tenants an admin has access to.
     * Powers the <strong>Tenant Switcher</strong> in the portal dashboard.
     */
    public List<TenantMembership> getTenantsForAccount(Long accountId) {
        return membershipRepository.findByAccountId(accountId);
    }

    // ─────────────────────────────────────────────
    // Internal Helpers
    // ─────────────────────────────────────────────

    private GlobalAccountResponse toResponse(GlobalAccount account, String platformRole) {
        return new GlobalAccountResponse(
                account.getId(),
                account.getEmail(),
                account.getGivenName(),
                account.getFamilyName(),
                account.getStatus(),
                platformRole,
                account.getCreatedAt()
        );
    }
}
