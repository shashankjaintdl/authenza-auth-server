package com.authenza.core.web;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.dto.ApiResponse;
import com.authenza.core.client.IamServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST proxy in auth-server-core that forwards MFA management requests
 * to auth-iam-service. This lets callers interact with a single host
 * (auth-server-core, port 8081) without needing direct access to the
 * IAM service on port 8082.
 *
 * <p>All endpoints are under {@code /{tenantId}/api/v1/users/{userId}/mfa/...}
 * and are already permitted without authentication via SecurityConfig
 * ({@code /{tenantId}/api/**}).
 *
 * <h3>Usage from Postman / curl (all via port 8081)</h3>
 * <pre>
 *   POST /{tenantId}/api/v1/users/{userId}/mfa/setup
 *   POST /{tenantId}/api/v1/users/{userId}/mfa/confirm   body: {"code":"123456"}
 *   POST /{tenantId}/api/v1/users/{userId}/mfa/disable   body: {"code":"123456"}
 * </pre>
 */
@RestController
public class MfaProxyController {

    private static final Logger log = LoggerFactory.getLogger(MfaProxyController.class);

    private final IamServiceClient iamClient;

    public MfaProxyController(IamServiceClient iamClient) {
        this.iamClient = iamClient;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup — generate TOTP secret + QR code
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates MFA setup for the given user.
     * Generates an encrypted TOTP secret and returns a QR code data URI
     * and raw base32 secret for manual entry.
     *
     * <p>MFA is NOT yet active after this call — the user must call
     * {@code /mfa/confirm} with a valid code to activate it.
     *
     * @param tenantId the tenant scope
     * @param userId   the user's database ID
     */
    @PostMapping("/{tenantId}/api/v1/users/{userId}/mfa/setup")
    public ResponseEntity<ApiResponse<?>> setupMfa(
            @PathVariable String tenantId,
            @PathVariable Long userId) {

        TenantContextHolder.setTenantId(tenantId);
        log.info("[MFA-PROXY] Setup requested for user {} in tenant '{}'", userId, tenantId);
        ApiResponse<?> response = iamClient.setupMfa(tenantId, userId);
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confirm — verify first code, activate MFA
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Confirms MFA enrollment by verifying the first TOTP code entered by the user.
     * Sets {@code mfa_enabled = true} if the code is valid.
     *
     * @param body JSON body: {@code { "code": "123456" }}
     */
    @PostMapping("/{tenantId}/api/v1/users/{userId}/mfa/confirm")
    public ResponseEntity<ApiResponse<?>> confirmMfa(
            @PathVariable String tenantId,
            @PathVariable Long userId,
            @RequestBody Map<String, String> body) {

        TenantContextHolder.setTenantId(tenantId);
        String code = body.getOrDefault("code", "").replaceAll("\\s", "");
        log.info("[MFA-PROXY] Confirm requested for user {} in tenant '{}'", userId, tenantId);
        ApiResponse<?> response = iamClient.confirmMfa(tenantId, userId, code);
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Disable — turn off MFA after verifying code
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Disables MFA for the given user after verifying their current TOTP code.
     * Clears both the secret and the enabled flag.
     *
     * @param body JSON body: {@code { "code": "123456" }}
     */
    @PostMapping("/{tenantId}/api/v1/users/{userId}/mfa/disable")
    public ResponseEntity<ApiResponse<?>> disableMfa(
            @PathVariable String tenantId,
            @PathVariable Long userId,
            @RequestBody Map<String, String> body) {

        TenantContextHolder.setTenantId(tenantId);
        String code = body.getOrDefault("code", "").replaceAll("\\s", "");
        log.info("[MFA-PROXY] Disable requested for user {} in tenant '{}'", userId, tenantId);
        ApiResponse<?> response = iamClient.disableMfa(tenantId, userId, code);
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tenant Settings — MFA policy management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all tenant settings.
     * {@code GET /{tenantId}/api/v1/settings}
     */
    @GetMapping("/{tenantId}/api/v1/settings")
    public ResponseEntity<ApiResponse<?>> getSettings(@PathVariable String tenantId) {
        TenantContextHolder.setTenantId(tenantId);
        return ResponseEntity.ok(iamClient.getTenantSettings(tenantId));
    }

    /**
     * Returns the current tenant-wide MFA enforcement policy.
     * {@code GET /{tenantId}/api/v1/settings/mfa-policy}
     */
    @GetMapping("/{tenantId}/api/v1/settings/mfa-policy")
    public ResponseEntity<ApiResponse<?>> getMfaPolicy(@PathVariable String tenantId) {
        TenantContextHolder.setTenantId(tenantId);
        return ResponseEntity.ok(iamClient.getMfaPolicy(tenantId));
    }

    /**
     * Enables or disables MFA for all users in this tenant.
     * {@code PUT /{tenantId}/api/v1/settings/mfa-policy}
     *
     * <p>Request body: {@code { "mfaRequiredForAll": true }}
     */
    @PutMapping("/{tenantId}/api/v1/settings/mfa-policy")
    public ResponseEntity<ApiResponse<?>> setMfaPolicy(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body) {

        TenantContextHolder.setTenantId(tenantId);
        Object value = body.get("mfaRequiredForAll");
        boolean required = value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        log.info("[SETTINGS-PROXY] mfa_required_for_all={} for tenant '{}'", required, tenantId);
        return ResponseEntity.ok(iamClient.setMfaPolicy(tenantId, required));
    }
}
