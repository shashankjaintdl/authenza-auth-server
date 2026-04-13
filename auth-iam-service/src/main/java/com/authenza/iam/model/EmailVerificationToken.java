package com.authenza.iam.model;

import com.authenza.common.enums.VerificationTokenType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("email_verification_token")
public class EmailVerificationToken {

    @Id
    private Long id;
    private Long userId;
    private String token;
    private VerificationTokenType tokenType;    // EMAIL_VERIFICATION, PASSWORD_RESET
    private Instant expiresAt;
    private Instant createdAt;

    // Getters, setters...

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public VerificationTokenType getTokenType() {
        return tokenType;
    }

    public void setTokenType(VerificationTokenType tokenType) {
        this.tokenType = tokenType;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
