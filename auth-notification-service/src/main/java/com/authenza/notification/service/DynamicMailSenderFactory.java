package com.authenza.notification.service;

import com.authenza.notification.domain.SmtpSettings;
import com.authenza.notification.repository.SmtpSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicMailSenderFactory {

    private static final Logger log = LoggerFactory.getLogger(DynamicMailSenderFactory.class);

    private final SmtpSettingsRepository smtpSettingsRepository;
    // Cache for tenant mail senders so we don't recreate them every time
    private final ConcurrentHashMap<String, JavaMailSender> tenantMailSenders = new ConcurrentHashMap<>();

    // Keep a default system mail sender injected if available, or initialize one
    private final JavaMailSender systemDefaultMailSender;

    public DynamicMailSenderFactory(SmtpSettingsRepository smtpSettingsRepository,
            @Nullable JavaMailSender systemDefaultMailSender) {
        this.smtpSettingsRepository = smtpSettingsRepository;
        this.systemDefaultMailSender = systemDefaultMailSender;
    }

    public JavaMailSender getMailSender(String tenantId) {
        if (tenantId == null) {
            return systemDefaultMailSender;
        }

        // We assume TenantContext is already set by the caller (or the listener)
        // so smtpSettingsRepository will query the correct schema.
        try {
            Optional<SmtpSettings> settingsOpt = smtpSettingsRepository.findFirstByOrderByIdDesc();

            if (settingsOpt.isPresent()) {
                SmtpSettings settings = settingsOpt.get();
                // To support dynamic updates, optionally clear cache or compare hash.
                // For MVP, we cache it once per tenant.
                return tenantMailSenders.computeIfAbsent(tenantId, k -> createJavaMailSender(settings));
            }
        } catch (Exception e) {
            log.warn("Failed to load SMTP settings for tenant: {}. Falling back to system default.", tenantId, e);
        }

        return systemDefaultMailSender;
    }

    private JavaMailSender createJavaMailSender(SmtpSettings settings) {
        log.info("Creating custom JavaMailSender for tenant based on DB config");
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(settings.getHost());
        mailSender.setPort(settings.getPort() != null ? settings.getPort() : 587);

        if (settings.getUsername() != null && !settings.getUsername().isEmpty()) {
            mailSender.setUsername(settings.getUsername());
        }
        if (settings.getPassword() != null && !settings.getPassword().isEmpty()) {
            mailSender.setPassword(settings.getPassword());
        }

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", settings.getProtocol() != null ? settings.getProtocol() : "smtp");
        props.put("mail.smtp.auth", settings.getAuthEnabled() != null ? settings.getAuthEnabled().toString() : "true");
        props.put("mail.smtp.starttls.enable",
                settings.getStarttlsEnabled() != null ? settings.getStarttlsEnabled().toString() : "true");

        return mailSender;
    }
}
