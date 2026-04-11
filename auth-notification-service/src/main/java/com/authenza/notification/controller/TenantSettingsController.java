package com.authenza.notification.controller;

import com.authenza.common.constant.AuthenzaConstant;
import com.authenza.notification.domain.BrandingSettings;
import com.authenza.notification.domain.SmtpSettings;
import com.authenza.notification.dto.BrandingSettingsDto;
import com.authenza.notification.dto.SmtpSettingsDto;
import com.authenza.common.dto.ApiResponse;
import com.authenza.notification.repository.BrandingSettingsRepository;
import com.authenza.notification.repository.SmtpSettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping(TenantSettingsController.ENDPOINT)
public class TenantSettingsController {

    public static final String ENDPOINT = AuthenzaConstant.API_VERSION + "/settings";

    private final BrandingSettingsRepository brandingRepository;
    private final SmtpSettingsRepository smtpRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantSettingsController(BrandingSettingsRepository brandingRepository, SmtpSettingsRepository smtpRepository, PasswordEncoder passwordEncoder) {
        this.brandingRepository = brandingRepository;
        this.smtpRepository = smtpRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // --- Branding Settings Endpoints ---

    @GetMapping("/branding")
    public ResponseEntity<ApiResponse<BrandingSettingsDto>> getBrandingSettings() {
        Optional<BrandingSettings> opt = brandingRepository.findFirstByOrderByIdDesc();
        if (opt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(new BrandingSettingsDto(), "Fetched default branding settings"));
        }
        BrandingSettings b = opt.get();
        return ResponseEntity.ok(ApiResponse.success(new BrandingSettingsDto(
                b.getLogoUrl(), b.getPrimaryColor(), b.getSecondaryColor(), b.getCompanyName(), b.getSupportEmail()
        ), "Fetched branding settings"));
    }

    @PutMapping("/branding")
    public ResponseEntity<ApiResponse<BrandingSettingsDto>> updateBrandingSettings(@RequestBody BrandingSettingsDto dto) {
        BrandingSettings branding = brandingRepository.findFirstByOrderByIdDesc().orElse(new BrandingSettings());
        
        branding.setLogoUrl(dto.getLogoUrl());
        branding.setPrimaryColor(dto.getPrimaryColor());
        branding.setSecondaryColor(dto.getSecondaryColor());
        branding.setCompanyName(dto.getCompanyName());
        branding.setSupportEmail(dto.getSupportEmail());

        BrandingSettings saved = brandingRepository.save(branding);

        BrandingSettingsDto responseDto = new BrandingSettingsDto(
                saved.getLogoUrl(), saved.getPrimaryColor(), saved.getSecondaryColor(), saved.getCompanyName(), saved.getSupportEmail()
        );

        return ResponseEntity.ok(ApiResponse.success(responseDto, "Branding settings updated successfully"));
    }

    @DeleteMapping("/branding")
    public ResponseEntity<ApiResponse<Void>> deleteBrandingSettings() {
        brandingRepository.deleteAll(); // Reset to system defaults
        return ResponseEntity.ok(ApiResponse.success(null, "Branding settings reset to defaults"));
    }

    // --- SMTP Settings Endpoints ---

    @GetMapping("/smtp")
    public ResponseEntity<ApiResponse<SmtpSettingsDto>> getSmtpSettings() {
        Optional<SmtpSettings> opt = smtpRepository.findFirstByOrderByIdDesc();
        if (opt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(new SmtpSettingsDto(), "Fetched default SMTP settings"));
        }
        SmtpSettings s = opt.get();
        SmtpSettingsDto dto = new SmtpSettingsDto();
        dto.setHost(s.getHost());
        dto.setPort(s.getPort());
        dto.setUsername(s.getUsername());
        dto.setProtocol(s.getProtocol());
        dto.setAuthEnabled(s.getAuthEnabled());
        dto.setStarttlsEnabled(s.getStarttlsEnabled());
        dto.setSenderEmail(s.getSenderEmail());
        dto.setSenderName(s.getSenderName());
        // For security, do not return the raw password in the GET request
        return ResponseEntity.ok(ApiResponse.success(dto, "Fetched SMTP settings"));
    }

    @PutMapping("/smtp")
    public ResponseEntity<ApiResponse<SmtpSettingsDto>> updateSmtpSettings(@RequestBody SmtpSettingsDto dto) {
        SmtpSettings smtp = smtpRepository.findFirstByOrderByIdDesc().orElse(new SmtpSettings());
        
        smtp.setHost(dto.getHost());
        smtp.setPort(dto.getPort());
        smtp.setUsername(dto.getUsername());
        
        // Only update password if one was provided in the request
        if (dto.getPassword() != null && !dto.getPassword().trim().isEmpty()) {
            smtp.setPassword(dto.getPassword());
        }
        
        smtp.setProtocol(dto.getProtocol());
        smtp.setAuthEnabled(dto.getAuthEnabled());
        smtp.setStarttlsEnabled(dto.getStarttlsEnabled());
        smtp.setSenderEmail(dto.getSenderEmail());
        smtp.setSenderName(dto.getSenderName());

        SmtpSettings saved = smtpRepository.save(smtp);

        SmtpSettingsDto responseDto = new SmtpSettingsDto();
        responseDto.setHost(saved.getHost());
        responseDto.setPort(saved.getPort());
        responseDto.setUsername(saved.getUsername());
        responseDto.setProtocol(saved.getProtocol());
        responseDto.setAuthEnabled(saved.getAuthEnabled());
        responseDto.setStarttlsEnabled(saved.getStarttlsEnabled());
        responseDto.setSenderEmail(saved.getSenderEmail());
        responseDto.setSenderName(saved.getSenderName());
        
        return ResponseEntity.ok(ApiResponse.success(responseDto, "SMTP settings updated successfully"));
    }

    @DeleteMapping("/smtp")
    public ResponseEntity<ApiResponse<Void>> deleteSmtpSettings() {
        smtpRepository.deleteAll(); // Reset to system defaults
        return ResponseEntity.ok(ApiResponse.success(null, "SMTP settings reset to defaults"));
    }
}
