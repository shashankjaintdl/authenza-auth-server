package com.authenza.iam.dto;

import java.time.Instant;

/**
 * DTO returned by the Active Session Management API.
 * Represents a single active login session for a user.
 */
public class SessionResponse {

    private Long id;

    /** Human-readable label e.g. "Chrome on macOS", "Firefox on Windows". */
    private String deviceName;

    /** Client IP address (may be IPv4 or IPv6). */
    private String ipAddress;

    /** Raw User-Agent string — useful for advanced clients that want to parse it further. */
    private String userAgent;

    /** Timestamp when this session was created (login time). */
    private Instant createdAt;

    /** Timestamp of the most recent activity recorded for this session. */
    private Instant lastActiveAt;

    /** {@code true} if this is the session making the current API request. */
    private boolean current;

    public SessionResponse() {
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
}
