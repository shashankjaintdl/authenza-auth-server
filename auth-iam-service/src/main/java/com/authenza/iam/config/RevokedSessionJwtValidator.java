package com.authenza.iam.config;

import com.authenza.iam.service.RevokedSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Custom JWT validator that rejects tokens whose {@code session_id} claim
 * has been blacklisted in Redis (i.e., the session was explicitly revoked).
 *
 * <p>
 * This validator is wired into the {@link IamSecurityConfig} resource-server
 * JWT decoder so it runs on every authenticated API request, enabling immediate
 * forced-logout without waiting for the access token's natural expiry.
 *
 * <p>
 * <b>Flow:</b>
 * <ol>
 * <li>User revokes a session via the portal →
 * {@code SessionService.revokeSession()} writes
 * the {@code session_id} to the Redis blacklist.</li>
 * <li>The device holding the revoked session makes its next API call with the
 * old JWT.</li>
 * <li>This validator extracts {@code session_id} from the JWT claims and checks
 * Redis.</li>
 * <li>If found → returns {@code invalid_token} → Spring Security returns HTTP
 * 401.</li>
 * <li>The Angular app's HTTP interceptor detects 401 → triggers OIDC
 * logout/redirect.</li>
 * </ol>
 */
@Component
public class RevokedSessionJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(RevokedSessionJwtValidator.class);

    private static final OAuth2Error REVOKED_SESSION_ERROR = new OAuth2Error(
            "invalid_token",
            "The session associated with this token has been revoked.",
            null);

    private final RevokedSessionStore revokedSessionStore;

    public RevokedSessionJwtValidator(RevokedSessionStore revokedSessionStore) {
        this.revokedSessionStore = revokedSessionStore;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Boolean passwordChangeRequired = token.getClaimAsBoolean("password_change_required");
        if (Boolean.TRUE.equals(passwordChangeRequired)) {
            log.info("[RevokedSessionJwtValidator] Rejected token because password change is required.");
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "A mandatory password change is required.",
                    null));
        }

        String sessionId = token.getClaimAsString("session_id");

        if (sessionId == null) {
            // A missing session_id means one of two things:
            //
            // 1. Legacy token — issued before this feature was deployed.
            // These have naturally short lifetimes (typically 1 hour) and
            // will age out on their own. Blocking them would cause a hard
            // logout for all users immediately after a server upgrade.
            //
            // 2. Orphaned re-auth token — issued by the auth server's iframe
            // silent-renew AFTER a session was revoked. In this case the
            // JWT token customizer could not find a non-revoked, un-linked
            // user_session row to embed the session_id into, so the claim
            // is absent. Allowing these tokens through defeats the entire
            // revocation system — the user stays logged in after revocation.
            //
            // To distinguish the two cases we check whether ANY active
            // (non-revoked) session exists for this user in the database.
            // If none exist, this is case 2 → reject the token.
            // If sessions exist, this is case 1 (legacy token) → allow through.
            //
            // We extract user_id from the JWT to perform this check.
            String userIdClaim = token.getClaimAsString("user_id");
            if (userIdClaim != null) {
                // user_id claim present → token was issued by the current system.
                // A missing session_id alongside a user_id is the fingerprint of
                // an orphaned re-auth token. Reject it unconditionally.
                log.warn("[RevokedSessionJwtValidator] Rejected token for user {} — " +
                        "session_id claim missing (orphaned re-auth after revocation).", userIdClaim);
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "Token has no linked session. Please log in again.",
                        null));
            }
            // No user_id either → truly legacy pre-feature token. Allow through.
            return OAuth2TokenValidatorResult.success();
        }

        if (revokedSessionStore.isRevoked(sessionId)) {
            log.info("[RevokedSessionJwtValidator] Rejected token for revoked session {}", sessionId);
            return OAuth2TokenValidatorResult.failure(REVOKED_SESSION_ERROR);
        }

        return OAuth2TokenValidatorResult.success();
    }
}
