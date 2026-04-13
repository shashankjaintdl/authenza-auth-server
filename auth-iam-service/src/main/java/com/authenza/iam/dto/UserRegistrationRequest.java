package com.authenza.iam.dto;

/**
 * Data Transfer Object for incoming user registration requests.
 */
public class UserRegistrationRequest {

    private String email;
    private String password;
    private String preferredUsername;
    private String givenName;
    private String familyName;
    private String phoneNumber;

    public UserRegistrationRequest() {
    }

    public UserRegistrationRequest(String email, String password, String preferredUsername, String givenName, String familyName) {
        this.email = email;
        this.password = password;
        this.preferredUsername = preferredUsername;
        this.givenName = givenName;
        this.familyName = familyName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }
}
