package com.authenza.common.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Redis pub/sub payload for new-device / high-risk login security emails.
 */
public record SecurityAlertEvent(
        String tenantId,
        String contextType,
        String recipientEmail,
        String recipientName,
        String deviceName,
        String ipAddress,
        String alertKind
) {
    @JsonCreator
    public SecurityAlertEvent(
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("contextType") String contextType,
            @JsonProperty("recipientEmail") String recipientEmail,
            @JsonProperty("recipientName") String recipientName,
            @JsonProperty("deviceName") String deviceName,
            @JsonProperty("ipAddress") String ipAddress,
            @JsonProperty("alertKind") String alertKind) {
        this.tenantId = tenantId;
        this.contextType = contextType;
        this.recipientEmail = recipientEmail;
        this.recipientName = recipientName;
        this.deviceName = deviceName;
        this.ipAddress = ipAddress;
        this.alertKind = alertKind;
    }
}
