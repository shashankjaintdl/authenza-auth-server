package com.authenza.notification.listener;

import com.authenza.common.dto.NotificationRequest;
import com.authenza.common.events.EmailVerificationEvent;
import com.authenza.notification.service.NotificationService;
import com.authenza.adapter.context.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    @Value("${authenza.iam.base-url:http://localhost:8081}")
    private String iamBaseUrl;

    public NotificationEventListener(ObjectMapper objectMapper, NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    public void handleMessage(String message) {
        log.info("Received raw notification message from Redis: {}", message);
        try {
            NotificationRequest request = objectMapper.readValue(message, NotificationRequest.class);
            
            if (request.getTenantId() == null) {
                log.warn("Received notification request with null tenantId. Ignoring.");
                return;
            }

            // Important: Set the TenantContext so any repository calls (like fetching SMTP settings)
            // within the NotificationService flow are routed to the correct tenant database.
            TenantContextHolder.setTenantId(request.getTenantId());

            try {
                notificationService.sendNotification(request);
            } finally {
                // Always clear context to avoid memory leaks or threading issues
                TenantContextHolder.clear();
            }

        } catch (Exception e) {
            log.error("Error processing notification message", e);
        }
    }

    public void handleEmailVerification(String message) {
        try {
            log.info("Received email verification event: {}", message);
            EmailVerificationEvent event = objectMapper.readValue(message, EmailVerificationEvent.class);

            // 1. Set the database routing context for this tenant
            TenantContextHolder.setTenantId(event.tenantId());

            // 2. Build the verification link
            // In a production system, you would store tenant Base URLs in a central table.
            NotificationRequest request = getNotificationRequest(event);

            // 4. Send! (The NotificationService and TemplateRenderingService will automatically
            // query the database for the Tenant Branding Settings and SMTP configuration)
            notificationService.sendNotification(request);

        } catch (Exception e){
            log.error("Failed to process email verification event", e);
        } finally {
            // Always clean up the ThreadLocal to prevent memory leaks cross-tenant contamination
            TenantContextHolder.clear();
        }
    }

    private NotificationRequest getNotificationRequest(EmailVerificationEvent event) {
        String verificationLink = iamBaseUrl + "/"+ event.tenantId()+"/verify-email?token=" + event.token();

        // 3. Prepare the notification request
        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("name", event.recipientName());
        variables.put("verification_link", verificationLink);

        NotificationRequest request = new NotificationRequest();
        request.setTenantId(event.tenantId());
        request.setContextType(com.authenza.common.enums.NotificationType.VERIFICATION_EMAIL);
        request.setTargetEmail(event.recipientEmail());
        request.setTemplateVariables(variables);
        return request;
    }


}
