package com.authenza.common.model.iam;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.authenza.common.enums.UserStatus;

@Table("application_user")
public class User{

    // ══════════════════════════════════════════
    // OIDC Standard Claims (Section 5.1)
    // https://openid.net/specs/openid-connect-core-1_0.html#StandardClaims
    // ══════════════════════════════════════════
    @Id
    private Long id;                    // maps to "sub" claim
    private String preferredUsername;    // maps to "preferred_username"
    private String name;                // Full display name
    private String givenName;           // First name
    private String familyName;          // Last name
    private String email;
    private Boolean emailVerified;
    private String phoneNumber;
    private Boolean phoneNumberVerified;
    private String picture;             // Profile image URL
    private String locale;              // e.g., "en-IN"
    private String zoneinfo;            // e.g., "Asia/Kolkata"
    private String gender;
    private String birthdate;           // OIDC spec says String format: "YYYY-MM-DD"

    // ══════════════════════════════════════════
    // Internal Authentication (NOT exposed as OIDC claims)
    // ══════════════════════════════════════════
    private String password;              // BCrypt hashed
    private UserStatus status;             // ACTIVE, DISABLED, LOCKED
    private Integer failedLoginAttempts;
    private Instant lockedUntil;
    private Instant passwordChangedAt;
    private Boolean mfaEnabled;
    /**
     * AES-256 encrypted, base32-encoded TOTP secret.
     * NULL until the user initiates MFA setup.
     * Present but {@code mfaEnabled=false} means setup was started but never confirmed.
     */
    private String mfaSecret;

    // ══════════════════════════════════════════
    // Audit
    // ══════════════════════════════════════════
    private Instant createdAt;
    private Instant updatedAt;             // OIDC "updated_at" claim
    private Instant lastLoginAt;
    private String lastLoginIp;

    // ══════════════════════════════════════════
    // Relationships
    // ══════════════════════════════════════════
    @MappedCollection(idColumn = "user_id")
    private Set<UserRoleRef> roles = new HashSet<>();

    // Inner class
    @Table("user_role")
    public static class UserRoleRef {
        private Long roleId;

        public Long getRoleId() {
            return roleId;
        }

        public void setRoleId(Long roleId) {
            this.roleId = roleId;
        }
    }

    // Getters and Setters...

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPreferredUsername() {
        return preferredUsername;
    }

    public void setPreferredUsername(String preferredUsername) {
        this.preferredUsername = preferredUsername;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGivenName() {
        return givenName;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(Boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Boolean getPhoneNumberVerified() {
        return phoneNumberVerified;
    }

    public void setPhoneNumberVerified(Boolean phoneNumberVerified) {
        this.phoneNumberVerified = phoneNumberVerified;
    }

    public String getPicture() {
        return picture;
    }

    public void setPicture(String picture) {
        this.picture = picture;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getZoneinfo() {
        return zoneinfo;
    }

    public void setZoneinfo(String zoneinfo) {
        this.zoneinfo = zoneinfo;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getBirthdate() {
        return birthdate;
    }

    public void setBirthdate(String birthdate) {
        this.birthdate = birthdate;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Integer getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(Integer failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(Instant passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public Boolean getMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(Boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public void setMfaSecret(String mfaSecret) {
        this.mfaSecret = mfaSecret;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getLastLoginIp() {
        return lastLoginIp;
    }

    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
    }

    public Set<UserRoleRef> getRoles() {
        return roles;
    }

    public void setRoles(Set<UserRoleRef> roles) {
        this.roles = roles;
    }
}

