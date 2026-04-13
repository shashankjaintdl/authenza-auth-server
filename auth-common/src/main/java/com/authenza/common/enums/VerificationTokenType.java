package com.authenza.common.enums;

// 2. Token/verification type (for email_verification_token table)
public enum VerificationTokenType {
    EMAIL_VERIFICATION,     // New account email confirmation
    PASSWORD_RESET,         // Forgot password flow
    EMAIL_CHANGE,           // User changing their email address
    PHONE_VERIFICATION,     // Phone/SMS OTP
    MFA_BACKUP_CODE,        // MFA recovery codes
    ADMIN_INVITE            // Tenant admin invitation link
}