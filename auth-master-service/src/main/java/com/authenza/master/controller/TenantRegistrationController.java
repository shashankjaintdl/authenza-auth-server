package com.authenza.master.controller;

import com.authenza.common.dto.TenantRequest;
import com.authenza.master.service.TenantProvisioningService;
import jakarta.validation.Valid;
import org.apache.coyote.Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(TenantRegistrationController.ENDPOINT)
public class TenantRegistrationController {

    public static final String ENDPOINT = "/admin/tenant";

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
}
