package com.authenza.master.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for inviting an existing platform admin
 * (already registered in global_accounts) as a collaborator to a tenant.
 */
public record InviteMemberRequest(

        @NotBlank(message = "Email is required.")
        @Email(message = "Must be a valid email address.")
        String email,

        /** Defaults to COLLABORATOR if not specified. */
        String platformRole
) {}
