package com.authenza.iam.service;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.common.dto.DatabaseHelper;
import com.authenza.common.enums.UserStatus;
import com.authenza.common.enums.VerificationTokenType;
import com.authenza.common.exception.InvalidTokenException;
import com.authenza.common.exception.ResourceAlreadyExistsException;
import com.authenza.common.exception.ResourceNotFoundException;
import com.authenza.common.model.iam.User;
import com.authenza.iam.dto.AcceptInviteRequest;
import com.authenza.iam.dto.ChangePasswordRequest;
import com.authenza.iam.dto.InviteUserRequest;
import com.authenza.iam.dto.UpdateProfileRequest;
import com.authenza.iam.dto.UserRegistrationRequest;
import com.authenza.iam.dto.AdminUserCreateRequest;
import com.authenza.iam.dto.UserResponse;
import com.authenza.iam.model.EmailVerificationToken;
import com.authenza.iam.repository.UserRepository;
import com.authenza.iam.repository.RoleRepository;
import com.authenza.iam.repository.EmailVerificationTokenRepository;
import com.authenza.common.model.iam.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationEventPublisher notificationEventPublisher;

    public UserService(UserRepository userRepository, EmailVerificationTokenRepository verificationTokenRepository,
            RoleRepository roleRepository, PasswordEncoder passwordEncoder,
            NotificationEventPublisher notificationEventPublisher) {
        this.userRepository = userRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    @Transactional
    public UserResponse registerUser(UserRegistrationRequest request) throws JsonProcessingException {
        String tenantId = TenantContextHolder.getTenantId();
        log.info("Registering user for tenant '{}' (from TenantContext)", tenantId);

        userRepository.findByEmail(request.getEmail())
                .ifPresent(u -> {
                    throw new ResourceAlreadyExistsException("Email is already properly registered");
                });

        userRepository.findByPreferredUsername(request.getPreferredUsername())
                .ifPresent(u -> {
                    throw new ResourceAlreadyExistsException("Username is already in use");
                });

        User user = new User();
        user.setPreferredUsername(request.getPreferredUsername());
        user.setEmail(request.getEmail());
        user.setGivenName(request.getGivenName());
        user.setFamilyName(request.getFamilyName());
        user.setName(request.getGivenName() + " " + request.getFamilyName());
        // Enforce strict password policies before encoding
        PasswordPolicyValidator.validate(request.getPassword());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        user.setEmailVerified(false);
        user.setPhoneNumberVerified(false);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setFailedLoginAttempts(0);
        user.setMfaEnabled(false);
        user.setCreatedAt(Instant.now());

        // Assign correct Role based on the Tenant Context
        // String targetRoleName = tenantId.equals("system-admin") ? "ROLE_SYSTEM_ADMIN"
        // : "ROLE_TENANT_ADMIN";
        // Role targetRole = roleRepository.findByName(targetRoleName)
        // .orElseThrow(() -> new IllegalStateException(
        // "Required system role " + targetRoleName + " is missing from the
        // database."));

        // User.UserRoleRef roleRef = new User.UserRoleRef();
        // roleRef.setRoleId(targetRole.getId());
        // user.getRoles().add(roleRef);

        User savedUser = userRepository.save(user);
        generateAndSendEmailVerificationToken(savedUser);
        return new UserResponse();
    }

    @Transactional
    public UserResponse createUser(AdminUserCreateRequest request) throws JsonProcessingException {
        String tenantId = TenantContextHolder.getTenantId();
        log.info("Admin creating user '{}' in tenant '{}'", request.getEmail(), tenantId);

        userRepository.findByEmail(request.getEmail())
                .ifPresent(u -> {
                    throw new ResourceAlreadyExistsException("A user with this email already exists.");
                });

        if (request.getPreferredUsername() != null && !request.getPreferredUsername().isBlank()) {
            userRepository.findByPreferredUsername(request.getPreferredUsername())
                    .ifPresent(u -> {
                        throw new ResourceAlreadyExistsException("Username is already in use.");
                    });
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPreferredUsername(request.getPreferredUsername() != null && !request.getPreferredUsername().isBlank() 
            ? request.getPreferredUsername() 
            : request.getEmail());
        user.setGivenName(request.getGivenName());
        user.setFamilyName(request.getFamilyName());
        user.setName(request.getGivenName() + " " + request.getFamilyName());
        
        // Enforce password policies
        PasswordPolicyValidator.validate(request.getPassword());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRequiresPasswordChange(true);

        user.setEmailVerified(true); // Admin-created users are trusted by default in this flow
        user.setPhoneNumberVerified(false);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setFailedLoginAttempts(0);
        user.setMfaEnabled(false);
        user.setCreatedAt(Instant.now());

        // Assign roles if provided
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            for (Long roleId : request.getRoleIds()) {
                User.UserRoleRef roleRef = new User.UserRoleRef();
                roleRef.setRoleId(roleId);
                user.getRoles().add(roleRef);
            }
        }

        User savedUser = userRepository.save(user);
        log.info("User '{}' created by admin in tenant '{}'", savedUser.getEmail(), tenantId);

        return toUserResponse(savedUser);
    }

    private void generateAndSendEmailVerificationToken(final User user) throws JsonProcessingException {
        String token = UUID.randomUUID().toString();
        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.setUserId(user.getId());
        verificationToken.setToken(token);
        verificationToken.setTokenType(VerificationTokenType.EMAIL_VERIFICATION);
        verificationToken.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        verificationToken.setCreatedAt(Instant.now());
        verificationTokenRepository.save(verificationToken);
        // Publish event to Redis → notification-service will send the email
        notificationEventPublisher.publishVerificationEvent(TenantContextHolder.getTenantId(), user.getEmail(),
                user.getName(), token);
    }

    @Transactional
    public void verifyEmail(String token) {
        EmailVerificationToken verificationToken = verificationTokenRepository
                .findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired verification link."));

        if (verificationToken.isExpired()) {
            verificationTokenRepository.deleteByUserId(verificationToken.getUserId());
            throw new InvalidTokenException("Verification link has expired. Please register again.");
        }

        User user = userRepository.findById(verificationToken.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        user.setEmailVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setUpdatedAt(Instant.now());

        userRepository.save(user);

        // Clean up the used token
        verificationTokenRepository.deleteByUserId(user.getId());

        log.info("Email verified for user '{}' in tenant '{}'",
                user.getEmail(), TenantContextHolder.getTenantId());
    }

    public Page<UserResponse> getUsers(DatabaseHelper databaseHelper) {
        Pageable pageable;
        if (databaseHelper.getSortOrder().isEmpty() || databaseHelper.getSortBy().isEmpty()) {
            pageable = PageRequest.of(databaseHelper.getCurrentPage(), databaseHelper.getItemPerPage());
        } else {
            pageable = PageRequest
                    .of(databaseHelper.getCurrentPage(), databaseHelper.getItemPerPage())
                    .withSort(Sort.Direction.fromString(databaseHelper.getSortOrder()), databaseHelper.getSortBy());
        }
        // Map the secure User entity safely to the UserResponse DTO.
        // We filter out shadow admins by excluding users with the sentinel password [GLOBAL_ACCOUNT].
        return userRepository.findByPasswordNot("[GLOBAL_ACCOUNT]", pageable).map(this::toUserResponse);
    }

    // ─────────────────────────────────────────────
    // Password Reset Flow
    // ─────────────────────────────────────────────

    /**
     * Initiates the password reset flow for the given email address.
     *
     * <p>
     * <strong>Anti-enumeration:</strong> This method silently succeeds even if no
     * user is found with the given email, preventing attackers from probing
     * which email addresses are registered in the system.
     * </p>
     *
     * @param email the email address to send the reset link to
     */
    @Transactional
    public void requestPasswordReset(String email) throws JsonProcessingException {
        String tenantId = TenantContextHolder.getTenantId();
        log.info("Password reset requested for email '{}' in tenant '{}'", email, tenantId);

        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isEmpty()) {
            // Silently succeed — do NOT reveal whether the email exists
            log.debug("No user found for email '{}' in tenant '{}'. Silently ignoring.", email, tenantId);
            return;
        }

        User user = userOpt.get();

        // Remove any existing password reset tokens for this user to prevent
        // accumulation
        verificationTokenRepository.deleteByUserIdAndTokenType(user.getId(), VerificationTokenType.PASSWORD_RESET);

        // Generate a new token with 1-hour TTL (tighter than email verification's
        // 24hrs)
        String token = UUID.randomUUID().toString();
        EmailVerificationToken resetToken = new EmailVerificationToken();
        resetToken.setUserId(user.getId());
        resetToken.setToken(token);
        resetToken.setTokenType(VerificationTokenType.PASSWORD_RESET);
        resetToken.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        resetToken.setCreatedAt(Instant.now());
        verificationTokenRepository.save(resetToken);

        // Publish event → notification-service sends the password reset email
        notificationEventPublisher.publishPasswordResetEvent(tenantId, user.getEmail(), user.getName(), token);

        log.info("Password reset token generated for user '{}' in tenant '{}'", user.getEmail(), tenantId);
    }

    /**
     * Validates that a password reset token exists and is not expired.
     *
     * @param token the reset token from the email link
     * @throws InvalidTokenException if the token is invalid or expired
     */
    public void validateResetToken(String token) {
        EmailVerificationToken resetToken = verificationTokenRepository
                .findByTokenAndTokenType(token, VerificationTokenType.PASSWORD_RESET)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired password reset link."));

        if (resetToken.isExpired()) {
            verificationTokenRepository.deleteByUserIdAndTokenType(resetToken.getUserId(),
                    VerificationTokenType.PASSWORD_RESET);
            throw new InvalidTokenException("Password reset link has expired. Please request a new one.");
        }
    }

    /**
     * Completes the password reset by updating the user's password.
     *
     * @param token       the valid reset token
     * @param newPassword the user's new plaintext password (will be encoded)
     * @throws InvalidTokenException if the token is invalid or expired
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        EmailVerificationToken resetToken = verificationTokenRepository
                .findByTokenAndTokenType(token, VerificationTokenType.PASSWORD_RESET)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired password reset link."));

        if (resetToken.isExpired()) {
            verificationTokenRepository.deleteByUserIdAndTokenType(resetToken.getUserId(),
                    VerificationTokenType.PASSWORD_RESET);
            throw new InvalidTokenException("Password reset link has expired. Please request a new one.");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        // Enforce strict password policies
        PasswordPolicyValidator.validate(newPassword);

        // Update the password
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setRequiresPasswordChange(false);
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        // Clean up — delete all password reset tokens for this user
        verificationTokenRepository.deleteByUserIdAndTokenType(user.getId(), VerificationTokenType.PASSWORD_RESET);

        log.info("Password successfully reset for user '{}' in tenant '{}'",
                user.getEmail(), TenantContextHolder.getTenantId());
    }

    /**
     * Checks whether the given email address is available (not yet registered)
     * within the current tenant's database.
     */
    public boolean isEmailAvailable(String email) {
        return userRepository.findByEmail(email).isEmpty();
    }

    /**
     * Checks whether the given username is available (not yet taken)
     * within the current tenant's database.
     */
    public boolean isUsernameAvailable(String username) {
        return userRepository.findByPreferredUsername(username).isEmpty();
    }

    // ─────────────────────────────────────────────
    // Admin User Invitation Flow
    // ─────────────────────────────────────────────

    /**
     * Invites a new user to the tenant on behalf of an admin.
     * Creates a user record in INVITED status (no password) and sends
     * a one-time invitation email with a secure token link.
     *
     * @param request       the invitation details (email, name)
     * @param invitedByName the name of the admin performing the invitation
     * @return the created UserResponse (status: INVITED)
     */
    @Transactional
    public UserResponse inviteUser(InviteUserRequest request, String invitedByName) throws JsonProcessingException {
        String tenantId = TenantContextHolder.getTenantId();
        log.info("Admin '{}' inviting user '{}' to tenant '{}'", invitedByName, request.getEmail(), tenantId);

        // Prevent duplicate invitations
        userRepository.findByEmail(request.getEmail())
                .ifPresent(u -> {
                    throw new ResourceAlreadyExistsException(
                            "A user with email '" + request.getEmail() + "' already exists in this tenant.");
                });

        // Create user in INVITED status — no password set yet
        User user = new User();
        user.setEmail(request.getEmail());
        user.setGivenName(request.getGivenName());
        user.setFamilyName(request.getFamilyName());
        user.setName(request.getGivenName() + " " + request.getFamilyName());
        user.setPreferredUsername(request.getEmail()); // Default username = email until they set one
        user.setEmailVerified(false);
        user.setPhoneNumberVerified(false);
        user.setStatus(UserStatus.INVITED);
        user.setFailedLoginAttempts(0);
        user.setMfaEnabled(false);
        user.setCreatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        // Generate a one-time invitation token (48-hour TTL)
        String token = UUID.randomUUID().toString();
        EmailVerificationToken inviteToken = new EmailVerificationToken();
        inviteToken.setUserId(savedUser.getId());
        inviteToken.setToken(token);
        inviteToken.setTokenType(VerificationTokenType.ADMIN_INVITE);
        inviteToken.setExpiresAt(Instant.now().plus(48, ChronoUnit.HOURS));
        inviteToken.setCreatedAt(Instant.now());
        verificationTokenRepository.save(inviteToken);

        // Publish event → notification-service sends the invitation email
        notificationEventPublisher.publishAdminInviteEvent(
                tenantId, savedUser.getEmail(), savedUser.getName(), token, invitedByName);

        log.info("Invitation sent to '{}' in tenant '{}'", savedUser.getEmail(), tenantId);
        return toUserResponse(savedUser);
    }

    /**
     * Accepts an admin invitation by consuming the one-time token
     * and setting the user's password for the first time.
     *
     * @param request contains the invitation token, password, and optional username
     * @return the activated UserResponse (status: ACTIVE)
     */
    @Transactional
    public UserResponse acceptInvitation(AcceptInviteRequest request) {
        EmailVerificationToken inviteToken = verificationTokenRepository
                .findByTokenAndTokenType(request.getToken(), VerificationTokenType.ADMIN_INVITE)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired invitation link."));

        if (inviteToken.isExpired()) {
            verificationTokenRepository.deleteByUserIdAndTokenType(
                    inviteToken.getUserId(), VerificationTokenType.ADMIN_INVITE);
            throw new InvalidTokenException("Invitation link has expired. Please ask your administrator to resend it.");
        }

        User user = userRepository.findById(inviteToken.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        // Enforce strict password policies
        PasswordPolicyValidator.validate(request.getPassword());

        // Set the password and activate the account
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmailVerified(true); // Email is verified by virtue of clicking the invitation link
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        // Allow the user to set a custom username during onboarding
        if (request.getPreferredUsername() != null && !request.getPreferredUsername().isBlank()) {
            // Check if the chosen username is available
            userRepository.findByPreferredUsername(request.getPreferredUsername())
                    .ifPresent(u -> {
                        throw new ResourceAlreadyExistsException("Username is already in use.");
                    });
            user.setPreferredUsername(request.getPreferredUsername());
        }

        userRepository.save(user);

        // Clean up the used invitation token
        verificationTokenRepository.deleteByUserIdAndTokenType(
                user.getId(), VerificationTokenType.ADMIN_INVITE);

        log.info("Invitation accepted by user '{}' in tenant '{}'",
                user.getEmail(), TenantContextHolder.getTenantId());

        return toUserResponse(user);
    }

    // ─────────────────────────────────────────────
    // Profile Management
    // ─────────────────────────────────────────────

    /**
     * Retrieves a user's profile by their ID.
     *
     * @param userId the user's database ID
     * @return a safe UserResponse DTO (no password or internal fields)
     * @throws ResourceNotFoundException if the user does not exist
     */
    public UserResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
        return toUserResponse(user);
    }

    /**
     * Updates non-sensitive profile fields for a user.
     * Email and password changes have their own dedicated, secured flows.
     *
     * @param userId  the user's database ID
     * @param request the profile update payload
     * @return the updated UserResponse DTO
     * @throws ResourceNotFoundException if the user does not exist
     */
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        // Update only non-null fields (partial update / PATCH semantics)
        if (request.getGivenName() != null) {
            user.setGivenName(request.getGivenName());
        }
        if (request.getFamilyName() != null) {
            user.setFamilyName(request.getFamilyName());
        }
        // Recompute the full "name" field whenever first or last name changes
        String givenName = user.getGivenName() != null ? user.getGivenName() : "";
        String familyName = user.getFamilyName() != null ? user.getFamilyName() : "";
        user.setName((givenName + " " + familyName).trim());

        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getPicture() != null) {
            user.setPicture(request.getPicture());
        }
        if (request.getLocale() != null) {
            user.setLocale(request.getLocale());
        }
        if (request.getZoneinfo() != null) {
            user.setZoneinfo(request.getZoneinfo());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        if (request.getBirthdate() != null) {
            user.setBirthdate(request.getBirthdate());
        }

        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        log.info("Profile updated for user '{}' in tenant '{}'",
                saved.getEmail(), TenantContextHolder.getTenantId());

        return toUserResponse(saved);
    }

    /**
     * Changes a user's password after verifying their current password.
     * This is the "authenticated change password" flow — NOT the forgot-password
     * flow.
     *
     * @param userId  the user's database ID
     * @param request contains the current and new passwords
     * @throws ResourceNotFoundException if the user does not exist
     * @throws IllegalArgumentException  if the current password is incorrect
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        // Verify the current password before allowing the change
        try {
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw new IllegalArgumentException("Incorrect current password.");
            }
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("password encoding prefix")) {
                // If it lacks a prefix, it's an old legacy format and definitely won't match 
                // the new delegating encoder logic. We treat it as incorrect.
                throw new IllegalArgumentException("Incorrect current password.");
            }
            throw ex;
        }

        // Prevent setting the same password
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new IllegalArgumentException("New password must be different from the current password.");
        }

        // Enforce strict password policies
        PasswordPolicyValidator.validate(request.getNewPassword());

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setRequiresPasswordChange(false);
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        log.info("Password changed for user '{}' in tenant '{}'",
                user.getEmail(), TenantContextHolder.getTenantId());
    }

    /**
     * Forces a password change for a user (e.g. after admin assigned a temporary password).
     * Bypasses current password verification, but requires the requires_password_change flag to be true.
     *
     * @param userId      the user's database ID
     * @param newPassword the new plaintext password
     */
    @Transactional
    public void forceChangePassword(Long userId, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (user.getRequiresPasswordChange() == null || !user.getRequiresPasswordChange()) {
            throw new IllegalStateException("Password change is not currently forced for this user.");
        }

        // Prevent setting the same password
        try {
            if (user.getPassword() != null && passwordEncoder.matches(newPassword, user.getPassword())) {
                throw new IllegalArgumentException("New password must be different from the temporary password.");
            }
        } catch (IllegalArgumentException ex) {
            // DelegatingPasswordEncoder throws this if the current DB password lacks a {prefix}.
            // If it lacks a prefix (like [GLOBAL_ACCOUNT] or legacy text), we safely assume 
            // the new password is "different" and allow the change to proceed.
            if (!ex.getMessage().contains("password encoding prefix")) {
                throw ex;
            }
        }

        // Enforce strict password policies
        PasswordPolicyValidator.validate(newPassword);

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setRequiresPasswordChange(false);
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        log.info("Forced password change completed for user '{}' in tenant '{}'",
                user.getEmail(), TenantContextHolder.getTenantId());
    }

    /**
     * Manually unlocks a user account that was locked due to excessive failed login
     * attempts.
     * Resets the failed attempt counter and clears the temporary lock window.
     *
     * <p>
     * This method is intended for call by tenant admins via the management API.
     *
     * @param userId the user's database ID
     * @throws ResourceNotFoundException if the user does not exist
     * @throws IllegalStateException     if the user account is not currently locked
     */
    @Transactional
    public void unlockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (user.getStatus() != com.authenza.common.enums.UserStatus.LOCKED) {
            throw new IllegalStateException("User account is not currently locked.");
        }

        user.setStatus(com.authenza.common.enums.UserStatus.ACTIVE);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        log.info("Account manually unlocked for user ID '{}' in tenant '{}'",
                userId, TenantContextHolder.getTenantId());
    }

    /**
     * Admin-only: Updates a user's email address directly.
     * Since this is an admin action, no re-verification flow is required.
     * The new email is checked for uniqueness within the tenant, and
     * {@code email_verified} is reset to {@code false}.
     *
     * @param userId   the user's database ID
     * @param newEmail the new email address
     * @return the updated UserResponse DTO
     * @throws ResourceNotFoundException      if the user does not exist
     * @throws ResourceAlreadyExistsException if the email is already taken
     */
    @Transactional
    public UserResponse updateEmail(Long userId, String newEmail) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        // Skip if the email hasn't actually changed
        if (newEmail.equalsIgnoreCase(user.getEmail())) {
            return toUserResponse(user);
        }

        // Ensure the new email isn't already registered in this tenant
        userRepository.findByEmail(newEmail)
                .ifPresent(u -> {
                    throw new ResourceAlreadyExistsException(
                            "A user with email '" + newEmail + "' already exists in this tenant.");
                });

        user.setEmail(newEmail);
        user.setEmailVerified(false); // New email has not been verified yet
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        log.info("Email updated by admin for user ID '{}' to '{}' in tenant '{}'",
                userId, newEmail, TenantContextHolder.getTenantId());

        return toUserResponse(saved);
    }

    /**
     * Permanently deletes a user's account and all associated data.
     * This is the self-service account deletion flow for GDPR/CCPA compliance.
     *
     * @param userId the user's database ID
     * @throws ResourceNotFoundException if the user does not exist
     */
    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        // 1. Clean up all verification/reset tokens
        verificationTokenRepository.deleteByUserId(userId);

        // 2. Delete the user record itself
        userRepository.deleteById(userId);

        log.info("Account permanently deleted for user '{}' (ID: {}) in tenant '{}'",
                user.getEmail(), userId, TenantContextHolder.getTenantId());
    }

    // ─────────────────────────────────────────────
    // Internal Helpers
    // ─────────────────────────────────────────────

    /**
     * Maps a User entity to a safe UserResponse DTO,
     * stripping out sensitive internal fields like password and lock status.
     */
    private UserResponse toUserResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setEmail(user.getEmail());
        response.setEmailVerified(user.getEmailVerified());
        response.setPreferredUsername(user.getPreferredUsername());
        response.setName(user.getName());
        response.setGivenName(user.getGivenName());
        response.setFamilyName(user.getFamilyName());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setPhoneNumberVerified(user.getPhoneNumberVerified());
        response.setPicture(user.getPicture());
        response.setStatus(user.getStatus() != null ? user.getStatus().name() : null);
        response.setMfaEnabled(user.getMfaEnabled());
        response.setCreatedAt(user.getCreatedAt());
        response.setLastLoginAt(user.getLastLoginAt());
        return response;
    }

}
