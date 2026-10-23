package com.authenza.core.risk;

import com.authenza.common.enums.RiskFailMode;
import com.authenza.core.risk.stepup.StepUpAction;
import com.authenza.core.risk.stepup.StepUpCapabilityResolver;
import com.authenza.core.security.AuthPendingStateStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Ensures adaptive auth stays out of the way of existing login when disabled or broken.
 */
@ExtendWith(MockitoExtension.class)
class LoginRiskGateRegressionTest {

    @Mock RiskEvaluationService riskEvaluationService;
    @Mock RiskContextFactory riskContextFactory;
    @Mock TrustedDeviceService trustedDeviceService;
    @Mock AuthPendingStateStore authPendingStateStore;
    @Mock RiskNotificationPublisher riskNotificationPublisher;
    @Mock AdaptiveAuthPolicy adaptiveAuthPolicy;
    @Mock StepUpCapabilityResolver stepUpCapabilityResolver;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;

    private LoginRiskGate gate;

    @BeforeEach
    void setUp() {
        gate = new LoginRiskGate(
                riskEvaluationService,
                riskContextFactory,
                trustedDeviceService,
                authPendingStateStore,
                riskNotificationPublisher,
                adaptiveAuthPolicy,
                stepUpCapabilityResolver);
    }

    @Test
    void whenAdaptiveDisabled_continuesLoginWithoutRiskEval() throws Exception {
        when(adaptiveAuthPolicy.load("demo")).thenReturn(disabledPolicy());

        LoginRiskGate.GateOutcome outcome =
                gate.evaluateAfterCredentials("demo", "user@x.com", 1L, request, response);

        assertFalse(outcome.halted());
        assertFalse(outcome.notifyNewDevice());
        verifyNoInteractions(riskEvaluationService);
        verifyNoInteractions(riskContextFactory);
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void whenSettingsLoadFails_continuesLoginFailOpen() throws Exception {
        when(adaptiveAuthPolicy.load("demo")).thenThrow(new RuntimeException("cache down"));

        LoginRiskGate.GateOutcome outcome =
                gate.evaluateAfterCredentials("demo", "user@x.com", 1L, request, response);

        assertFalse(outcome.halted());
        verifyNoInteractions(riskEvaluationService);
    }

    @Test
    void whenNullUserId_continuesLogin() throws Exception {
        LoginRiskGate.GateOutcome outcome =
                gate.evaluateAfterCredentials("demo", "user@x.com", null, request, response);

        assertFalse(outcome.halted());
        verifyNoInteractions(adaptiveAuthPolicy);
        verifyNoInteractions(riskEvaluationService);
    }

    @Test
    void onLoginFullyEstablished_whenDisabled_skipsSideEffects() {
        when(adaptiveAuthPolicy.load("demo")).thenReturn(disabledPolicy());

        gate.onLoginFullyEstablished("demo", 1L, true, request, response);

        verifyNoInteractions(trustedDeviceService);
        verifyNoInteractions(riskNotificationPublisher);
    }

    @Test
    void onLoginFullyEstablished_neverThrows() {
        when(adaptiveAuthPolicy.load("demo")).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() ->
                gate.onLoginFullyEstablished("demo", 1L, true, request, response));
    }

    @Test
    void stepUpWithoutMfaEnrollment_doesNotForceSetup() throws Exception {
        when(adaptiveAuthPolicy.load("demo")).thenReturn(enabledPolicy());
        when(riskContextFactory.fromPasswordLogin(anyString(), anyString(), any(), any()))
                .thenReturn(sampleContext());
        when(riskEvaluationService.evaluate(any())).thenReturn(
                new RiskEvaluationResult(
                        com.authenza.common.enums.RiskDecision.STEP_UP,
                        50,
                        java.util.List.of(),
                        true));
        when(stepUpCapabilityResolver.resolve("user@x.com"))
                .thenReturn(StepUpAction.ALLOW_WITHOUT_ENROLLMENT);

        LoginRiskGate.GateOutcome outcome =
                gate.evaluateAfterCredentials("demo", "user@x.com", 1L, request, response);

        assertFalse(outcome.halted());
        assertTrue(outcome.notifyNewDevice());
        verify(response, never()).sendRedirect(anyString());
        verify(authPendingStateStore, never()).savePendingState(anyString(), any());
    }

    @Test
    void allow_continuesSoSecurityConfigCanRunPolicyMfa() throws Exception {
        when(adaptiveAuthPolicy.load("demo")).thenReturn(enabledPolicy());
        when(riskContextFactory.fromPasswordLogin(anyString(), anyString(), any(), any()))
                .thenReturn(sampleContext());
        when(riskEvaluationService.evaluate(any())).thenReturn(
                new RiskEvaluationResult(
                        com.authenza.common.enums.RiskDecision.ALLOW, 0, java.util.List.of(), false));

        LoginRiskGate.GateOutcome outcome =
                gate.evaluateAfterCredentials("demo", "user@x.com", 1L, request, response);

        // Same contract SecurityConfig expects: not halted → policy MFA / session path continues
        assertFalse(outcome.halted());
        assertFalse(outcome.notifyNewDevice());
        verify(response, never()).sendRedirect(anyString());
        verifyNoInteractions(stepUpCapabilityResolver);
    }

    @Test
    void stepUpWhenPolicyMfaRequired_collapsesWithoutSecondChallenge() throws Exception {
        when(adaptiveAuthPolicy.load("demo")).thenReturn(enabledPolicy());
        when(riskContextFactory.fromPasswordLogin(anyString(), anyString(), any(), any()))
                .thenReturn(sampleContext());
        when(riskEvaluationService.evaluate(any())).thenReturn(
                new RiskEvaluationResult(
                        com.authenza.common.enums.RiskDecision.STEP_UP, 55, java.util.List.of(), true));
        when(stepUpCapabilityResolver.resolve("user@x.com"))
                .thenReturn(StepUpAction.COLLAPSE_INTO_POLICY_MFA);

        LoginRiskGate.GateOutcome outcome =
                gate.evaluateAfterCredentials("demo", "user@x.com", 1L, request, response);

        // SecurityConfig still runs its existing MFA redirect — gate must not halt or double-redirect
        assertFalse(outcome.halted());
        assertTrue(outcome.notifyNewDevice());
        verify(response, never()).sendRedirect(anyString());
        verify(authPendingStateStore, never()).savePendingState(anyString(), any());
    }

    @Test
    void block_haltsAndRedirectsGenericLoginError() throws Exception {
        when(adaptiveAuthPolicy.load("demo")).thenReturn(enabledPolicy());
        when(riskContextFactory.fromPasswordLogin(anyString(), anyString(), any(), any()))
                .thenReturn(sampleContext());
        when(riskEvaluationService.evaluate(any())).thenReturn(
                new RiskEvaluationResult(
                        com.authenza.common.enums.RiskDecision.BLOCK, 90, java.util.List.of(), true));

        LoginRiskGate.GateOutcome outcome =
                gate.evaluateAfterCredentials("demo", "user@x.com", 1L, request, response);

        assertTrue(outcome.halted());
        verify(response).sendRedirect("/demo/login?error");
        verifyNoInteractions(stepUpCapabilityResolver);
        verify(authPendingStateStore, never()).savePendingState(anyString(), any());
    }

    private static AdaptiveAuthPolicy.Snapshot disabledPolicy() {
        return new AdaptiveAuthPolicy.Snapshot(
                false, 40, 80, true, RiskFailMode.OPEN, 90, 30);
    }

    private static AdaptiveAuthPolicy.Snapshot enabledPolicy() {
        return new AdaptiveAuthPolicy.Snapshot(
                true, 40, 80, true, RiskFailMode.OPEN, 90, 30);
    }

    private static RiskContext sampleContext() {
        return new RiskContext(
                "demo", 1L, "user@x.com", "1.2.3.4", "Mozilla", null,
                "fp", false, 0, RiskContext.AuthMethod.PASSWORD);
    }
}
