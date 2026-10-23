package com.authenza.core.risk.stepup;

import com.authenza.core.security.JdbcTenantUserDetailsService;
import org.springframework.stereotype.Component;

/**
 * Phase A resolver: policy MFA collapse, enrolled TOTP challenge, or allow-without-enrollment.
 */
@Component
public class MfaStepUpCapabilityResolver implements StepUpCapabilityResolver {

    private final JdbcTenantUserDetailsService userDetailsService;

    public MfaStepUpCapabilityResolver(JdbcTenantUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    public StepUpAction resolve(String username) {
        if (userDetailsService.isMfaRequired(username)) {
            return StepUpAction.COLLAPSE_INTO_POLICY_MFA;
        }
        if (userDetailsService.isMfaFullyEnrolled(username)) {
            return StepUpAction.CHALLENGE_MFA_VERIFY;
        }
        return StepUpAction.ALLOW_WITHOUT_ENROLLMENT;
    }
}
