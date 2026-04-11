package com.authenza.common.enums;

public enum UserStatus {

    // ── Registration & Verification ───────────────────────────
    PENDING_VERIFICATION,   // Registered but email not yet verified
    ACTIVE,                 // Fully verified, can log in normally

    // ── Administrative States ─────────────────────────────────
    SUSPENDED,              // Temporarily disabled by tenant admin (can be re-enabled)
    DEACTIVATED,            // Soft-deactivated (user requested or admin action) — login blocked
    DELETED,                // Soft-deleted — data retained for audit, login impossible

    // ── Security States ───────────────────────────────────────
    LOCKED,                 // Locked due to too many failed login attempts (auto-unlocks after cooldown)
    COMPROMISED,            // Flagged by security system (e.g., breached password detected via HIBP)

    // ── Invitation Flow ───────────────────────────────────────
    INVITED,                // Tenant admin sent an invite; user hasn't set password yet

    // ── MFA Enforcement ──────────────────────────────────────
    MFA_ENROLLMENT_PENDING, // MFA is required by tenant policy but user hasn't enrolled yet
}
