package com.authenza.iam.controller;

import com.authenza.common.dto.ApiResponse;
import com.authenza.iam.service.TenantSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoints for managing per-tenant configuration settings.
 *
 * <p>
 * All endpoints are scoped to the active tenant via the {@code X-Tenant-ID}
 * header
 * resolved by the IAM service's tenant routing interceptor.
 *
 * <h3>MFA Policy</h3>
 * 
 * <pre>
 *   GET  /api/v1/settings              → returns all settings as key-value map
 *   GET  /api/v1/settings/mfa-policy   → returns current MFA enforcement policy
 *   PUT  /api/v1/settings/mfa-policy   → enables/disables tenant-wide MFA
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/settings")
public class TenantSettingsController {

    private final TenantSettingsService settingsService;

    public TenantSettingsController(TenantSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/settings
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all tenant settings as a flat key-value map.
     *
     * <p>Example response:
     * <pre>{@code
     * {
     * "status": 200,
     * "success": true,
     * "data": { "mfa_required_for_all": "false" }
     * }
     * }</pre>
     */
    @GetMapping
    // @PreAuthorize("hasAuthority('audit:read') or
    // hasAuthority('sec:settings:write')")
    public ResponseEntity<ApiResponse<Map<String, String>>> getAllSettings() {
        return ResponseEntity.ok(
                ApiResponse.success(settingsService.getAllSettings(), "Settings retrieved successfully."));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/settings/mfa-policy
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the current tenant-wide MFA enforcement policy.
     *
     * <p>Example response:
     * <pre>{@code
     * {
     * "status": 200,
     * "success": true,
     * "data": { "mfaRequiredForAll": false }
     * }
     * }</pre>
     */
    @GetMapping("/mfa-policy")
    // @PreAuthorize("hasAuthority('audit:read') or
    // hasAuthority('sec:settings:write')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMfaPolicy() {
        boolean required = settingsService.isMfaRequiredForAll();
        return ResponseEntity.ok(
                ApiResponse.success(
                        Map.of("mfaRequiredForAll", required),
                        "MFA policy retrieved successfully."));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /api/v1/settings/mfa-policy
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates the tenant-wide MFA enforcement policy.
     *
     * <p>
     * Request body:
     * 
     * <pre>{@code { "mfaRequiredForAll": true } }</pre>
     *
     * <p>
     * When set to {@code true}, every user in the tenant will be challenged
     * for a TOTP code on login — even users whose individual {@code mfa_enabled}
     * flag is {@code false}. Users with no secret will be directed to the
     * MFA setup page first.
     *
     * @param body JSON object containing {@code mfaRequiredForAll} (boolean)
     */
    @PutMapping("/mfa-policy")
    // @PreAuthorize("hasAuthority('sec:settings:write')")
    public ResponseEntity<ApiResponse<?>> setMfaPolicy(@RequestBody Map<String, Object> body) {
        Object value = body.get("mfaRequiredForAll");
        if (value == null) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.badRequest("Request body must contain 'mfaRequiredForAll' (boolean)."));
        }

        boolean required;
        if (value instanceof Boolean b) {
            required = b;
        } else {
            required = Boolean.parseBoolean(value.toString());
        }

        settingsService.setMfaRequiredForAll(required);
        String msg = required
                ? "MFA is now required for ALL users in this tenant."
                : "Tenant-wide MFA enforcement has been disabled. Per-user flags still apply.";
        return ResponseEntity.ok(ApiResponse.success(Map.of("mfaRequiredForAll", required), msg));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /api/v1/settings
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bulk updates arbitrary tenant settings.
     * Used by the portal for generic configuration (e.g., session policies).
     *
     * <p>Request body:
     * <pre>{@code
     * {
     * "session_policy_enabled": "true",
     * "session_ttl_days": "14"
     * }
     * }</pre>
     */
    @PutMapping
    // @PreAuthorize("hasAuthority('sec:settings:write')")
    public ResponseEntity<ApiResponse<?>> setBulkSettings(@RequestBody Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.badRequest("Request body must contain at least one setting."));
        }

        settings.forEach((k, v) -> settingsService.setSetting(k, v));

        return ResponseEntity.ok(ApiResponse.success(settings, "Settings updated successfully."));
    }
}
