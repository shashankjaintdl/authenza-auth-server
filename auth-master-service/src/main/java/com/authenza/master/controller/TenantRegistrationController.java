package com.authenza.master.controller;

import com.authenza.common.constant.AuthenzaConstant;
import com.authenza.common.dto.TenantDatabaseValidationRequest;
import com.authenza.common.dto.TenantDatabaseValidationResponse;
import com.authenza.common.dto.TenantRequest;
import com.authenza.common.dto.TenantSettingsResponse;
import com.authenza.common.dto.TenantSettingsUpdateRequest;
import com.authenza.common.exception.TenantDatabaseValidationException;
import com.authenza.master.service.TenantDatabasePreflightService;
import com.authenza.master.service.TenantProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping(TenantRegistrationController.ENDPOINT)
public class TenantRegistrationController {

    public static final String ENDPOINT = AuthenzaConstant.API_VERSION + "/admin/tenant";

    private final TenantProvisioningService tenantProvisioningService;
    private final TenantDatabasePreflightService preflightService;

    public TenantRegistrationController(
            TenantProvisioningService tenantProvisioningService,
            TenantDatabasePreflightService preflightService) {
        this.tenantProvisioningService = tenantProvisioningService;
        this.preflightService = preflightService;
    }

    @PostMapping
    public ResponseEntity<?> register(
            @RequestBody @Valid TenantRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        try {
            String tenantId = tenantProvisioningService.onboardNewTenant(request);
            // Preserve plain-text responses for older scripts/Postman that expect the legacy body.
            if (accept != null && accept.contains(MediaType.TEXT_PLAIN_VALUE) && !accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Tenant provisioned successfully");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("status", "PROVISIONED");
            body.put("dbType", request.dbType() != null ? request.dbType().getValue() : "MYSQL");
            body.put("message", "Tenant provisioned successfully");
            return ResponseEntity.ok(body);
        } catch (TenantDatabaseValidationException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("valid", false);
            body.put("errorCode", e.getErrorCode());
            body.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Non-mutating BYOD connectivity check. Does not create master rows or run Liquibase.
     */
    @PostMapping("/validate-db")
    public ResponseEntity<TenantDatabaseValidationResponse> validateDb(
            @RequestBody TenantDatabaseValidationRequest request) {
        TenantDatabaseValidationResponse response = preflightService.validate(request);
        if (response.valid()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @GetMapping
    public ResponseEntity<?> getTenants(@RequestParam("ownerId") String ownerId) {
        return ResponseEntity.ok(tenantProvisioningService.getTenantsByOwner(ownerId));
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<?> getTenantSettings(
            @PathVariable String tenantId,
            @RequestParam("ownerId") String ownerId) {
        try {
            return ResponseEntity.ok(tenantProvisioningService.getTenantSettings(tenantId, ownerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{tenantId}")
    public ResponseEntity<?> updateTenantSettings(
            @PathVariable String tenantId,
            @RequestBody TenantSettingsUpdateRequest request) {
        try {
            TenantSettingsResponse updated = tenantProvisioningService.updateTenantSettings(tenantId, request);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{tenantId}/deactivate")
    public ResponseEntity<?> deactivateTenant(
            @PathVariable String tenantId,
            @RequestParam("ownerId") String ownerId) {
        try {
            return ResponseEntity.ok(tenantProvisioningService.deactivateTenant(tenantId, ownerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{tenantId}/activate")
    public ResponseEntity<?> activateTenant(
            @PathVariable String tenantId,
            @RequestParam("ownerId") String ownerId) {
        try {
            return ResponseEntity.ok(tenantProvisioningService.activateTenant(tenantId, ownerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{tenantId}/switch")
    public ResponseEntity<?> switchTenant(@PathVariable String tenantId) {
        // Existing switcher behavior preserved — update last-accessed only.
        tenantProvisioningService.updateLastAccessed(tenantId);
        return ResponseEntity.ok("Context switched to " + tenantId);
    }

    @PostMapping("/{tenantId}/default")
    public ResponseEntity<?> setDefaultTenant(
            @PathVariable String tenantId,
            @RequestParam("ownerId") String ownerId) {
        try {
            return ResponseEntity.ok(tenantProvisioningService.setDefaultTenant(tenantId, ownerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }
}
