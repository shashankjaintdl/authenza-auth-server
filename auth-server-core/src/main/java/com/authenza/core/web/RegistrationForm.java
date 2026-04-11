package com.authenza.core.web;

/**
 * Simple POJO backing the Thymeleaf registration form.
 *
 * <p>Validation is intentionally minimal here — the real validation
 * (email format, password strength, duplicate checks) is performed
 * by {@code auth-iam-service} to keep it as the single source of truth.
 * Only the confirmPassword match is validated locally since it is
 * never sent to the IAM service.</p>
 */
public class RegistrationForm {

    private String email;
    private String preferredUsername;
    private String givenName;
    private String familyName;
    private String password;
    private String confirmPassword;
    private boolean useEmailAsUsername;

    public RegistrationForm() {
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPreferredUsername() {
        return preferredUsername;
    }

    public void setPreferredUsername(String preferredUsername) {
        this.preferredUsername = preferredUsername;
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    public boolean isUseEmailAsUsername() {
        return useEmailAsUsername;
    }

    public void setUseEmailAsUsername(boolean useEmailAsUsername) {
        this.useEmailAsUsername = useEmailAsUsername;
    }
}
