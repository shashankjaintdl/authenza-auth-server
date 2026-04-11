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
