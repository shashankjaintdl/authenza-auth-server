package com.authenza.iam.controller;

import com.authenza.common.constant.AuthenzaConstant;
import com.authenza.common.dto.ApiResponse;
import com.authenza.iam.dto.TrustedDeviceResponse;
import com.authenza.iam.service.TrustedDeviceAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Trusted devices API for Adaptive Auth Phase A.
 *
 * <pre>
 *   GET    /api/v1/users/{userId}/trusted-devices
 *   DELETE /api/v1/users/{userId}/trusted-devices/{deviceId}
 * </pre>
 */
@RestController
@RequestMapping(AuthenzaConstant.API_VERSION + "/users/{userId}/trusted-devices")
public class TrustedDeviceController {

    private final TrustedDeviceAdminService trustedDeviceAdminService;

    public TrustedDeviceController(TrustedDeviceAdminService trustedDeviceAdminService) {
        this.trustedDeviceAdminService = trustedDeviceAdminService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TrustedDeviceResponse>>> list(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(
                trustedDeviceAdminService.listActive(userId),
                "Trusted devices retrieved successfully."));
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<ApiResponse<?>> revoke(@PathVariable Long userId,
                                                 @PathVariable Long deviceId) {
        boolean revoked = trustedDeviceAdminService.revoke(userId, deviceId);
        if (!revoked) {
            return ResponseEntity.status(404).body(
                    ApiResponse.error(404, "Trusted device not found or already revoked."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("id", deviceId, "revoked", true),
                "Trusted device revoked."));
    }
}
