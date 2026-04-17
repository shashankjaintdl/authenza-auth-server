package com.authenza.notification.listener;

import com.authenza.common.dto.NotificationRequest;
import com.authenza.common.events.AdminInviteEvent;
import com.authenza.common.events.EmailVerificationEvent;
import com.authenza.common.events.PasswordResetEvent;
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
            NotificationRequest request = getVerificationNotificationRequest(event);

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

    /**
     * Handles password reset events published by auth-iam-service.
     * Builds the reset link and delegates to the notification service
     * to send the branded password-reset email.
     */
    public void handlePasswordReset(String message) {
        try {
            log.info("Received password reset event: {}", message);
            PasswordResetEvent event = objectMapper.readValue(message, PasswordResetEvent.class);

            // Set the database routing context for this tenant
            TenantContextHolder.setTenantId(event.tenantId());

            NotificationRequest request = getPasswordResetNotificationRequest(event);
            notificationService.sendNotification(request);

        } catch (Exception e) {
            log.error("Failed to process password reset event", e);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * Handles admin invitation events published by auth-iam-service.
     * Builds the invitation link and delegates to the notification service
     * to send the branded invitation email.
     */
    public void handleAdminInvite(String message) {
        try {
            log.info("Received admin invite event: {}", message);
            AdminInviteEvent event = objectMapper.readValue(message, AdminInviteEvent.class);

            // Set the database routing context for this tenant
            TenantContextHolder.setTenantId(event.tenantId());

            NotificationRequest request = getAdminInviteNotificationRequest(event);
            notificationService.sendNotification(request);

        } catch (Exception e) {
            log.error("Failed to process admin invite event", e);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private NotificationRequest getVerificationNotificationRequest(EmailVerificationEvent event) {
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

    private NotificationRequest getPasswordResetNotificationRequest(PasswordResetEvent event) {
        String resetLink = iamBaseUrl + "/" + event.tenantId() + "/reset-password?token=" + event.token();

        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("name", event.recipientName());
        variables.put("reset_link", resetLink);

        NotificationRequest request = new NotificationRequest();
        request.setTenantId(event.tenantId());
        request.setContextType(com.authenza.common.enums.NotificationType.PASSWORD_RESET);
        request.setTargetEmail(event.recipientEmail());
        request.setTemplateVariables(variables);
        return request;
    }

    private NotificationRequest getAdminInviteNotificationRequest(AdminInviteEvent event) {
        // The URL pattern usually directs to a page where the user can set their password.
        // For simplicity, we are appending the token. 
        String inviteLink = iamBaseUrl + "/" + event.tenantId() + "/accept-invite?token=" + event.token();

        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("name", event.recipientName());
        variables.put("inviter_name", event.invitedByName());
        variables.put("invite_link", inviteLink);

        NotificationRequest request = new NotificationRequest();
        request.setTenantId(event.tenantId());
        request.setContextType(com.authenza.common.enums.NotificationType.ADMIN_INVITE);
        request.setTargetEmail(event.recipientEmail());
        request.setTemplateVariables(variables);
        return request;
    }

}
