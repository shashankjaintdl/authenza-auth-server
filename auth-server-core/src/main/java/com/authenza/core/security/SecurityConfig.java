package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.core.service.SessionRecordingService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

import java.io.IOException;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final BruteForceProtectionService bruteForceProtectionService;
    private final JdbcTenantUserDetailsService userDetailsService;
    private final MfaAuthenticationFilter mfaAuthenticationFilter;
    private final SessionRecordingService sessionRecordingService;

    public SecurityConfig(BruteForceProtectionService bruteForceProtectionService,
            JdbcTenantUserDetailsService userDetailsService,
            MfaAuthenticationFilter mfaAuthenticationFilter,
            SessionRecordingService sessionRecordingService) {
        this.bruteForceProtectionService = bruteForceProtectionService;
        this.userDetailsService = userDetailsService;
        this.mfaAuthenticationFilter = mfaAuthenticationFilter;
        this.sessionRecordingService = sessionRecordingService;
    }

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http,
            MultiTenantSecurityFilter tenantSecurityFilter)
            throws Exception {
        // @formatter:off
        http
                // Add the tenant filter so TenantContextHolder is populated
                // before security decisions are made (including login redirects)
                .addFilterBefore(tenantSecurityFilter, DisableEncodeUrlFilter.class)
                // Enable CORS — delegates to the CorsConfigurationSource bean
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests((authorize) ->
                        authorize
                                // Allow the root URL, login page, and error pages without authentication
                                .requestMatchers("/").permitAll()
                                .requestMatchers("/{tenantId}/login").permitAll()
                                .requestMatchers("/{tenantId}/register").permitAll()
                                .requestMatchers("/{tenantId}/verify-email").permitAll()
                                .requestMatchers("/{tenantId}/forgot-password").permitAll()
                                .requestMatchers("/{tenantId}/reset-password").permitAll()
                                // MFA challenge and setup pages — session-gated by their controllers
                                .requestMatchers("/{tenantId}/mfa-verify").permitAll()
                                .requestMatchers("/{tenantId}/mfa-setup").permitAll()
                                .requestMatchers("/{tenantId}/api/**").permitAll()
                                .requestMatchers("/error/**").permitAll()
                                .requestMatchers("/images/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                                .anyRequest().authenticated()
                )
                // Use a custom entry point that resolves {tenantId} dynamically
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                new TenantAwareAuthenticationEntryPoint("/{tenantId}/login")
                        )
                )
                // Form login handles the redirect to the login page from the
                // authorization server filter chain
                .formLogin(
                        form -> {
                            // DO NOT set loginPage() here — it causes Spring to
                            // set up its own redirect with the literal "{tenantId}".
                            // Instead, we handle redirects via the entry point above.
                            form.loginProcessingUrl("/{tenantId}/login");
                            // Redirect back to the tenant-specific login page with ?error
                            // so the inline error message is shown instead of an error page
                            form.failureHandler(tenantAwareAuthenticationFailureHandler());
                            // After successful login, follow saved request (OAuth2 authorize)
                            // or fall back to /{tenantId}/ — never to "/" which would
                            // trigger the master tenant OAuth2 flow for non-master tenants
                            form.successHandler(tenantAwareAuthenticationSuccessHandler());
                        }
                )
                // Register the MFA TOTP verification filter after password auth
                .addFilterAfter(mfaAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        // @formatter:on

        return http.build();
    }

    /**
     * Tenant-aware success handler.
     * - If a saved request exists (e.g. OAuth2 /authorize flow), follow it
     * → completes the authorization code flow for the correct tenant/client
     * - If no saved request (user navigated directly to /{tenantId}/login),
     * redirect to /{tenantId}/ to keep them in their tenant context.
     * This prevents non-master tenants from being redirected to "/" which
     * would trigger the system-master OAuth2 authorize flow.
     */
    @Bean
    public SavedRequestAwareAuthenticationSuccessHandler tenantAwareAuthenticationSuccessHandler() {
        return new SavedRequestAwareAuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request,
                    HttpServletResponse response,
                    Authentication authentication)
                    throws IOException, ServletException {

                // Resolve tenant for brute-force counter reset
                String tenantId = TenantContextHolder.getTenantId();
                if (tenantId == null || tenantId.isBlank()) {
                    tenantId = resolveTenantFromUri(request.getRequestURI());
                }

                String username = authentication.getName();

                // ── MFA gate: if MFA is enabled for this user, do NOT complete auth yet ──
                // Store the pending state in session and redirect to the TOTP challenge page.
                // The SecurityContext is NOT set here — MfaAuthenticationFilter will do it
                // after the TOTP code is verified.
                if (userDetailsService.isMfaRequired(username)) {
                    // Invalidate the Spring Security authentication produced by form login
                    // so the user is not treated as logged-in yet
                    SecurityContextHolder.clearContext();
                    HttpSession mfaSession = request.getSession(true);
                    mfaSession.setAttribute(MfaAuthenticationFilter.PENDING_MFA_USERNAME, username);
                    mfaSession.setAttribute(MfaAuthenticationFilter.PENDING_MFA_TENANT, tenantId);
                    // Also store userId for the setup flow (needed to call IAM API)
                    Long userId = userDetailsService.loadUserId(username);
                    mfaSession.setAttribute(MfaAuthenticationFilter.PENDING_MFA_USER_ID, userId);

                    // Routing decision: only send to /mfa-verify if the user has
                    // FULLY enrolled (mfa_enabled=true AND secret confirmed).
                    // If they visited /mfa-setup but closed without confirming, they
                    // have a secret in the DB but mfa_enabled=false — send back to setup.
                    String mfaTarget = userDetailsService.isMfaFullyEnrolled(username)
                            ? "/" + tenantId + "/mfa-verify"
                            : "/" + tenantId + "/mfa-setup";
                    response.sendRedirect(mfaTarget);
                    return;
                }

                // ── No MFA — reset brute-force counter and complete login as normal ──
                bruteForceProtectionService.resetFailedAttempts(username, tenantId);

                // Record the session for Active Devices tracking
                Long userId = userDetailsService.loadUserId(username);
                sessionRecordingService.recordSession(userId, request);

                // Check if there's a saved request (from OAuth2 authorize redirect)
                HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
                SavedRequest savedRequest = requestCache.getRequest(request, response);

                if (savedRequest != null) {
                    // A pending OAuth2 authorize request exists — let the default
                    // handler follow it (issues auth code to the correct client)
                    super.onAuthenticationSuccess(request, response, authentication);
                    return;
                }

                // No saved request — user logged in directly at /{tenantId}/login.
                // Redirect to /{tenantId}/ instead of "/" to stay in their tenant.
                if (tenantId != null && !tenantId.isBlank()) {
                    getRedirectStrategy().sendRedirect(request, response, "/" + tenantId + "/");
                } else {
                    getRedirectStrategy().sendRedirect(request, response, "/");
                }
            }
        };
    }

    /**
     * Custom failure handler that:
     * <ol>
     * <li>Records the failed attempt via {@link BruteForceProtectionService}.</li>
     * <li>Redirects to {@code /{tenantId}/login?locked} if the account was just
     * locked.</li>
     * <li>Redirects to {@code /{tenantId}/login?error} for an ordinary
     * bad-credential failure.</li>
     * </ol>
     */
    @Bean
    public AuthenticationFailureHandler tenantAwareAuthenticationFailureHandler() {
        return new AuthenticationFailureHandler() {
            @Override
            public void onAuthenticationFailure(HttpServletRequest request,
                    HttpServletResponse response,
                    AuthenticationException exception)
                    throws IOException, ServletException {

                // Extract tenantId from the POST URL (e.g. /customer2/login)
                String tenantId = TenantContextHolder.getTenantId();

                if (tenantId == null || tenantId.isBlank()) {
                    // Fallback: parse from request URI
                    tenantId = resolveTenantFromUri(request.getRequestURI());
                }

                if (tenantId == null || tenantId.isBlank()) {
                    response.sendRedirect("/error/invalid-tenant");
                    return;
                }

                // Extract the attempted username from the form submission
                String username = request.getParameter("username");

                // Track the failed attempt and check if the account was just locked
                boolean accountJustLocked = (username != null && !username.isBlank())
                        && bruteForceProtectionService.recordFailedAttempt(username, tenantId);

                if (accountJustLocked) {
                    response.sendRedirect("/" + tenantId + "/login?locked");
                } else {
                    response.sendRedirect("/" + tenantId + "/login?error");
                }
            }
        };
    }

    private static String resolveTenantFromUri(String uri) {
        if (uri == null || uri.equals("/"))
            return null;
        String[] parts = uri.split("/");
        for (String part : parts) {
            if (!part.isEmpty())
                return part;
        }
        return null;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

}
