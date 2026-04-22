//package com.authenza.iam.config;
//
//import com.authenza.adapter.context.TenantContextHolder;
//import jakarta.servlet.http.HttpServletRequest;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.security.authentication.AuthenticationManager;
//import org.springframework.security.authentication.AuthenticationManagerResolver;
//import org.springframework.security.core.GrantedAuthority;
//import org.springframework.security.core.authority.SimpleGrantedAuthority;
//import org.springframework.security.oauth2.jwt.JwtDecoder;
//import org.springframework.security.oauth2.jwt.JwtDecoders;
//import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
//import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
//import org.springframework.stereotype.Component;
//
//import java.util.Collection;
//import java.util.Collections;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.stream.Collectors;
//
//@Component
//public class TenantJwtAuthenticationManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {
//
//    private static final Logger log = LoggerFactory.getLogger(TenantJwtAuthenticationManagerResolver.class);
//
//    // Cache to prevent fetching JWKS from auth-server-core on every single request
//    private final Map<String, AuthenticationManager> tenantManagers = new ConcurrentHashMap<>();
//
//    @Override
//    public AuthenticationManager resolve(HttpServletRequest request) {
//        String tenantId = TenantContextHolder.getTenantId();
//
//        if (tenantId == null || tenantId.isBlank()) {
//            throw new IllegalArgumentException("Tenant context is missing. Cannot validate JWT.");
//        }
//
//        return tenantManagers.computeIfAbsent(tenantId, this::buildAuthenticationManager);
//    }
//
//    private AuthenticationManager buildAuthenticationManager(String tenantId) {
//        log.info("Initializing JWT Decoder for tenant: {}", tenantId);
//
//        // This must match the authorization server's issuer URL exactly.
//        // auth-server-core is running on port 8081
//        String issuerUri = "http://localhost:8081/" + tenantId;
//
//        try {
//            JwtDecoder jwtDecoder = JwtDecoders.fromIssuerLocation(issuerUri);
//            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtDecoder);
//
//            // Map the "roles" claim from the JWT into Spring GrantedAuthorities
//            provider.setJwtAuthenticationConverter(jwtAuthenticationConverter());
//
//            return provider::authenticate;
//        } catch (Exception e) {
//            log.error("Failed to initialize JwtDecoder for tenant '{}' from issuer '{}': {}", tenantId, issuerUri,
//                    e.getMessage());
//            throw new IllegalArgumentException("Could not validate identity provider for tenant " + tenantId, e);
//        }
//    }
//
//    private JwtAuthenticationConverter jwtAuthenticationConverter() {
//        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
//        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
//            // Extract the 'roles' array populated by auth-server-core's
//            // OAuth2TokenCustomizer
//            Collection<String> roles = jwt.getClaimAsStringList("roles");
//
//            if (roles == null || roles.isEmpty()) {
//                return Collections.emptyList();
//            }
//
//            return roles.stream()
//                    .map(roleName -> {
//                        // Prepend ROLE_ to match Spring Security's expression expectations (e.g.
//                        // hasRole('TENANT_ADMIN'))
//                        return new SimpleGrantedAuthority("ROLE_" + roleName);
//                    })
//                    .collect(Collectors.toList());
//        });
//        return converter;
//    }
//}
