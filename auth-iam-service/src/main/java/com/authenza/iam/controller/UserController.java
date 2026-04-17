package com.authenza.iam.controller;

import com.authenza.common.constant.AuthenzaConstant;
import com.authenza.common.dto.ApiResponse;
import com.authenza.common.dto.DatabaseHelper;
import com.authenza.common.model.iam.User;
import com.authenza.iam.dto.AcceptInviteRequest;
import com.authenza.iam.dto.ChangePasswordRequest;
import com.authenza.iam.dto.InviteUserRequest;
import com.authenza.iam.dto.PasswordResetRequest;
import com.authenza.iam.dto.UpdateProfileRequest;
import com.authenza.iam.dto.UserRegistrationRequest;
import com.authenza.iam.dto.UserResponse;
import com.authenza.iam.service.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(UserController.ENDPOINT)
public class UserController {

        public static final String ENDPOINT = AuthenzaConstant.API_VERSION + "/users";

        private final UserService userService;

        public UserController(UserService userService) {
                this.userService = userService;
        }

        @PostMapping("/register")
        public ResponseEntity<ApiResponse<UserResponse>> registerUser(
                        @Valid @RequestBody UserRegistrationRequest request) throws JsonProcessingException {
                UserResponse response = userService.registerUser(request);
                ApiResponse<UserResponse> apiResponse = ApiResponse.created(response, "User registered successfully");
                return ResponseEntity
                                .status(apiResponse.getStatus())
                                .body(apiResponse);
        }

        @GetMapping
        public ResponseEntity<ApiResponse<List<UserResponse>>> getUsers(
                        @RequestParam(name = "currentPage", defaultValue = "0", required = false) int currentPage,
                        @RequestParam(name = "itemsPerPage", defaultValue = "10", required = false) int itemsPerPage,
                        @RequestParam(name = "sortOrder", defaultValue = "desc", required = false) String sortOrder,
                        @RequestParam(name = "sortBy", defaultValue = "createdAt", required = false) String sortBy) {
                DatabaseHelper databaseHelper = new DatabaseHelper(currentPage, itemsPerPage, sortBy, sortOrder);
                Page<UserResponse> users = userService.getUsers(databaseHelper);
                ApiResponse<List<UserResponse>> apiResponse = ApiResponse.paginated(users, "Users retrieved successfully.");
                return ResponseEntity.ok(apiResponse);
        }

        @GetMapping("/verify-email")
        public ResponseEntity<ApiResponse<String>> verifyEmail(@RequestParam("token") String token) {
                userService.verifyEmail(token);
                return ResponseEntity.ok(
                                ApiResponse.success("Email verified successfully. You can now log in."));
        }

        // ─────────────────────────────────────────────
        // Password Reset Endpoints
        // ─────────────────────────────────────────────

        /**
         * Initiates the password reset flow by sending a reset email.
         * Always returns success to prevent email enumeration attacks.
         */
        @PostMapping("/forgot-password")
        public ResponseEntity<ApiResponse<String>> forgotPassword(@RequestBody Map<String, String> payload)
                        throws JsonProcessingException {
                String email = payload.get("email");
                userService.requestPasswordReset(email);
                return ResponseEntity.ok(
                                ApiResponse.success(
                                                "If an account with that email exists, a password reset link has been sent."));
        }

        /**
         * Validates a password reset token without consuming it.
         * Used by the UI to check if the token is valid before showing the reset form.
         */
        @GetMapping("/validate-reset-token")
        public ResponseEntity<ApiResponse<String>> validateResetToken(@RequestParam("token") String token) {
                userService.validateResetToken(token);
                return ResponseEntity.ok(
                                ApiResponse.success("Token is valid."));
        }

        /**
         * Completes the password reset by updating the user's password.
         */
        @PostMapping("/reset-password")
        public ResponseEntity<ApiResponse<String>> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
                userService.resetPassword(request.getToken(), request.getNewPassword());
                return ResponseEntity.ok(
                                ApiResponse.success("Your password has been reset successfully. You can now sign in."));
        }

        /**
         * Real-time email availability check for the registration form.
         * Returns whether the given email is available (not yet registered)
         * within the current tenant's database.
         */
        @GetMapping("/check-email")
        public ResponseEntity<ApiResponse<Map<String, Boolean>>> checkEmailAvailability(
                        @RequestParam("email") String email) {
                boolean available = userService.isEmailAvailable(email);
                return ResponseEntity.ok(
                                ApiResponse.success(Map.of("available", available)));
        }

        /**
         * Real-time username availability check for the registration form.
         * Returns whether the given username is available (not yet taken)
         * within the current tenant's database.
         */
        @GetMapping("/check-username")
        public ResponseEntity<ApiResponse<Map<String, Boolean>>> checkUsernameAvailability(
                        @RequestParam("username") String username) {
                boolean available = userService.isUsernameAvailable(username);
                return ResponseEntity.ok(
                                ApiResponse.success(Map.of("available", available)));
        }

        // ─────────────────────────────────────────────
        // Admin User Invitation Endpoints
        // ─────────────────────────────────────────────

        /**
         * Invites a new user to the tenant.
         * Only Tenant Admins should call this endpoint.
         * Creates a user in INVITED status and sends a one-time invitation email.
         */
        @PostMapping("/invite")
        public ResponseEntity<ApiResponse<UserResponse>> inviteUser(
                        @Valid @RequestBody InviteUserRequest request,
                        @RequestHeader(value = "X-Inviter-Name", defaultValue = "Admin") String inviterName)
                        throws JsonProcessingException {
                UserResponse response = userService.inviteUser(request, inviterName);
                return ResponseEntity
                                .status(201)
                                .body(ApiResponse.created(response, "Invitation sent successfully."));
        }

        /**
         * Accepts an admin invitation. The invited user provides the token from the
         * email link and sets their password for the first time.
         */
        @PostMapping("/accept-invite")
        public ResponseEntity<ApiResponse<UserResponse>> acceptInvitation(
                        @Valid @RequestBody AcceptInviteRequest request) {
                UserResponse response = userService.acceptInvitation(request);
                return ResponseEntity.ok(
                                ApiResponse.success(response, "Invitation accepted. Your account is now active."));
        }

        // ─────────────────────────────────────────────
        // Profile Management Endpoints
        // ─────────────────────────────────────────────

        /**
         * Retrieves the profile for a specific user.
         * In a fully secured deployment, the userId should be extracted
         * from the JWT principal rather than the path variable.
         */
        @GetMapping("/{userId}/profile")
        public ResponseEntity<ApiResponse<UserResponse>> getUserProfile(@PathVariable Long userId) {
                UserResponse profile = userService.getUserProfile(userId);
                return ResponseEntity.ok(
                                ApiResponse.success(profile, "Profile retrieved successfully."));
        }

        /**
         * Updates a user's non-sensitive profile fields (name, phone, picture, locale,
         * etc.).
         * Email and password changes have their own dedicated endpoints.
         */
        @PutMapping("/{userId}/profile")
        public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
                        @PathVariable Long userId,
                        @RequestBody UpdateProfileRequest request) {
                UserResponse updated = userService.updateProfile(userId, request);
                return ResponseEntity.ok(
                                ApiResponse.success(updated, "Profile updated successfully."));
        }

        /**
         * Changes a user's password from within the app.
         * Requires the current password for identity re-verification.
         */
        @PostMapping("/{userId}/change-password")
        public ResponseEntity<ApiResponse<String>> changePassword(
                        @PathVariable Long userId,
                        @Valid @RequestBody ChangePasswordRequest request) {
                userService.changePassword(userId, request);
                return ResponseEntity.ok(
                                ApiResponse.success("Password changed successfully."));
        }

        /**
         * Permanently deletes a user's account and all associated data.
         * This is the self-service deletion flow for GDPR/CCPA compliance.
         */
        @DeleteMapping("/{userId}")
        public ResponseEntity<ApiResponse<String>> deleteAccount(@PathVariable Long userId) {
                userService.deleteAccount(userId);
                return ResponseEntity.ok(
                                ApiResponse.noContent("Account deleted successfully."));
        }

}
