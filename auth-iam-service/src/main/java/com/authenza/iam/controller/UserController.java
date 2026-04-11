package com.authenza.iam.controller;

import com.authenza.common.constant.AuthenzaConstant;
import com.authenza.common.dto.ApiResponse;
import com.authenza.iam.dto.UserRegistrationRequest;
import com.authenza.iam.dto.UserResponse;
import com.authenza.iam.service.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping(UserController.ENDPOINT)
public class UserController {

    public static final String ENDPOINT = AuthenzaConstant.API_VERSION  + "/users";

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

    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<String>> verifyEmail(@RequestParam("token") String token) {
        userService.verifyEmail(token);
        return ResponseEntity.ok(
                ApiResponse.success("Email verified successfully. You can now log in.")
        );
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
                ApiResponse.success(Map.of("available", available))
        );
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
                ApiResponse.success(Map.of("available", available))
        );
    }

}
