package com.authenza.common.dto;

import com.authenza.common.enums.NotificationType;

import java.io.Serializable;
import java.util.Map;

public class NotificationRequest implements Serializable {

    private String targetEmail;
    private String tenantId;
    private NotificationType contextType;
    private Map<String, Object> templateVariables;

    public NotificationRequest() {
    }

    public NotificationRequest(String targetEmail, String tenantId, NotificationType contextType, Map<String, Object> templateVariables) {
        this.targetEmail = targetEmail;
        this.tenantId = tenantId;
        this.contextType = contextType;
        this.templateVariables = templateVariables;
    }

    public String getTargetEmail() {
        return targetEmail;
    }

    public void setTargetEmail(String targetEmail) {
        this.targetEmail = targetEmail;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public NotificationType getContextType() {
        return contextType;
    }

    public void setContextType(NotificationType contextType) {
        this.contextType = contextType;
    }

    public Map<String, Object> getTemplateVariables() {
        return templateVariables;
    }

    public void setTemplateVariables(Map<String, Object> templateVariables) {
        this.templateVariables = templateVariables;
    }
}
