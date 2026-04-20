package com.authenza.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for brute-force login protection.
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code max-attempts} — lock account after this many consecutive failures (default: 5)</li>
 *   <li>{@code lock-duration-minutes} — how long the account stays locked (default: 15 minutes)</li>
 * </ul>
 *
 * Override in {@code application.yaml}:
 * <pre>
 * app:
 *   brute-force:
 *     max-attempts: 5
 *     lock-duration-minutes: 15
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app.brute-force")
public class BruteForceProperties {

    /** Number of consecutive failed login attempts before account is locked. */
    private int maxAttempts = 5;

    /** Duration in minutes for which the account remains locked after exceeding max attempts. */
    private int lockDurationMinutes = 15;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getLockDurationMinutes() {
        return lockDurationMinutes;
    }

    public void setLockDurationMinutes(int lockDurationMinutes) {
        this.lockDurationMinutes = lockDurationMinutes;
    }
}
