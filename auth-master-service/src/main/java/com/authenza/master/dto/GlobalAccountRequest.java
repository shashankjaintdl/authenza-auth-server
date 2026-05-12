package com.authenza.master.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for registering a new global platform admin account.
 * This creates a record in {@code global_accounts} in the master database.
 */
public record GlobalAccountRequest(

        @NotBlank(message = "Email is required.")
        @Email(message = "Must be a valid email address.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, message = "Password must be at least 8 characters.")
        String password,

        @NotBlank(message = "First name is required.")
        String givenName,

        @NotBlank(message = "Last name is required.")
        String familyName
) {}
