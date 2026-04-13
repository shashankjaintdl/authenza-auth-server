 package com.authenza.iam.service;

 import com.authenza.adapter.context.TenantContextHolder;
 import com.authenza.common.enums.UserStatus;
 import com.authenza.common.enums.VerificationTokenType;
 import com.authenza.common.exception.InvalidTokenException;
 import com.authenza.common.exception.ResourceAlreadyExistsException;
 import com.authenza.common.exception.ResourceNotFoundException;
 import com.authenza.common.model.iam.User;
 import com.authenza.iam.dto.UserRegistrationRequest;
 import com.authenza.iam.dto.UserResponse;
 import com.authenza.iam.model.EmailVerificationToken;
 import com.authenza.iam.repository.EmailVerificationTokenRepository;
 import com.authenza.iam.repository.UserRepository;
 import com.fasterxml.jackson.core.JsonProcessingException;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
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
     private final PasswordEncoder passwordEncoder;
     private final NotificationEventPublisher notificationEventPublisher;

     public UserService(UserRepository userRepository, EmailVerificationTokenRepository verificationTokenRepository, PasswordEncoder passwordEncoder, NotificationEventPublisher notificationEventPublisher) {
         this.userRepository = userRepository;
         this.verificationTokenRepository = verificationTokenRepository;
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
         user.setPassword(passwordEncoder.encode(request.getPassword()));

         user.setEmailVerified(false);
         user.setPhoneNumberVerified(false);
         user.setStatus(UserStatus.PENDING_VERIFICATION);
         user.setFailedLoginAttempts(0);
         user.setMfaEnabled(false);
         user.setCreatedAt(Instant.now());
         User savedUser = userRepository.save(user);
         generateAndSendEmailVerificationToken(savedUser);
         return new UserResponse();
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
         notificationEventPublisher.publishVerificationEvent(TenantContextHolder.getTenantId(), user.getEmail(), user.getName(), token);
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

     // ─────────────────────────────────────────────
     // Password Reset Flow
     // ─────────────────────────────────────────────

     /**
      * Initiates the password reset flow for the given email address.
      *
      * <p><strong>Anti-enumeration:</strong> This method silently succeeds even if no
      * user is found with the given email, preventing attackers from probing
      * which email addresses are registered in the system.</p>
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

         // Remove any existing password reset tokens for this user to prevent accumulation
         verificationTokenRepository.deleteByUserIdAndTokenType(user.getId(), VerificationTokenType.PASSWORD_RESET);

         // Generate a new token with 1-hour TTL (tighter than email verification's 24hrs)
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
             verificationTokenRepository.deleteByUserIdAndTokenType(resetToken.getUserId(), VerificationTokenType.PASSWORD_RESET);
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
             verificationTokenRepository.deleteByUserIdAndTokenType(resetToken.getUserId(), VerificationTokenType.PASSWORD_RESET);
             throw new InvalidTokenException("Password reset link has expired. Please request a new one.");
         }

         User user = userRepository.findById(resetToken.getUserId())
                 .orElseThrow(() -> new ResourceNotFoundException("User not found."));

         // Update the password
         user.setPassword(passwordEncoder.encode(newPassword));
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

 }

