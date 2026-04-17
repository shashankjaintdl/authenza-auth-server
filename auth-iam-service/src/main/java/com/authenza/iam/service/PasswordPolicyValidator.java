package com.authenza.iam.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Centralized password policy enforcement.
 * All password-setting flows (registration, change password, reset password,
 * and invitation acceptance) must route through this validator.
 *
 * <p>Policies enforced:</p>
 * <ul>
 *   <li>Minimum 8 characters</li>
 *   <li>At least one uppercase letter</li>
 *   <li>At least one lowercase letter</li>
 *   <li>At least one digit</li>
 *   <li>At least one special character</li>
 *   <li>Not in common/breached password list</li>
 * </ul>
 */
public final class PasswordPolicyValidator {

    private PasswordPolicyValidator() {
        // Utility class — no instantiation
    }

    private static final int MIN_LENGTH = 8;
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]");

    /**
     * A small blocklist of the most commonly breached passwords.
     * In production, this should be backed by a database table or
     * integrated with the HIBP (Have I Been Pwned) API.
     */
    private static final List<String> COMMON_PASSWORDS = List.of(
            "password", "12345678", "123456789", "1234567890",
            "qwerty123", "password1", "iloveyou", "admin123",
            "welcome1", "letmein12", "p@ssw0rd", "changeme",
            "password123", "abc12345", "qwerty12", "trustno1"
    );

    /**
     * Validates a password against all policy rules.
     *
     * @param password the plaintext password to validate
     * @throws IllegalArgumentException if any policy rule is violated
     */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_LENGTH + " characters long.");
        }

        if (!UPPERCASE.matcher(password).find()) {
            throw new IllegalArgumentException(
                    "Password must contain at least one uppercase letter.");
        }

        if (!LOWERCASE.matcher(password).find()) {
            throw new IllegalArgumentException(
                    "Password must contain at least one lowercase letter.");
        }

        if (!DIGIT.matcher(password).find()) {
            throw new IllegalArgumentException(
                    "Password must contain at least one digit.");
        }

        if (!SPECIAL.matcher(password).find()) {
            throw new IllegalArgumentException(
                    "Password must contain at least one special character (!@#$%^&* etc.).");
        }

        if (COMMON_PASSWORDS.contains(password.toLowerCase())) {
            throw new IllegalArgumentException(
                    "This password is too common and has been found in data breaches. Please choose a stronger password.");
        }
    }
}
