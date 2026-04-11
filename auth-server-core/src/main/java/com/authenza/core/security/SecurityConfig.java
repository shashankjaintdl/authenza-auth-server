package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

import java.io.IOException;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

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
                .authorizeHttpRequests((authorize) ->
                        authorize
                                // Allow the root URL, login page, and error pages without authentication
                                .requestMatchers("/").permitAll()
                                .requestMatchers("/{tenantId}/login").permitAll()
                                .requestMatchers("/{tenantId}/register").permitAll()
                                .requestMatchers("/{tenantId}/verify-email").permitAll()
                                .requestMatchers("/{tenantId}/api/**").permitAll()
                                .requestMatchers("/error/**").permitAll()
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
                );
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
                String tenantId = TenantContextHolder.getTenantId();
                if (tenantId == null || tenantId.isBlank()) {
                    tenantId = resolveTenantFromUri(request.getRequestURI());
                }

                if (tenantId != null && !tenantId.isBlank()) {
                    getRedirectStrategy().sendRedirect(request, response, "/" + tenantId + "/");
                } else {
                    getRedirectStrategy().sendRedirect(request, response, "/");
                }
            }
        };
    }

    /**
     * Custom failure handler that redirects to /{tenantId}/login?error
     * on bad credentials, keeping the user on the login page with an
     * inline error message instead of redirecting to a generic error page.
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

                if (tenantId != null && !tenantId.isBlank()) {
                    response.sendRedirect("/" + tenantId + "/login?error");
                } else {
                    response.sendRedirect("/error/invalid-tenant");
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
    public UserDetailsService userDetailsService() {
        UserDetails userDetails = User.builder()
                .username("user")
                .password(passwordEncoder().encode("pass"))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(userDetails);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

}
