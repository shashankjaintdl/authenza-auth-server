package com.authenza.core.risk.stepup;

import com.authenza.core.security.JdbcTenantUserDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaStepUpCapabilityResolverTest {

    @Mock JdbcTenantUserDetailsService userDetailsService;

    @Test
    void collapsesWhenPolicyRequiresMfa() {
        when(userDetailsService.isMfaRequired("u")).thenReturn(true);
        assertEquals(StepUpAction.COLLAPSE_INTO_POLICY_MFA,
                new MfaStepUpCapabilityResolver(userDetailsService).resolve("u"));
    }

    @Test
    void challengesWhenEnrolled() {
        when(userDetailsService.isMfaRequired("u")).thenReturn(false);
        when(userDetailsService.isMfaFullyEnrolled("u")).thenReturn(true);
        assertEquals(StepUpAction.CHALLENGE_MFA_VERIFY,
                new MfaStepUpCapabilityResolver(userDetailsService).resolve("u"));
    }

    @Test
    void allowsWithoutEnrollment() {
        when(userDetailsService.isMfaRequired("u")).thenReturn(false);
        when(userDetailsService.isMfaFullyEnrolled("u")).thenReturn(false);
        assertEquals(StepUpAction.ALLOW_WITHOUT_ENROLLMENT,
                new MfaStepUpCapabilityResolver(userDetailsService).resolve("u"));
    }
}
