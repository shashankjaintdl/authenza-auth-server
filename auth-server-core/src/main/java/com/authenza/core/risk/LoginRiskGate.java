package com.authenza.core.risk;

import com.authenza.core.risk.stepup.StepUpAction;
import com.authenza.core.risk.stepup.StepUpCapabilityResolver;
import com.authenza.core.security.AuthPendingStateStore;
import com.authenza.core.util.ClientRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Post-credential adaptive gate used by password (and later passkey) success paths.
 * Keeps {@code SecurityConfig} thin and reusable across login completion points.
 */
@Service
public class LoginRiskGate {

    private static final Logger log = LoggerFactory.getLogger(LoginRiskGate.class);

    private final RiskEvaluationService riskEvaluationService;
    private final RiskContextFactory riskContextFactory;
    private final TrustedDeviceService trustedDeviceService;
    private final AuthPendingStateStore authPendingStateStore;
    private final RiskNotificationPublisher riskNotificationPublisher;
    private final AdaptiveAuthPolicy adaptiveAuthPolicy;
    private final StepUpCapabilityResolver stepUpCapabilityResolver;

    public LoginRiskGate(RiskEvaluationService riskEvaluationService,
                         RiskContextFactory riskContextFactory,
                         TrustedDeviceService trustedDeviceService,
                         AuthPendingStateStore authPendingStateStore,
                         RiskNotificationPublisher riskNotificationPublisher,
                         AdaptiveAuthPolicy adaptiveAuthPolicy,
                         StepUpCapabilityResolver stepUpCapabilityResolver) {
        this.riskEvaluationService = riskEvaluationService;
        this.riskContextFactory = riskContextFactory;
        this.trustedDeviceService = trustedDeviceService;
        this.authPendingStateStore = authPendingStateStore;
        this.riskNotificationPublisher = riskNotificationPublisher;
        this.adaptiveAuthPolicy = adaptiveAuthPolicy;
        this.stepUpCapabilityResolver = stepUpCapabilityResolver;
    }

    /**
     * Evaluates risk after credentials succeed and before policy MFA / session establishment.
     *
     * @return {@link GateOutcome} describing whether the caller should stop (BLOCK / STEP_UP)
     *         or continue with optional new-device notify flag
     */
    public GateOutcome evaluateAfterCredentials(String tenantId, String username, Long userId,
                                                HttpServletRequest request,
                                                HttpServletResponse response) throws IOException {
        // Fail-open at the gate: adaptive must never break password / MFA / OAuth login.
        try {
            if (tenantId == null || tenantId.isBlank() || userId == null) {
                return GateOutcome.proceed(false);
            }

            AdaptiveAuthPolicy.Snapshot policy = adaptiveAuthPolicy.load(tenantId);
            if (!policy.enabled()) {
                return GateOutcome.proceed(false);
            }

            RiskContext context = riskContextFactory.fromPasswordLogin(tenantId, username, userId, request);
            RiskEvaluationResult result = riskEvaluationService.evaluate(context);

            return switch (result.decision()) {
                case BLOCK -> handleBlock(tenantId, userId, result.score(), request, response);
                case STEP_UP -> handleStepUp(tenantId, username, userId, policy, request, response);
                case ALLOW -> GateOutcome.proceed(
                        result.newDeviceDetected() && policy.notifyNewDevice());
            };
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("[LoginRiskGate] Unexpected error — continuing normal login for tenant={}: {}",
                    tenantId, e.getMessage());
            return GateOutcome.proceed(false);
        }
    }

    /**
     * Completes adaptive side-effects once authentication is fully established
     * (password without MFA, or after MFA/step-up success).
     * Never throws — must not interrupt MFA completion or session recording.
     */
    public void onLoginFullyEstablished(String tenantId, Long userId, boolean notifyNewDevice,
                                        HttpServletRequest request, HttpServletResponse response) {
        if (userId == null || tenantId == null || tenantId.isBlank()) {
            return;
        }
        try {
            AdaptiveAuthPolicy.Snapshot policy = adaptiveAuthPolicy.load(tenantId);
            if (!policy.enabled()) {
                return;
            }
            trustedDeviceService.rememberDevice(tenantId, userId, request, response);
            if (notifyNewDevice) {
                riskNotificationPublisher.publishNewDeviceAlert(
                        tenantId,
                        userId,
                        ClientRequestUtils.parseDeviceName(ClientRequestUtils.resolveUserAgent(request)),
                        ClientRequestUtils.resolveIpAddress(request));
            }
        } catch (Exception e) {
            log.warn("[LoginRiskGate] Post-login adaptive side-effects skipped for userId={}: {}",
                    userId, e.getMessage());
        }
    }

    private GateOutcome handleBlock(String tenantId, Long userId, int score,
                                    HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        clearSecurityContext(request);
        log.warn("[LoginRiskGate] BLOCKED login tenant={} userId={} score={}",
                tenantId, userId, score);
        // Generic ?error — do not reveal which signal fired (enumeration resistance)
        response.sendRedirect("/" + tenantId + "/login?error");
        return GateOutcome.stop();
    }

    private GateOutcome handleStepUp(String tenantId, String username, Long userId,
                                     AdaptiveAuthPolicy.Snapshot policy,
                                     HttpServletRequest request,
                                     HttpServletResponse response) throws IOException {
        boolean notify = policy.notifyNewDevice();
        StepUpAction action = stepUpCapabilityResolver.resolve(username);

        return switch (action) {
            case COLLAPSE_INTO_POLICY_MFA -> GateOutcome.proceed(notify);
            case CHALLENGE_MFA_VERIFY -> {
                clearSecurityContext(request);
                redirectToStepUpChallenge(tenantId, username, userId, notify, request, response);
                yield GateOutcome.stop(notify);
            }
            case ALLOW_WITHOUT_ENROLLMENT -> {
                // OTP/MFA not enabled — do NOT force authenticator setup (Phase A).
                log.info("[LoginRiskGate] STEP_UP skipped (no MFA enrolled) tenant={} userId={} — notify={}",
                        tenantId, userId, notify);
                yield GateOutcome.proceed(notify);
            }
        };
    }

    private void redirectToStepUpChallenge(String tenantId, String username, Long userId,
                                           boolean notifyNewDevice,
                                           HttpServletRequest request,
                                           HttpServletResponse response) throws IOException {
        String mfaToken = authPendingStateStore.savePendingState(
                AuthPendingStateStore.TYPE_MFA,
                new AuthPendingStateStore.PendingState(
                        username, tenantId, userId, request.getParameter("tx"), notifyNewDevice));
        // Only enrolled users reach here — always verify, never force setup
        response.sendRedirect("/" + tenantId + "/mfa-verify?mfaToken=" + mfaToken);
    }

    private static void clearSecurityContext(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        }
    }

    public record GateOutcome(boolean halted, boolean notifyNewDevice) {
        public static GateOutcome stop() {
            return new GateOutcome(true, false);
        }

        public static GateOutcome stop(boolean notifyNewDevice) {
            return new GateOutcome(true, notifyNewDevice);
        }

        public static GateOutcome proceed(boolean notifyNewDevice) {
            return new GateOutcome(false, notifyNewDevice);
        }
    }
}
