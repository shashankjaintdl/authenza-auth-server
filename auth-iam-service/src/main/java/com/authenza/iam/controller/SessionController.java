package com.authenza.iam.controller;

import com.authenza.common.constant.AuthenzaConstant;
import com.authenza.common.dto.ApiResponse;
import com.authenza.iam.dto.SessionResponse;
import com.authenza.iam.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for Active Session Management (Active Devices feature).
 *
 * <p>All endpoints are scoped under {@code /api/v1/users/{userId}/sessions}.
 * In a secured deployment, {@code userId} should be extracted from the JWT
 * principal and cross-checked against the caller (RBAC guards — Phase 4).
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET    /users/{userId}/sessions}          — list active sessions</li>
 *   <li>{@code DELETE /users/{userId}/sessions/{id}}     — revoke one session</li>
 *   <li>{@code DELETE /users/{userId}/sessions}          — revoke all sessions</li>
 * </ul>
 */
@RestController
@RequestMapping(AuthenzaConstant.API_VERSION + "/users/{userId}/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    // ─────────────────────────────────────────────
    // List active sessions
    // ─────────────────────────────────────────────

    /**
     * Returns all active (non-revoked) sessions for a user.
     *
     * <pre>GET /api/v1/users/{userId}/sessions</pre>
     *
     * Response example:
     * <pre>{@code
     * {
     *   "status": 200,
     *   "message": "Active sessions retrieved successfully.",
     *   "data": [
     *     { "id": 3, "deviceName": "Chrome on macOS", "ipAddress": "103.x.x.x",
     *       "createdAt": "...", "lastActiveAt": "...", "current": true },
     *     { "id": 1, "deviceName": "Firefox on Windows", "ipAddress": "45.x.x.x",
     *       "createdAt": "...", "lastActiveAt": "...", "current": false }
     *   ]
     * }
     * }</pre>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<SessionResponse>>> listSessions(
            @PathVariable Long userId) {
        List<SessionResponse> sessions = sessionService.listActiveSessions(userId);
        return ResponseEntity.ok(
                ApiResponse.success(sessions, "Active sessions retrieved successfully."));
    }

    // ─────────────────────────────────────────────
    // Revoke a single session
    // ─────────────────────────────────────────────

    /**
     * Revokes a specific session by ID.
     * Marks the session as revoked and deletes the linked OAuth2 authorization
     * to prevent refresh-token reuse.
     *
     * <pre>DELETE /api/v1/users/{userId}/sessions/{sessionId}</pre>
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeSession(
            @PathVariable Long userId,
            @PathVariable Long sessionId) {
        sessionService.revokeSession(userId, sessionId);
        return ResponseEntity.ok(
                ApiResponse.success(
                        Map.of("revokedSessionId", sessionId),
                        "Session revoked successfully. The device will need to log in again."));
    }

    // ─────────────────────────────────────────────
    // Revoke all sessions
    // ─────────────────────────────────────────────

    /**
     * Revokes all active sessions for a user — equivalent to "Sign out everywhere".
     *
     * <pre>DELETE /api/v1/users/{userId}/sessions</pre>
     */
    @DeleteMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeAllSessions(
            @PathVariable Long userId) {
        int count = sessionService.revokeAllSessions(userId);
        return ResponseEntity.ok(
                ApiResponse.success(
                        Map.of("revokedCount", count),
                        count + " session(s) revoked. All devices will need to log in again."));
    }
}
