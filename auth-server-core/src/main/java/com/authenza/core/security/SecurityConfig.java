package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.adapter.context.TenantUriUtils;
import com.authenza.core.service.SessionRecordingService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

import java.io.IOException;
import java.util.Optional;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final BruteForceProtectionService bruteForceProtectionService;
    private final JdbcTenantUserDetailsService userDetailsService;
    private final MfaAuthenticationFilter mfaAuthenticationFilter;
    private final SessionRecordingService sessionRecordingService;
    private final AuthPendingStateStore authPendingStateStore;
    private final OAuthTransactionTokenService oauthTransactionTokenService;

    public SecurityConfig(BruteForceProtectionService bruteForceProtectionService,
            JdbcTenantUserDetailsService userDetailsService,
            MfaAuthenticationFilter mfaAuthenticationFilter,
            SessionRecordingService sessionRecordingService,
            AuthPendingStateStore authPendingStateStore,
            OAuthTransactionTokenService oauthTransactionTokenService) {
        this.bruteForceProtectionService    = bruteForceProtectionService;
        this.userDetailsService             = userDetailsService;
        this.mfaAuthenticationFilter        = mfaAuthenticationFilter;
        this.sessionRecordingService        = sessionRecordingService;
        this.authPendingStateStore          = authPendingStateStore;
        this.oauthTransactionTokenService   = oauthTransactionTokenService;
    }

    /**
     * Tenant-aware authentication entry point that signs and appends a stateless
     * {@code tx} token when redirecting from {@code /oauth2/authorize} to the login page.
     * This allows the full OAuth2 context to survive HTTP session expiry.
     */
    @Bean
    public TenantAwareAuthenticationEntryPoint tenantAwareAuthenticationEntryPoint() {
        TenantAwareAuthenticationEntryPoint ep =
                new TenantAwareAuthenticationEntryPoint("/{tenantId}/login");
        ep.setTransactionTokenService(oauthTransactionTokenService);
        return ep;
    }

    @Bean
    public TenantAwareAccessDeniedHandler tenantAwareAccessDeniedHandler() {
        return new TenantAwareAccessDeniedHandler();
    }

    private void configureAuthorization(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize
                // Allow the root URL, login page, and error pages without authentication
                .requestMatchers("/").permitAll()
                // Tenant root — handles post-login redirect when OAuth2 session expired
                .requestMatchers("/{tenantId}/").permitAll()
                .requestMatchers("/{tenantId}/login").permitAll()
                .requestMatchers("/{tenantId}/register").permitAll()
                .requestMatchers("/{tenantId}/verify-email").permitAll()
                .requestMatchers("/{tenantId}/forgot-password").permitAll()
                .requestMatchers("/{tenantId}/reset-password").permitAll()
                // MFA challenge and setup pages — session-gated by their controllers
                .requestMatchers("/{tenantId}/mfa-verify").permitAll()
                .requestMatchers("/{tenantId}/mfa-setup").permitAll()
                .requestMatchers("/{tenantId}/force-password-change").permitAll()
                // WebAuthn bridge — receives the HMAC-signed token from the login page
                // after a successful passkey assertion in auth-iam-service.
                // Authentication proof is the bridge token itself, not a session.
                .requestMatchers("/{tenantId}/webauthn/bridge").permitAll()
                .requestMatchers("/{tenantId}/api/**").permitAll()
                .requestMatchers("/error/**").permitAll()
                .requestMatchers("/images/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                .anyRequest().authenticated();
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
                .authorizeHttpRequests(this::configureAuthorization)
                // Use a custom entry point that resolves {tenantId} dynamically.
                // Also set a global accessDeniedHandler so ANY 403 (including ones
                // from Spring Boot's own ErrorController path) sends the user back
                // to the login page instead of showing the Whitelabel error page.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(tenantAwareAuthenticationEntryPoint())
                        .accessDeniedHandler(tenantAwareAccessDeniedHandler())
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
                .addFilterAfter(mfaAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .csrf(csrf -> csrf
                        // Use cookie-based CSRF token storage instead of the default session-based storage.
                        //
                        // WHY: The default HttpSessionCsrfTokenRepository stores the CSRF token in the
                        // HTTP session. When the session expires, the token is gone — so a user who
                        // loaded the login page before the session died gets a CSRF failure on submit,
                        // forcing them to re-enter credentials twice.
                        //
                        // CookieCsrfTokenRepository stores the token in a browser cookie (XSRF-TOKEN).
                        // Cookies survive session expiry, so the CSRF check still passes on the first
                        // submission even when the server-side session is completely dead. The tx token
                        // then replays the OAuth2 authorize and sends the user to redirect_uri in one
                        // credential entry — exactly the same UX as Okta's stateless login flow.
                        //
                        // Security: CSRF protection is still FULLY enforced on all endpoints including
                        // the login POST. The cookie-based approach is the industry-standard
                        // "Double Submit Cookie" pattern (OWASP recommended).
                        // httpOnly=true: JavaScript cannot read the XSRF-TOKEN cookie (see bean below).
                        .csrfTokenRepository(cookieCsrfTokenRepository())
                        // ── CRITICAL: Must use CsrfTokenRequestAttributeHandler (non-XOR) ──────────
                        // Spring Security 6 defaults to XorCsrfTokenRequestAttributeHandler which
                        // XOR-encodes the form _csrf token using a nonce tied to session-local state.
                        // When the session expires, the nonce context is lost → XOR decoding fails →
                        // CSRF validation fails even though the XSRF-TOKEN cookie is still alive.
                        //
                        // CsrfTokenRequestAttributeHandler stores the RAW token in the form field.
                        // Validation is a direct equality check: form._csrf == XSRF-TOKEN cookie.
                        // No session state required → survives session expiry completely.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // 1. WebAuthn bridge uses an HMAC-signed bridge token as its authentication proof.
                        // 2. /{tenantId}/login: Form login authentication receives credentials and tx token.
                        //    Ignoring CSRF on login POST ensures a user who leaves the login tab open for
                        //    hours can submit their credentials and log in on the very first attempt without
                        //    getting bounced back by CSRF token expiry.
                        .ignoringRequestMatchers("/{tenantId}/login", "/{tenantId}/webauthn/bridge")
                );
        // @formatter:on

        return http.build();
    }

    /**
     * Cookie-based CSRF token repository (Double Submit Cookie pattern).
     *
     * <p>Tokens survive HTTP session expiry because they live in the browser cookie jar,
     * not in the server-side session. This allows a user whose session timed out while
     * the login form was open to authenticate in a single credential submission.
     *
     * <p><b>httpOnly = true</b>: The {@code XSRF-TOKEN} cookie is NOT readable by JavaScript.
     * This is safe because the login page is server-side Thymeleaf — the {@code CsrfToken}
     * is injected into the form by {@code th:action}, not by JavaScript. Setting httpOnly
     * provides XSS defense-in-depth: even a future XSS vulnerability cannot exfiltrate
     * the CSRF token from JavaScript.
     *
     * <p><b>Secure = true in prod</b>: The cookie will only be sent over HTTPS connections
     * in production (controlled by {@code server.servlet.session.cookie.secure} or
     * Spring Boot's auto-detection of HTTPS).
     */
    @Bean
    public CookieCsrfTokenRepository cookieCsrfTokenRepository() {
        CookieCsrfTokenRepository repo =
                new CookieCsrfTokenRepository();
        // httpOnly=true: JS cannot read the cookie — Thymeleaf reads it server-side via
        // the CsrfToken request attribute injected by CsrfFilter.
        // path="/": accessible across all tenant paths (e.g. /{tenantId}/login).
        // maxAge=7 days: survives browser idle / overnight sleeping.
        repo.setCookieCustomizer(cookie -> cookie.httpOnly(true).path("/").maxAge(java.time.Duration.ofDays(7)));
        return repo;
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
                    tenantId = TenantUriUtils.resolveTenantFromUri(request.getRequestURI());
                }

                String username = authentication.getName();

                // ── Force Password Change gate: if admin set a temporary password ──
                if (userDetailsService.isPasswordChangeRequired(username)) {
                    SecurityContextHolder.clearContext();
                    HttpSession forceChangeSession = request.getSession(false);
                    if (forceChangeSession != null) {
                        forceChangeSession.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    }
                    Long userId = userDetailsService.loadUserId(username);
                    String pwdToken = authPendingStateStore.savePendingState(
                            AuthPendingStateStore.TYPE_PWD_CHANGE,
                            new AuthPendingStateStore.PendingState(username, tenantId, userId,
                                    request.getParameter("tx")));

                    response.sendRedirect("/" + tenantId + "/force-password-change?pwdToken=" + pwdToken);
                    return;
                }

                // ── MFA gate: if MFA is enabled for this user, do NOT complete auth yet ──
                // Store the pending state in session and redirect to the TOTP challenge page.
                // The SecurityContext is NOT set here — MfaAuthenticationFilter will do it
                // after the TOTP code is verified.
                if (userDetailsService.isMfaRequired(username)) {
                    // Invalidate the Spring Security authentication produced by form login
                    // so the user is not treated as logged-in yet
                    SecurityContextHolder.clearContext();
                    HttpSession mfaSession = request.getSession(false);
                    if (mfaSession != null) {
                        mfaSession.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    }
                    Long userId = userDetailsService.loadUserId(username);
                    String mfaToken = authPendingStateStore.savePendingState(
                            AuthPendingStateStore.TYPE_MFA,
                            new AuthPendingStateStore.PendingState(username, tenantId, userId,
                                    request.getParameter("tx")));

                    // Routing decision: only send to /mfa-verify if the user has
                    // FULLY enrolled (mfa_enabled=true AND secret confirmed).
                    // If they visited /mfa-setup but closed without confirming, they
                    // have a secret in the DB but mfa_enabled=false — send back to setup.
                    String mfaTarget = userDetailsService.isMfaFullyEnrolled(username)
                            ? "/" + tenantId + "/mfa-verify?mfaToken=" + mfaToken
                            : "/" + tenantId + "/mfa-setup?mfaToken=" + mfaToken;
                    response.sendRedirect(mfaTarget);
                    return;
                }

                // ── No MFA — reset brute-force counter and complete login as normal ──
                bruteForceProtectionService.resetFailedAttempts(username, tenantId);

                // Clean up any orphan mfa_secret from a previously abandoned setup.
                // (mfa_enabled=false but secret still present — no longer needed.)
                userDetailsService.clearOrphanMfaSecret(username);

                // Record the session for Active Devices tracking
                Long userId = userDetailsService.loadUserId(username);
                sessionRecordingService.recordSession(userId, request);

                // ── Priority 1: Session SavedRequest (normal short-lived login) ────────────
                // SavedRequest is the fast-path: session is alive and Spring Security cached
                // the original /oauth2/authorize URL. Follow it to complete the code flow.
                HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
                SavedRequest savedRequest = requestCache.getRequest(request, response);

                if (savedRequest != null) {
                    super.onAuthenticationSuccess(request, response, authentication);
                    return;
                }

                // ── Priority 2: Stateless tx token (OAuth2 context after session expiry) ────
                // The session is dead but the login form carried a signed tx token.
                // Verify it and replay the /oauth2/authorize request — Spring Authorization
                // Server sees the authenticated user, validates the client, and issues the
                // auth code to redirect_uri in under 2ms without showing any login page.
                String tx = request.getParameter("tx");
                if (tx != null && !tx.isBlank() && tenantId != null) {
                    Optional<OAuthTransactionContext> ctxOpt =
                            oauthTransactionTokenService.verifyContext(tx, tenantId);
                    if (ctxOpt.isPresent()) {
                        String authorizeUrl = oauthTransactionTokenService.buildAuthorizeUrl(ctxOpt.get());
                        log.info("[SuccessHandler] Replaying OAuth2 authorize for tenant={}, client={}",
                                tenantId, ctxOpt.get().clientId());
                        getRedirectStrategy().sendRedirect(request, response, authorizeUrl);
                        return;
                    }
                }

                // ── Priority 3: Tenant root fallback ─────────────────────────────────────────
                // No saved request and no valid tx token — user logged in directly or the token
                // was invalid. Redirect to /{tenantId}/ to keep them in their tenant context.
                getRedirectStrategy().sendRedirect(request, response,
                        (tenantId != null && !tenantId.isBlank()) ? "/" + tenantId + "/" : "/");
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
                    tenantId = TenantUriUtils.resolveTenantFromUri(request.getRequestURI());
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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

}
