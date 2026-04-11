package com.authenza.notification.dto;

public class SmtpSettingsDto {

    private String host;
    private Integer port;
    private String username;
    private String password;
    private String protocol;
    private Boolean authEnabled;
    private Boolean starttlsEnabled;
    private String senderEmail;
    private String senderName;

    public SmtpSettingsDto() {}

    // Getters and Setters
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public Boolean getAuthEnabled() { return authEnabled; }
    public void setAuthEnabled(Boolean authEnabled) { this.authEnabled = authEnabled; }

    public Boolean getStarttlsEnabled() { return starttlsEnabled; }
    public void setStarttlsEnabled(Boolean starttlsEnabled) { this.starttlsEnabled = starttlsEnabled; }

    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
}
