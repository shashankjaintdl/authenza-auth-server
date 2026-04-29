package com.authenza.master.controller;

import com.authenza.common.constant.AuthenzaConstant;
import com.authenza.common.dto.TenantRequest;
import com.authenza.master.service.TenantProvisioningService;
import jakarta.validation.Valid;
import org.apache.coyote.Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(TenantRegistrationController.ENDPOINT)
public class TenantRegistrationController {

    public static final String ENDPOINT = AuthenzaConstant.API_VERSION+"/admin/tenant";

    private final TenantProvisioningService tenantProvisioningService;

    public TenantRegistrationController(TenantProvisioningService tenantProvisioningService) {
        this.tenantProvisioningService = tenantProvisioningService;
    }

    @PostMapping
    public ResponseEntity<String> register(@RequestBody @Valid TenantRequest request){
        try {
            tenantProvisioningService.onboardNewTenant(request);
            return ResponseEntity.ok("Tenant provisioned successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<?> getTenants(@RequestParam("ownerId") String ownerId) {
        return ResponseEntity.ok(tenantProvisioningService.getTenantsByOwner(ownerId));
    }

    @PostMapping("/{tenantId}/switch")
    public ResponseEntity<?> switchTenant(@PathVariable String tenantId) {
        // In a real app, you would verify the security context here
        // (i.e., that the current user has access to this tenantId)
        tenantProvisioningService.updateLastAccessed(tenantId);
        
        // This is where you would also trigger a token refresh
        return ResponseEntity.ok("Context switched to " + tenantId);
    }

    @PostMapping("/{tenantId}/default")
    public ResponseEntity<?> setDefaultTenant(@PathVariable String tenantId, @RequestParam("ownerId") String ownerId) {
        // Logic to clear other defaults and set this one as primary
        // (Need to implement this in service)
        return ResponseEntity.ok("Default tenant updated");
    }
}
