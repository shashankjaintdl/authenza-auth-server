//package com.authenza.master.config;
//
//
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.web.cors.CorsConfiguration;
//import org.springframework.web.cors.CorsConfigurationSource;
//import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
//
//import java.util.Arrays;
//import java.util.List;
//
//@Configuration
//public class CorsConfig {
//
//    /**
//     * Shared CorsConfigurationSource bean used by Spring Security's built-in
//     * CORS support (.cors()) on every SecurityFilterChain.
//     *
//     * Using allowedOriginPatterns("*") instead of allowedOrigins("*")
//     * because allowedOrigins("*") is incompatible with allowCredentials(true).
//     */
//    @Bean
//    public CorsConfigurationSource corsConfigurationSource() {
//        CorsConfiguration config = new CorsConfiguration();
//
//        // Allow credentials (cookies, Authorization header)
//        config.setAllowCredentials(true);
//
//        // allowedOriginPatterns("*") works with credentials — allowedOrigins("*") does NOT
//        config.setAllowedOriginPatterns(List.of("*"));
//
//        // Allow common headers and all standard methods
//        config.setAllowedHeaders(Arrays.asList("Origin", "Content-Type", "Accept", "Authorization"));
//        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
//
//        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
//        source.registerCorsConfiguration("/**", config);
//        return source;
//    }
//}
//
