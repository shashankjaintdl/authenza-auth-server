package com.authenza.core.client;

import com.authenza.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Internal HTTP client that proxies requests from auth-server-core
 * to auth-iam-service. This keeps the IAM service as the single owner
 * of all user CRUD logic while allowing the Thymeleaf UI pages in
 * auth-server-core to interact with it seamlessly.
 *
 * <p>All calls include the {@code X-Tenant-ID} header required by
 * the IAM service's {@code TenantValidationInterceptor}.</p>
 */
@Component
public class IamServiceClient {

    private static final Logger log = LoggerFactory.getLogger(IamServiceClient.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public IamServiceClient(@Value("${app.services.iam.base-url}") String iamBaseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(iamBaseUrl)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Proxies a user registration request to auth-iam-service.
     *
     * @param tenantId the tenant context for this registration
     * @param payload  JSON-serializable registration request body
     * @return the ApiResponse from IAM (success or error with message)
     */
    public ApiResponse<?> registerUser(String tenantId, Map<String, String> payload) {
        try {
            return restClient.post()
                    .uri("/api/v1/users/register")
                    .header(TENANT_HEADER, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "Registration failed. Please try again.");
        }
    }

    /**
     * Checks whether an email address is available within a tenant.
     *
     * @return true if available, false if already registered
     */
    public boolean isEmailAvailable(String tenantId, String email) {
        try {
            ApiResponse<?> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/users/check-email")
                            .queryParam("email", email)
                            .build())
                    .header(TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});

            return extractAvailability(response);
        } catch (Exception ex) {
            log.error("Email availability check failed for tenant '{}': {}", tenantId, ex.getMessage());
            // Default to available on error — the registration endpoint will catch duplicates
            return true;
        }
    }

    /**
     * Checks whether a username is available within a tenant.
     *
     * @return true if available, false if already taken
     */
    public boolean isUsernameAvailable(String tenantId, String username) {
        try {
            ApiResponse<?> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/users/check-username")
                            .queryParam("username", username)
                            .build())
                    .header(TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});

            return extractAvailability(response);
        } catch (Exception ex) {
            log.error("Username availability check failed for tenant '{}': {}", tenantId, ex.getMessage());
            return true;
        }
    }

    /**
     * Proxies the email verification request to auth-iam-service.
     */
    public ApiResponse<?> verifyEmail(String tenantId, String token) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/users/verify-email")
                            .queryParam("token", token)
                            .build())
                    .header(TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "Verification failed. The link may be invalid or expired.");
        }
    }

    // ─────────────────────────────────────────────
    // Password Reset Proxies
    // ─────────────────────────────────────────────

    /**
     * Proxies a password reset request to auth-iam-service.
     * Always returns success to the UI (anti-enumeration is handled by IAM).
     */
    public ApiResponse<?> requestPasswordReset(String tenantId, String email) {
        try {
            return restClient.post()
                    .uri("/api/v1/users/forgot-password")
                    .header(TENANT_HEADER, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("email", email))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            // Even on error, return success to prevent enumeration
            return ApiResponse.success("If an account with that email exists, a password reset link has been sent.");
        }
    }

    /**
     * Validates a password reset token by calling auth-iam-service.
     */
    public ApiResponse<?> validateResetToken(String tenantId, String token) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/users/validate-reset-token")
                            .queryParam("token", token)
                            .build())
                    .header(TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "Invalid or expired password reset link.");
        }
    }

    /**
     * Proxies the password reset (new password submission) to auth-iam-service.
     */
    public ApiResponse<?> resetPassword(String tenantId, String token, String newPassword) {
        try {
            return restClient.post()
                    .uri("/api/v1/users/reset-password")
                    .header(TENANT_HEADER, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("token", token, "newPassword", newPassword))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "Password reset failed. The link may be invalid or expired.");
        }
    }

    /**
     * Proxies the forced password change request to auth-iam-service.
     */
    public ApiResponse<?> forceChangePassword(String tenantId, Long userId, String newPassword) {
        try {
            return restClient.post()
                    .uri("/api/v1/users/" + userId + "/force-change-password")
                    .header(TENANT_HEADER, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("newPassword", newPassword))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "Failed to change password. Please try again.");
        }
    }

    /**
     * Extracts the "available" boolean from the ApiResponse data map.
     */
    @SuppressWarnings("unchecked")
    private boolean extractAvailability(ApiResponse<?> response) {
        if (response != null && response.getData() instanceof Map) {
            Map<String, Object> data = (Map<String, Object>) response.getData();
            Object available = data.get("available");
            if (available instanceof Boolean) {
                return (Boolean) available;
            }
        }
        return true;
    }

    // ─────────────────────────────────────────────
    // MFA (TOTP) Proxies
    // ─────────────────────────────────────────────

    /**
     * Initiates TOTP MFA setup for the given user via auth-iam-service.
     * Returns the QR code data URI and the raw base32 secret.
     */
    public ApiResponse<?> setupMfa(String tenantId, Long userId) {
        try {
            return restClient.post()
                    .uri("/api/v1/users/" + userId + "/mfa/setup")
                    .header(TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "MFA setup failed. Please try again.");
        }
    }

    /**
     * Confirms MFA enrollment by verifying the first authenticator code.
     */
    public ApiResponse<?> confirmMfa(String tenantId, Long userId, String code) {
        try {
            return restClient.post()
                    .uri("/api/v1/users/" + userId + "/mfa/confirm")
                    .header(TENANT_HEADER, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("code", code))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "MFA confirmation failed. The code may be invalid.");
        }
    }

    /**
     * Disables MFA after verifying the current TOTP code.
     */
    public ApiResponse<?> disableMfa(String tenantId, Long userId, String code) {
        try {
            return restClient.post()
                    .uri("/api/v1/users/" + userId + "/mfa/disable")
                    .header(TENANT_HEADER, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("code", code))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "MFA disable failed. The code may be invalid.");
        }
    }

    /**
     * Attempts to extract an error message from a RestClient exception.
     * Falls back to the provided default message if extraction fails.
     */
    private ApiResponse<?> extractErrorOrFallback(Exception ex, String fallbackMessage) {
        log.error("IAM service call failed: {}", ex.getMessage());

        // Try to extract error body from HttpClientErrorException
        if (ex instanceof org.springframework.web.client.HttpClientErrorException httpEx) {
            try {
                String body = httpEx.getResponseBodyAsString();
                ApiResponse<?> errorResponse = objectMapper.readValue(body, ApiResponse.class);
                if (errorResponse.getMessage() != null) {
                    return errorResponse;
                }
            } catch (Exception parseEx) {
                log.debug("Could not parse error response body: {}", parseEx.getMessage());
            }
        }

        return ApiResponse.internalServerError(fallbackMessage);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tenant Settings
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches all tenant settings from auth-iam-service.
     *
     * @param tenantId the tenant scope
     */
    public ApiResponse<?> getTenantSettings(String tenantId) {
        try {
            return restClient.get()
                    .uri("/api/v1/settings")
                    .header(TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "Failed to retrieve tenant settings.");
        }
    }

    /**
     * Reads the current tenant-wide MFA enforcement policy.
     *
     * @param tenantId the tenant scope
     */
    public ApiResponse<?> getMfaPolicy(String tenantId) {
        try {
            return restClient.get()
                    .uri("/api/v1/settings/mfa-policy")
                    .header(TENANT_HEADER, tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "Failed to retrieve MFA policy.");
        }
    }

    /**
     * Updates the tenant-wide MFA enforcement policy.
     *
     * @param tenantId        the tenant scope
     * @param requireForAll   {@code true} to enforce MFA for every user; {@code false} to relax
     */
    public ApiResponse<?> setMfaPolicy(String tenantId, boolean requireForAll) {
        try {
            return restClient.put()
                    .uri("/api/v1/settings/mfa-policy")
                    .header(TENANT_HEADER, tenantId)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("mfaRequiredForAll", requireForAll))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<?>>() {});
        } catch (Exception ex) {
            return extractErrorOrFallback(ex, "Failed to update MFA policy.");
        }
    }
}

