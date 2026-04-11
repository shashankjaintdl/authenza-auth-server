package com.authenza.notification.service;

import com.authenza.common.dto.NotificationRequest;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final DynamicMailSenderFactory mailSenderFactory;
    private final TemplateRenderingService templateRenderingService;

    public NotificationServiceImpl(DynamicMailSenderFactory mailSenderFactory, TemplateRenderingService templateRenderingService) {
        this.mailSenderFactory = mailSenderFactory;
        this.templateRenderingService = templateRenderingService;
    }

    @Override
    public void sendNotification(NotificationRequest request) {
        log.info("Preparing to send {} notification to {}", request.getContextType(), request.getTargetEmail());

        try {
            // 1. Get the correct JavaMailSender for this tenant
            JavaMailSender mailSender = mailSenderFactory.getMailSender(request.getTenantId());

            // 2. Render the HTML content using Thymeleaf + Tenant Branding
            String htmlContent = templateRenderingService.renderHtmlContent(request);

            // 3. Construct the email
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setTo(request.getTargetEmail());
            helper.setSubject(resolveSubject(request));
            helper.setText(htmlContent, true); // true = html

            // TODO: Resolve sender from SmtpSettings dynamically
            helper.setFrom("no-reply@authenza.local");

            // 4. Send the email
            mailSender.send(message);
            log.info("Successfully sent {} notification to {}", request.getContextType(), request.getTargetEmail());
            
        } catch (Exception e) {
            log.error("Failed to send notification to {}", request.getTargetEmail(), e);
            // In a production system, we might want to throw a custom exception here 
            // and have the Redis listener put the message in a Dead Letter Queue (DLQ)
        }
    }

    private String resolveSubject(NotificationRequest request) {
        if (request.getContextType() == null) {
            return "Notification from Authenza";
        }
        switch (request.getContextType()) {
            case VERIFICATION_EMAIL:
                return "Verify your email address";
            case PASSWORD_RESET:
                return "Password Reset Request";
            case ADMIN_INVITE:
                return "You have been invited to join a workspace";
            case SECURITY_ALERT:
                return "Security Alert: New sign-in detected";
            default:
                return "Notification from Authenza";
        }
    }
}
