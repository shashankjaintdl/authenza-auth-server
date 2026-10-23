package com.authenza.core.risk;

import com.authenza.core.util.ClientRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Facade for trusted-device persistence and cookie lifecycle after successful login.
 */
@Service
public class TrustedDeviceService {

    private static final Logger log = LoggerFactory.getLogger(TrustedDeviceService.class);

    private final TrustedDeviceRepository repository;
    private final TrustedDeviceCookieService cookieService;
    private final DeviceFingerprintService fingerprintService;
    private final AdaptiveAuthPolicy adaptiveAuthPolicy;

    public TrustedDeviceService(TrustedDeviceRepository repository,
                                TrustedDeviceCookieService cookieService,
                                DeviceFingerprintService fingerprintService,
                                AdaptiveAuthPolicy adaptiveAuthPolicy) {
        this.repository = repository;
        this.cookieService = cookieService;
        this.fingerprintService = fingerprintService;
        this.adaptiveAuthPolicy = adaptiveAuthPolicy;
    }

    public String computeFingerprint(String tenantId, Long userId, HttpServletRequest request) {
        return fingerprintService.fingerprint(
                tenantId,
                userId,
                ClientRequestUtils.resolveUserAgent(request),
                ClientRequestUtils.resolveSecChUa(request));
    }

    /**
     * Cookie alone is not enough — the DB row must still be active (not revoked / expired).
     * Revoking a device therefore takes effect on the next login even if the cookie remains.
     */
    public boolean isTrustedCookieValid(String tenantId, Long userId, String fingerprint,
                                        HttpServletRequest request) {
        if (cookieService.readValidCookie(request, tenantId, userId, fingerprint).isEmpty()) {
            return false;
        }
        return repository.existsActive(userId, fingerprint);
    }

    public void rememberDevice(String tenantId, Long userId, HttpServletRequest request,
                               HttpServletResponse response) {
        try {
            AdaptiveAuthPolicy.Snapshot policy = adaptiveAuthPolicy.load(tenantId);
            String fingerprint = computeFingerprint(tenantId, userId, request);
            String ip = ClientRequestUtils.resolveIpAddress(request);
            String deviceName = ClientRequestUtils.parseDeviceName(
                    ClientRequestUtils.resolveUserAgent(request));
            Instant expiresAt = Instant.now().plus(policy.trustDeviceDays(), ChronoUnit.DAYS);
            repository.upsertTrustedDevice(userId, fingerprint, deviceName, ip, expiresAt);
            cookieService.writeCookie(response, tenantId, userId, fingerprint, policy.trustDeviceDays());
        } catch (Exception e) {
            log.warn("[TrustedDevice] rememberDevice failed for userId={}: {}", userId, e.getMessage());
        }
    }

    public List<TrustedDeviceRepository.TrustedDeviceRow> listDevices(Long userId) {
        return repository.listActive(userId);
    }

    public boolean revokeDevice(Long userId, Long deviceId) {
        return repository.revoke(userId, deviceId);
    }
}
