package com.authenza.core.risk;

import com.authenza.core.security.BruteForceProtectionService;
import com.authenza.core.util.ClientRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Builds {@link RiskContext} from an HTTP login request.
 * Centralizes fingerprint / cookie / BF lookups so passkey/social hooks reuse the same path.
 */
@Component
public class RiskContextFactory {

    private final TrustedDeviceService trustedDeviceService;
    private final BruteForceProtectionService bruteForceProtectionService;

    public RiskContextFactory(TrustedDeviceService trustedDeviceService,
                              BruteForceProtectionService bruteForceProtectionService) {
        this.trustedDeviceService = trustedDeviceService;
        this.bruteForceProtectionService = bruteForceProtectionService;
    }

    public RiskContext fromPasswordLogin(String tenantId, String username, Long userId,
                                         HttpServletRequest request) {
        return fromRequest(tenantId, username, userId, request, RiskContext.AuthMethod.PASSWORD);
    }

    public RiskContext fromRequest(String tenantId, String username, Long userId,
                                   HttpServletRequest request, RiskContext.AuthMethod authMethod) {
        String fingerprint = trustedDeviceService.computeFingerprint(tenantId, userId, request);
        boolean cookieValid = trustedDeviceService.isTrustedCookieValid(
                tenantId, userId, fingerprint, request);
        int failures = bruteForceProtectionService.getFailedAttemptCount(username, tenantId);
        return new RiskContext(
                tenantId,
                userId,
                username,
                ClientRequestUtils.resolveIpAddress(request),
                ClientRequestUtils.resolveUserAgent(request),
                ClientRequestUtils.resolveSecChUa(request),
                fingerprint,
                cookieValid,
                failures,
                authMethod);
    }
}
