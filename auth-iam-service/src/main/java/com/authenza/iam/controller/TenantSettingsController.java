package com.authenza.iam.controller;

import com.authenza.common.constant.AdaptiveAuthSettings;
import com.authenza.common.dto.ApiResponse;
import com.authenza.common.enums.RiskFailMode;
import com.authenza.iam.service.TenantSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    // ─────────────────────────────────────────────────────────────────────────
    // GET / PUT /api/v1/settings/adaptive-auth
    // ─────────────────────────────────────────────────────────────────────────

    private static final Set<String> ADAPTIVE_KEYS = Set.of(
            AdaptiveAuthSettings.ENABLED,
            AdaptiveAuthSettings.SCORE_STEP_UP,
            AdaptiveAuthSettings.SCORE_BLOCK,
            AdaptiveAuthSettings.NOTIFY_NEW_DEVICE,
            AdaptiveAuthSettings.FAIL_MODE,
            AdaptiveAuthSettings.TRUST_DEVICE_DAYS,
            AdaptiveAuthSettings.NEW_IP_LOOKBACK_DAYS
    );

    @GetMapping("/adaptive-auth")
    public ResponseEntity<ApiResponse<Map<String, String>>> getAdaptiveAuth() {
        Map<String, String> all = settingsService.getAllSettings();
        Map<String, String> adaptive = new LinkedHashMap<>();
        for (String key : ADAPTIVE_KEYS) {
            adaptive.put(key, all.getOrDefault(key, defaultAdaptiveValue(key)));
        }
        return ResponseEntity.ok(ApiResponse.success(adaptive, "Adaptive auth settings retrieved."));
    }

    @PutMapping("/adaptive-auth")
    public ResponseEntity<ApiResponse<?>> setAdaptiveAuth(@RequestBody Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.badRequest("Request body must contain adaptive auth settings."));
        }
        for (Map.Entry<String, String> entry : body.entrySet()) {
            if (!ADAPTIVE_KEYS.contains(entry.getKey())) {
                return ResponseEntity.badRequest().body(
                        ApiResponse.badRequest("Unknown adaptive setting: " + entry.getKey()));
            }
            String validated = validateAdaptiveValue(entry.getKey(), entry.getValue());
            if (validated == null) {
                return ResponseEntity.badRequest().body(
                        ApiResponse.badRequest("Invalid value for " + entry.getKey()));
            }
            settingsService.setSetting(entry.getKey(), validated);
        }
        ResponseEntity<ApiResponse<Map<String, String>>> updated = getAdaptiveAuth();
        return ResponseEntity.status(updated.getStatusCode()).body(updated.getBody());
    }

    private static String defaultAdaptiveValue(String key) {
        return switch (key) {
            case AdaptiveAuthSettings.ENABLED -> "false";
            case AdaptiveAuthSettings.SCORE_STEP_UP ->
                    String.valueOf(AdaptiveAuthSettings.DEFAULT_STEP_UP);
            case AdaptiveAuthSettings.SCORE_BLOCK ->
                    String.valueOf(AdaptiveAuthSettings.DEFAULT_BLOCK);
            case AdaptiveAuthSettings.NOTIFY_NEW_DEVICE -> "true";
            case AdaptiveAuthSettings.FAIL_MODE -> AdaptiveAuthSettings.FAIL_MODE_OPEN;
            case AdaptiveAuthSettings.TRUST_DEVICE_DAYS ->
                    String.valueOf(AdaptiveAuthSettings.DEFAULT_TRUST_DAYS);
            case AdaptiveAuthSettings.NEW_IP_LOOKBACK_DAYS ->
                    String.valueOf(AdaptiveAuthSettings.DEFAULT_IP_LOOKBACK_DAYS);
            default -> "";
        };
    }

    /** Returns normalized value or null if invalid (enumeration / range resistance). */
    private static String validateAdaptiveValue(String key, String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return switch (key) {
            case AdaptiveAuthSettings.ENABLED, AdaptiveAuthSettings.NOTIFY_NEW_DEVICE ->
                    ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
                            ? value.toLowerCase() : null;
            case AdaptiveAuthSettings.FAIL_MODE ->
                    validateFailMode(value);
            case AdaptiveAuthSettings.SCORE_STEP_UP, AdaptiveAuthSettings.SCORE_BLOCK ->
                    parseBoundedInt(value, 0, 100);
            case AdaptiveAuthSettings.TRUST_DEVICE_DAYS, AdaptiveAuthSettings.NEW_IP_LOOKBACK_DAYS ->
                    parseBoundedInt(value, 1, 365);
            default -> null;
        };
    }

    private static String parseBoundedInt(String value, int min, int max) {
        try {
            int n = Integer.parseInt(value);
            if (n < min || n > max) {
                return null;
            }
            return Integer.toString(n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accept only known {@link RiskFailMode} names (reject unknown strings). */
    private static String validateFailMode(String value) {
        try {
            return RiskFailMode.valueOf(value.trim().toUpperCase()).name();
        } catch (Exception e) {
            return null;
        }
    }
}
