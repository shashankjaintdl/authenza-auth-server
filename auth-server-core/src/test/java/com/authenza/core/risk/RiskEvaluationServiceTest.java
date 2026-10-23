package com.authenza.core.risk;

import com.authenza.common.enums.RiskDecision;
import com.authenza.common.enums.RiskFailMode;
import com.authenza.common.enums.RiskSignalId;
import com.authenza.core.risk.signal.RiskSignalEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskEvaluationServiceTest {

    private AdaptiveAuthPolicy policy;
    private AdaptiveAuthPolicy.Snapshot enabledSnapshot;

    @BeforeEach
    void setUp() {
        policy = mock(AdaptiveAuthPolicy.class);
        enabledSnapshot = new AdaptiveAuthPolicy.Snapshot(
                true, 40, 80, true, RiskFailMode.OPEN, 90, 30);
        when(policy.load(any())).thenReturn(enabledSnapshot);
    }

    @Test
    void returnsAllowWhenDisabled() {
        when(policy.load(any())).thenReturn(new AdaptiveAuthPolicy.Snapshot(
                false, 40, 80, true, RiskFailMode.OPEN, 90, 30));
        RiskEvaluationService service = new RiskEvaluationService(policy, List.of());
        RiskEvaluationResult result = service.evaluate(sampleContext());
        assertEquals(RiskDecision.ALLOW, result.decision());
        assertEquals(0, result.score());
    }

    @Test
    void stepsUpWhenScoreAtThreshold() {
        RiskSignalEvaluator evaluator = (ctx, pol) ->
                Optional.of(RiskSignal.of(RiskSignalId.NEW_DEVICE, "test"));
        RiskEvaluationService service = new RiskEvaluationService(policy, List.of(evaluator));
        RiskEvaluationResult result = service.evaluate(sampleContext());
        assertEquals(RiskDecision.STEP_UP, result.decision());
        assertEquals(40, result.score());
        assertTrue(result.newDeviceDetected());
    }

    @Test
    void blocksWhenScoreAtBlockThreshold() {
        RiskSignalEvaluator high = (ctx, pol) ->
                Optional.of(RiskSignal.of(RiskSignalId.NEW_DEVICE, 50, "a"));
        RiskSignalEvaluator mid = (ctx, pol) ->
                Optional.of(RiskSignal.of(RiskSignalId.NEW_IP, 30, "b"));
        RiskEvaluationService service = new RiskEvaluationService(policy, List.of(high, mid));
        RiskEvaluationResult result = service.evaluate(sampleContext());
        assertEquals(RiskDecision.BLOCK, result.decision());
        assertEquals(80, result.score());
    }

    @Test
    void clampsScoreToHundred() {
        RiskSignalEvaluator evaluator = (ctx, pol) ->
                Optional.of(RiskSignal.of(RiskSignalId.IMPOSSIBLE_TRAVEL, 150, "x"));
        RiskEvaluationService service = new RiskEvaluationService(policy, List.of(evaluator));
        RiskEvaluationResult result = service.evaluate(sampleContext());
        assertEquals(100, result.score());
        assertEquals(RiskDecision.BLOCK, result.decision());
    }

    @Test
    void decisionEngineMapsThresholds() {
        assertEquals(RiskDecision.ALLOW, RiskDecision.fromScore(39, 40, 80));
        assertEquals(RiskDecision.STEP_UP, RiskDecision.fromScore(40, 40, 80));
        assertEquals(RiskDecision.BLOCK, RiskDecision.fromScore(80, 40, 80));
        assertEquals(0, RiskDecisionEngine.clampScore(-10));
        assertEquals(100, RiskDecisionEngine.clampScore(200));
    }

    private static RiskContext sampleContext() {
        return new RiskContext(
                "demo", 1L, "user@example.com", "1.2.3.4", "Mozilla", null,
                "fp", false, 0, RiskContext.AuthMethod.PASSWORD);
    }
}
