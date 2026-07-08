package com.authenza.adapter.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimitFilter.class);

    private static final Set<String> IGNORED_PREFIXES = java.util.Set.of(
            "api", "actuator", "swagger-ui", "v3", "error", "favicon.ico", "webjars", "static", "css", "js");

    private static final Set<String> IGNORED_SUFFIXES = Set.of(
            "/oauth2/jwks",
            "/.well-known/openid-configuration"
    );

    private final LettuceBasedProxyManager<byte[]> proxyManager;
    private final BucketConfiguration defaultBucketConfig;
    private final int burstPerMinute;

    public TenantRateLimitFilter(
            LettuceBasedProxyManager<byte[]> proxyManager,
            @org.springframework.beans.factory.annotation.Value("${app.security.rate-limit.burst-per-minute:200}") int burstPerMinute,
            @org.springframework.beans.factory.annotation.Value("${app.security.rate-limit.sustained-per-hour:5000}") int sustainedPerHour) {
        this.proxyManager = proxyManager;
        this.burstPerMinute = burstPerMinute;

        // Dual-Layer limits: Stop immediate spikes, but also prevent slow sustained
        // scraping
        this.defaultBucketConfig = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(burstPerMinute).refillGreedy(burstPerMinute, Duration.ofMinutes(1)))
                .addLimit(limit -> limit.capacity(sustainedPerHour).refillGreedy(sustainedPerHour, Duration.ofHours(1)))
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Bypass OIDC metadata endpoints to prevent microservice validation lockout
        String uri = request.getRequestURI();
        if (uri != null && IGNORED_SUFFIXES.stream().anyMatch(uri::endsWith)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 1. Try to extract tenantId from the Header (IAM style)
        String tenantId = request.getHeader("X-Tenant-ID");

        // 2. If not found, extract from the URI path (OAuth2 style: /tenantId/...)
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = resolveTenantId(request.getRequestURI());
        }

        // If no tenant is resolved, allow it (could be an actuator or static resource)
        if (tenantId == null || tenantId.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        // The Redis key for this tenant's rate limit bucket
        byte[] key = ("rate_limit:tenant:" + tenantId).getBytes();

        try {
            // Retrieve or create the bucket for this tenant in Redis
            Bucket bucket = proxyManager.builder().build(key, defaultBucketConfig);

            // Try to consume 1 token
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

            // Add transparency headers
            response.addHeader("X-RateLimit-Limit", String.valueOf(this.burstPerMinute));
            response.addHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));

            if (probe.isConsumed()) {
                // Success: proceed
                filterChain.doFilter(request, response);
            } else {
                // Failure: bucket is empty
                log.warn("Rate limit exceeded for tenant '{}'. Throttling request.", tenantId);
                long waitForRefillSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000;

                // Set Retry-After and Reset headers
                response.addHeader("X-RateLimit-Retry-After", String.valueOf(waitForRefillSeconds));
                long resetTimestamp = System.currentTimeMillis() + (probe.getNanosToWaitForRefill() / 1_000_000);
                response.addHeader("X-RateLimit-Reset", String.valueOf(resetTimestamp));

                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write(
                        String.format(
                                "{\"error\": \"Too Many Requests\", \"message\": \"Rate limit exceeded. Try again in %d seconds.\"}",
                                waitForRefillSeconds));
            }
        } catch (Exception e) {
            // Graceful Degradation: If Redis fails, log the error and allow the request
            // (fail-open)
            log.error("Redis connection failure during rate limiting for tenant '{}'. Failing open.", tenantId, e);
            filterChain.doFilter(request, response);
        }
    }

    private String resolveTenantId(String uri) {
        if (uri == null || uri.equals("/"))
            return null;
        String[] parts = uri.split("/");
        for (String part : parts) {
            if (!part.isEmpty()) {
                // Skip common system, API, and static file prefixes
                if (IGNORED_PREFIXES.contains(part.toLowerCase())) {
                    return null;
                }
                return part;
            }
        }
        return null;
    }
}
