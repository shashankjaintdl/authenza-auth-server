package com.authenza.iam.controller;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.constant.AuthenzaConstant;
import com.authenza.common.dto.ApiResponse;
import com.authenza.iam.dto.*;
import com.authenza.iam.service.WebAuthnService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing the FIDO2 / WebAuthn endpoints.
 *
 * <p>Access Matrix:</p>
 * <pre>
 * ┌─────────────────────────────────────────────────────────┬────────────────┐
 * │ Endpoint                                                │ Auth Required? │
 * ├─────────────────────────────────────────────────────────┼────────────────┤
 * │ POST /webauthn/authenticate/start                       │ No             │
 * │ POST /webauthn/authenticate/finish                      │ No             │
 * │ POST /users/{userId}/passkeys/register/start            │ Yes (own user) │
 * │ POST /users/{userId}/passkeys/register/finish           │ Yes (own user) │
 * │ GET  /users/{userId}/passkeys                           │ Yes (own user) │
 * │ DELETE /users/{userId}/passkeys/{passkeyId}             │ Yes (own user) │
 * └─────────────────────────────────────────────────────────┴────────────────┘
 * </pre>
 *
 * <p><b>Important — Two separate URL prefixes:</b>
 * <ul>
 *   <li>{@code /webauthn/**} — Unauthenticated endpoints used by the login page to
 *       initiate and complete a passwordless login attempt.</li>
 *   <li>{@code /users/{userId}/passkeys/**} — Authenticated endpoints used by the
 *       Tenant Portal's "Security" tab to add or remove passkeys from a logged-in account.</li>
 * </ul>
 * </p>
 */
@RestController
public class WebAuthnController {

    private final WebAuthnService webAuthnService;

    public WebAuthnController(WebAuthnService webAuthnService) {
        this.webAuthnService = webAuthnService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passwordless Login (Unauthenticated — called from Auth Server login page)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Step 1 of passwordless login: generates a WebAuthn assertion challenge.
     * The browser's {@code navigator.credentials.get()} call uses the response to
     * locate the correct passkey and prompt the biometric dialog.
     *
     * <p>Tenant context is set by {@code TenantValidationInterceptor} from the
     * {@code X-Tenant-ID} request header before this method is called.</p>
     *
     * @param body JSON: {@code { "username": "user@example.com" }}
     */
    @PostMapping("/webauthn/authenticate/start")
    public ResponseEntity<ApiResponse<WebAuthnRegistrationStartResponse>> startAuthentication(
            @RequestBody Map<String, String> body) throws Exception {
        String username = body.get("username");
        WebAuthnRegistrationStartResponse response = webAuthnService.startAuthentication(username);
        return ResponseEntity.ok(ApiResponse.success(response, "WebAuthn authentication challenge generated."));
    }

    /**
     * Step 2 of passwordless login: verifies the signed assertion from the browser.
     * Returns a signed bridge token that the login page POSTs to auth-server-core
     * to complete the OIDC session.
     *
     * <p>Tenant context is set by {@code TenantValidationInterceptor} from the
     * {@code X-Tenant-ID} request header. The tenantId is read from
     * {@link TenantContextHolder} to include in the bridge token.</p>
     */
    @PostMapping("/webauthn/authenticate/finish")
    public ResponseEntity<ApiResponse<Map<String, Object>>> finishAuthentication(
            @Valid @RequestBody WebAuthnAuthenticationFinishRequest request) throws Exception {
        // tenantId is already validated and set by TenantValidationInterceptor
        String tenantId = TenantContextHolder.getTenantId();
        WebAuthnService.WebAuthnAuthResult result = webAuthnService.finishAuthentication(request, tenantId);
        Map<String, Object> responseBody = Map.of(
                "userId", result.userId(),
                "tenantId", result.tenantId(),
                "bridgeToken", result.bridgeToken()
        );
        return ResponseEntity.ok(ApiResponse.success(responseBody, "Passkey authentication successful."));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passkey Registration (Authenticated — called from Tenant Portal Security Tab)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Step 1 of passkey registration: generates a {@code PublicKeyCredentialCreationOptions}
     * JSON object. The browser passes this to {@code navigator.credentials.create()} which
     * triggers Touch ID / Face ID / Windows Hello to generate a new key pair on the device.
     *
     * @param userId the database ID of the currently authenticated user
     */
    @PostMapping(AuthenzaConstant.API_VERSION + "/users/{userId}/passkeys/register/start")
    public ResponseEntity<ApiResponse<WebAuthnRegistrationStartResponse>> startRegistration(
            @PathVariable Long userId,
            @RequestBody(required = false) Map<String, String> body) throws Exception {
        String attachment = body != null ? body.get("attachment") : null;
        WebAuthnRegistrationStartResponse response = webAuthnService.startRegistration(userId, attachment);
        return ResponseEntity.ok(ApiResponse.success(response, "Registration challenge generated."));
    }

    /**
     * Step 2 of passkey registration: verifies the browser's signed attestation and stores
     * the public key credential in the database.
     *
     * @param userId  the database ID of the authenticated user
     * @param request the credential JSON returned by the browser and the requestId
     */
    @PostMapping(AuthenzaConstant.API_VERSION + "/users/{userId}/passkeys/register/finish")
    public ResponseEntity<ApiResponse<String>> finishRegistration(
            @PathVariable Long userId,
            @Valid @RequestBody WebAuthnRegistrationFinishRequest request) throws Exception {
        webAuthnService.finishRegistration(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Passkey registered successfully. You can now use it to sign in."));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passkey Management (Authenticated — Tenant Portal Security Tab)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists all passkeys registered to the given user.
     * Used to populate the "Manage Passkeys" list in the Security settings tab.
     */
    @GetMapping(AuthenzaConstant.API_VERSION + "/users/{userId}/passkeys")
    public ResponseEntity<ApiResponse<List<PasskeyResponse>>> listPasskeys(
            @PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(
                webAuthnService.listPasskeys(userId), "Passkeys retrieved successfully."));
    }

    /**
     * Permanently removes a specific passkey from the user's account.
     * After deletion, the physical device can no longer be used to sign in.
     *
     * @param userId     the owner's database ID (ownership is enforced in the service)
     * @param passkeyId  the database PK of the {@code webauthn_credential} row to delete
     */
    @DeleteMapping(AuthenzaConstant.API_VERSION + "/users/{userId}/passkeys/{passkeyId}")
    public ResponseEntity<ApiResponse<String>> deletePasskey(
            @PathVariable Long userId,
            @PathVariable Long passkeyId) {
        webAuthnService.deletePasskey(userId, passkeyId);
        return ResponseEntity.ok(ApiResponse.noContent("Passkey removed successfully."));
    }

    /**
     * Renames a passkey's display name (e.g. "MacBook Touch ID" → "My Work Laptop").
     * The display name is purely cosmetic and has no effect on authentication.
     *
     * @param userId     the owner's database ID
     * @param passkeyId  the database PK of the {@code webauthn_credential} row to rename
     * @param body       JSON body with a {@code displayName} string field
     */
    @PatchMapping(AuthenzaConstant.API_VERSION + "/users/{userId}/passkeys/{passkeyId}")
    public ResponseEntity<ApiResponse<String>> renamePasskey(
            @PathVariable Long userId,
            @PathVariable Long passkeyId,
            @RequestBody Map<String, String> body) {
        String displayName = body.get("displayName");
        if (displayName == null || displayName.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(404,"displayName must not be blank."));
        }
        webAuthnService.renamePasskey(userId, passkeyId, displayName.trim());
        return ResponseEntity.ok(ApiResponse.success("Passkey renamed successfully."));
    }
}
