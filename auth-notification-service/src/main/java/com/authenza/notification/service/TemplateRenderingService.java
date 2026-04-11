package com.authenza.notification.service;

import com.authenza.common.dto.NotificationRequest;
import com.authenza.notification.domain.BrandingSettings;
import com.authenza.notification.repository.BrandingSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Optional;

@Service
public class TemplateRenderingService {

    private static final Logger log = LoggerFactory.getLogger(TemplateRenderingService.class);

    private final SpringTemplateEngine templateEngine;
    private final BrandingSettingsRepository brandingSettingsRepository;

    public TemplateRenderingService(SpringTemplateEngine templateEngine, BrandingSettingsRepository brandingSettingsRepository) {
        this.templateEngine = templateEngine;
        this.brandingSettingsRepository = brandingSettingsRepository;
    }

    public String renderHtmlContent(NotificationRequest request) {
        Context context = new Context();
        
        // Add variables from the request
        if (request.getTemplateVariables() != null) {
            context.setVariables(request.getTemplateVariables());
        }

        // Fetch and inject tenant branding
        try {
            Optional<BrandingSettings> brandingOpt = brandingSettingsRepository.findFirstByOrderByIdDesc();
            if (brandingOpt.isPresent()) {
                BrandingSettings branding = brandingOpt.get();
                context.setVariable("brand_logo", branding.getLogoUrl());
                context.setVariable("brand_primary_color", branding.getPrimaryColor());
                context.setVariable("brand_company_name", branding.getCompanyName());
                context.setVariable("brand_support_email", branding.getSupportEmail());
            } else {
                setFallbackBranding(context);
            }
        } catch (Exception e) {
            log.warn("Failed to load branding setting for tenant {}. Using defaults.", request.getTenantId(), e);
            setFallbackBranding(context);
        }

        // Select template based on notification type
        String templateName = getTemplateName(request);
        return templateEngine.process(templateName, context);
    }

    private void setFallbackBranding(Context context) {
        context.setVariable("brand_logo", ""); // No default logo
        context.setVariable("brand_primary_color", "#2563eb"); // Default blue
        context.setVariable("brand_company_name", "Authenza");
        context.setVariable("brand_support_email", "support@authenza.local");
    }

    private String getTemplateName(NotificationRequest request) {
        if (request.getContextType() == null) {
            return "generic-notification";
        }
        
        switch (request.getContextType()) {
            case VERIFICATION_EMAIL:
                return "verification-email";
            case PASSWORD_RESET:
                return "password-reset";
            case ADMIN_INVITE:
                return "user-invite";
            case SECURITY_ALERT:
                return "security-alert";
            default:
                return "generic-notification";
        }
    }
}
