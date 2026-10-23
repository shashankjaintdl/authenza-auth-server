# Implementation Plan: Okta-Style Stateless OAuth2 Context Preservation

**Reference Design:** [stateless_oauth_context_design.md](file:///Users/shashankjain/Downloads/authenza-auth-server/stateless_oauth_context_design.md)  
**Module:** `auth-server-core`  
**Estimated Effort:** ~4–6 hours  

---

## Overview

This plan implements a 4-step upgrade that eliminates the `MISSING_AUTH_REQUEST` bug and adds full stateless context survival across pod restarts, session timeouts, MFA flows, and WebAuthn logins.

**Backward-compatibility guarantee:** Every change is additive. The `SavedRequest` (fast-path) remains the **first priority** in all success handlers. The `tx` token is the **fallback** only when `SavedRequest` is absent. No existing behavior is removed.

---

## Dependencies First: What to Add

Before writing any code, add one dependency to `auth-server-core/pom.xml`:

```xml
<!-- JWT / compact JSON signing — used by OAuthTransactionTokenService -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <!-- Already transitive via Spring Boot — no explicit version needed -->
</dependency>
```

> No new library is required. HMAC-SHA256 is available in the JDK (`javax.crypto.Mac`), and Jackson's `ObjectMapper` is already present via `AuthPendingStateStore`. The `tx` token will be a `Base64url(JSON payload) + "." + Base64url(HMAC)` — same compact format as the existing WebAuthn bridge token.

---

## Step 1 — Create `OAuthTransactionTokenService`

**New File:** [`auth-server-core/.../security/OAuthTransactionTokenService.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/security/OAuthTransactionTokenService.java)

### 1a. `OAuthTransactionContext` Record (inner or separate)

```java
package com.authenza.core.security;

import java.time.Instant;

/**
 * Immutable snapshot of the OAuth2 authorize request parameters that were
 * active when the user was redirected to the login page.
 *
 * Serialized into an HMAC-signed compact token (the {@code tx} parameter)
 * and passed client-side in the login form so context survives session expiry.
 */
public record OAuthTransactionContext(
        String tenantId,
        String clientId,
        String redirectUri,
        String scope,
        String state,
        String responseType,
        String codeChallenge,
        String codeChallengeMethod,
        Instant issuedAt,
        Instant expiresAt
) {}
```

### 1b. `OAuthTransactionTokenService` Bean

```java
package com.authenza.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * Creates and verifies compact HMAC-SHA256 signed tokens that carry the OAuth2
 * authorize request context across HTTP session boundaries.
 *
 * Token format:  Base64url(JSON) + "." + Base64url(HMAC-SHA256)
 *
 * Security properties:
 *  - Tamper detection: HMAC makes any field modification detectable.
 *  - Replay prevention: {@code expiresAt} is enforced (24-hour TTL by default).
 *  - Tenant binding: verifier asserts token.tenantId == expected tenantId.
 *  - Open-redirect guard: {@code redirect_uri} is stored inside the signed payload;
 *    caller MUST verify it against the registered client before redirecting.
 */
@Service
public class OAuthTransactionTokenService {

    private static final Logger log = LoggerFactory.getLogger(OAuthTransactionTokenService.class);
    private static final long TOKEN_TTL_HOURS = 24;

    private final ObjectMapper objectMapper;
    private final byte[] hmacKey;

    public OAuthTransactionTokenService(
            ObjectMapper objectMapper,
            @Value("${app.oauth-tx.hmac-secret}") String hmacSecret) {
        this.objectMapper = objectMapper;
        // Accept the secret as a hex string (consistent with app.webauthn.bridge-secret)
        this.hmacKey = HexFormat.of().parseHex(hmacSecret);
    }

    /**
     * Extracts OAuth2 parameters from the incoming /oauth2/authorize request,
     * encodes them into a signed compact token, and returns it.
     *
     * @param request  the original /oauth2/authorize HttpServletRequest
     * @param tenantId the active tenant (from path variable or TenantContextHolder)
     * @return Base64url(JSON).Base64url(HMAC) token string
     */
    public String signContext(HttpServletRequest request, String tenantId) {
        try {
            Instant now = Instant.now();
            OAuthTransactionContext ctx = new OAuthTransactionContext(
                    tenantId,
                    request.getParameter("client_id"),
                    request.getParameter("redirect_uri"),
                    request.getParameter("scope"),
                    request.getParameter("state"),
                    request.getParameter("response_type"),
                    request.getParameter("code_challenge"),
                    request.getParameter("code_challenge_method"),
                    now,
                    now.plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS)
            );

            String payloadJson  = objectMapper.writeValueAsString(ctx);
            String payloadB64   = Base64.getUrlEncoder().withoutPadding()
                                        .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signature    = hmacSign(payloadB64);

            return payloadB64 + "." + signature;
        } catch (Exception e) {
            log.error("[OAuthTx] Failed to sign transaction context: {}", e.getMessage());
            throw new IllegalStateException("Cannot sign OAuth transaction context.", e);
        }
    }

    /**
     * Verifies the token's HMAC signature, expiry, and tenant binding.
     *
     * @param token            the compact token received from the login form
     * @param expectedTenantId the tenant derived from the request path (/{tenantId}/login)
     * @return verified context, or empty if invalid/expired/tampered/mismatched tenant
     */
    public Optional<OAuthTransactionContext> verifyContext(String token, String expectedTenantId) {
        if (token == null || token.isBlank()) return Optional.empty();

        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) return Optional.empty();

            String payloadB64 = parts[0];
            String receivedSig = parts[1];

            // 1. Constant-time HMAC check
            String expectedSig = hmacSign(payloadB64);
            if (!constantTimeEquals(expectedSig, receivedSig)) {
                log.warn("[OAuthTx] HMAC mismatch — token tampered.");
                return Optional.empty();
            }

            // 2. Decode and parse payload
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
            OAuthTransactionContext ctx = objectMapper.readValue(payloadJson, OAuthTransactionContext.class);

            // 3. Expiry check
            if (Instant.now().isAfter(ctx.expiresAt())) {
                log.warn("[OAuthTx] Token expired for tenant={}", ctx.tenantId());
                return Optional.empty();
            }

            // 4. Tenant binding assertion
            if (!expectedTenantId.equals(ctx.tenantId())) {
                log.warn("[OAuthTx] Tenant mismatch: token={}, expected={}", ctx.tenantId(), expectedTenantId);
                return Optional.empty();
            }

            return Optional.of(ctx);
        } catch (Exception e) {
            log.warn("[OAuthTx] Token verification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reconstructs the full /oauth2/authorize URL from a verified context.
     * The caller redirects to this URL after setting the Spring SecurityContext.
     */
    public String buildAuthorizeUrl(OAuthTransactionContext ctx) {
        org.springframework.web.util.UriComponentsBuilder builder =
                org.springframework.web.util.UriComponentsBuilder
                        .fromPath("/" + ctx.tenantId() + "/oauth2/authorize")
                        .queryParam("response_type",   ctx.responseType() != null ? ctx.responseType() : "code")
                        .queryParam("client_id",        ctx.clientId())
                        .queryParam("redirect_uri",     ctx.redirectUri())
                        .queryParam("scope",            ctx.scope());

        if (ctx.state() != null)               builder.queryParam("state",                 ctx.state());
        if (ctx.codeChallenge() != null)       builder.queryParam("code_challenge",        ctx.codeChallenge());
        if (ctx.codeChallengeMethod() != null) builder.queryParam("code_challenge_method", ctx.codeChallengeMethod());

        return builder.build().toUriString();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private String hmacSign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed.", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }
}
```

### 1c. Add property to `application.yml` / `application.properties`

```yaml
app:
  oauth-tx:
    hmac-secret: "<64-char hex string — generate with: openssl rand -hex 32>"
  webauthn:
    bridge-secret: "<existing value>"
```

> **Generate secrets:** `openssl rand -hex 32` produces a 256-bit hex key. Never commit this value; inject via environment variable or Kubernetes Secret.

---

## Step 2 — Update `TenantAwareAuthenticationEntryPoint`

**File:** [`TenantAwareAuthenticationEntryPoint.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/security/TenantAwareAuthenticationEntryPoint.java) (currently 47 lines)

### What to change

Inject `OAuthTransactionTokenService` via a setter (the class is instantiated with `new` inside `SecurityConfig`, so setter injection is the cleanest approach).

**Add a setter and update `buildRedirectUrlToLoginPage`:**

```diff
 public final class TenantAwareAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

+    private OAuthTransactionTokenService transactionTokenService;
+
     public TenantAwareAuthenticationEntryPoint(String loginFormUrl) {
         super(loginFormUrl);
     }

+    /** Called by SecurityConfig after construction to inject the service. */
+    public void setTransactionTokenService(OAuthTransactionTokenService svc) {
+        this.transactionTokenService = svc;
+    }

     @Override
     protected String buildRedirectUrlToLoginPage(HttpServletRequest request,
                                                  HttpServletResponse response,
                                                  AuthenticationException authException) {

         String tenantId = TenantContextHolder.getTenantId();
         if (tenantId == null || tenantId.isBlank()) {
             tenantId = resolveTenantFromUri(request.getRequestURI());
         }

         if (tenantId != null && !tenantId.isBlank()) {
             String loginFormUrl = getLoginFormUrl().replace("{tenantId}", tenantId);
+            // Append signed tx token if this is an /oauth2/authorize redirect
+            if (transactionTokenService != null && isOAuthAuthorizeRequest(request)) {
+                try {
+                    String tx = transactionTokenService.signContext(request, tenantId);
+                    return loginFormUrl + "?tx=" + java.net.URLEncoder.encode(tx, java.nio.charset.StandardCharsets.UTF_8);
+                } catch (Exception e) {
+                    // Non-fatal: fall through to plain login URL
+                }
+            }
             return loginFormUrl;
         }

         return "/error/invalid-tenant";
     }

+    private static boolean isOAuthAuthorizeRequest(HttpServletRequest request) {
+        String uri = request.getRequestURI();
+        return uri != null && uri.contains("/oauth2/authorize");
+    }
+
     private String resolveTenantFromUri(String uri) { ... }
 }
```

**Update `SecurityConfig` bean instantiation** (in `defaultSecurityFilterChain`, line ~95):

```diff
-new TenantAwareAuthenticationEntryPoint("/{tenantId}/login")
+tenantAwareAuthenticationEntryPoint()
```

Add a new `@Bean`:

```java
@Bean
public TenantAwareAuthenticationEntryPoint tenantAwareAuthenticationEntryPoint(
        OAuthTransactionTokenService oauthTransactionTokenService) {
    TenantAwareAuthenticationEntryPoint ep =
            new TenantAwareAuthenticationEntryPoint("/{tenantId}/login");
    ep.setTransactionTokenService(oauthTransactionTokenService);
    return ep;
}
```

And update the `exceptionHandling` block:

```java
.exceptionHandling(exceptions -> exceptions
    .authenticationEntryPoint(tenantAwareAuthenticationEntryPoint(oauthTransactionTokenService))
    ...
)
```

---

## Step 3 — Update `LoginController`, `login.html`, and `SecurityConfig.accessDeniedHandler`

### 3a. `LoginController.java` — Accept `tx` parameter

**File:** [`LoginController.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/web/LoginController.java)

**Constructor:** Inject `OAuthTransactionTokenService`.

```diff
 public LoginController(SuperAdminClientProperties superAdminClientProperties,
         RegisteredClientRepository registeredClientRepository,
-        com.authenza.adapter.cache.TenantSettingsCache settingsCache) {
+        com.authenza.adapter.cache.TenantSettingsCache settingsCache,
+        OAuthTransactionTokenService oauthTransactionTokenService) {
     this.superAdminClientProperties = superAdminClientProperties;
     this.registeredClientRepository = registeredClientRepository;
     this.settingsCache = settingsCache;
+    this.oauthTransactionTokenService = oauthTransactionTokenService;
 }
```

**`loginPage` method signature** — add `tx` parameter:

```diff
 @GetMapping("/{tenantId}/login")
 public String loginPage(@PathVariable String tenantId,
         @RequestParam(name = "error",           required = false) String error,
         @RequestParam(name = "logout",          required = false) String logout,
         @RequestParam(name = "session_expired", required = false) String sessionExpired,
+        @RequestParam(name = "tx",              required = false) String tx,
         HttpServletRequest request,
         ...
```

**Key logic update** — the critical gate at line 106 that currently shows `MISSING_AUTH_REQUEST`:

```diff
 if (error == null && logout == null && !isSessionExpired) {
     HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
     SavedRequest savedRequest = requestCache.getRequest(request, null);

     if (savedRequest == null) {
-        // No pending OAuth2 request — user accessed login page directly
-        model.addAttribute("errorCode", "MISSING_AUTH_REQUEST");
-        return "error-page";
+        // No session-backed saved request — check if we have a stateless tx token
+        if (tx == null || tx.isBlank() ||
+                oauthTransactionTokenService.verifyContext(tx, tenantId).isEmpty()) {
+            // Genuinely no OAuth2 context — block direct navigation
+            model.addAttribute("title",     "Unauthorized Access");
+            model.addAttribute("message",   "Login page can only be accessed through a valid OAuth2 client authorization flow.");
+            model.addAttribute("errorCode", "MISSING_AUTH_REQUEST");
+            return "error-page";
+        }
+        // Valid tx token present — allow the login page to render
     }
 }

 model.addAttribute("tenantId",      tenantId);
 model.addAttribute("sessionExpired", isSessionExpired);
+model.addAttribute("tx",            tx);   // pass to template
```

> **Why this matters:** This single change converts the `MISSING_AUTH_REQUEST` wall into a valid login page whenever a legitimate `tx` token is present — fixing the core bug.

### 3b. `login.html` — Render hidden `tx` input in the form

**File:** [`login.html`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/resources/templates/login.html)

Inside the `<form>` that POSTs to `/{tenantId}/login`, add:

```html
<!-- Stateless OAuth2 transaction context: survives session expiry -->
<input type="hidden" name="tx" th:value="${tx}" th:if="${tx != null and !#strings.isEmpty(tx)}" />
```

This ensures `tx` travels from the GET login page → through the POST login form → to the success handler.

### 3c. `SecurityConfig.accessDeniedHandler` — Preserve `tx` across CSRF failures

**File:** [`SecurityConfig.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/security/SecurityConfig.java) (lines 97–106)

When a CSRF rejection fires (session expired but form POSTed), extract the `tx` from the POST body and re-append it:

```diff
 .accessDeniedHandler((request, response, accessDeniedException) -> {
     String tenantId = TenantContextHolder.getTenantId();
     if (tenantId == null || tenantId.isBlank()) {
         tenantId = resolveTenantFromUri(request.getRequestURI());
     }
-    String loginUrl = (tenantId != null && !tenantId.isBlank())
-            ? "/" + tenantId + "/login?session_expired"
-            : "/login?session_expired";
-    response.sendRedirect(loginUrl);
+    String base = (tenantId != null && !tenantId.isBlank())
+            ? "/" + tenantId + "/login?session_expired"
+            : "/login?session_expired";
+    // Preserve the tx token from the POST body so context survives the CSRF redirect
+    String tx = request.getParameter("tx");
+    String txParam = (tx != null && !tx.isBlank())
+            ? "&tx=" + java.net.URLEncoder.encode(tx, java.nio.charset.StandardCharsets.UTF_8)
+            : "";
+    response.sendRedirect(base + txParam);
 })
```

---

## Step 4 — Update Success Handlers

### 4a. `SecurityConfig.tenantAwareAuthenticationSuccessHandler`

**File:** [`SecurityConfig.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/security/SecurityConfig.java) (lines 144–236)

Inject `OAuthTransactionTokenService` into the `SecurityConfig` constructor and use it in the success handler.

**Constructor change:**

```diff
 public SecurityConfig(BruteForceProtectionService bruteForceProtectionService,
         JdbcTenantUserDetailsService userDetailsService,
         MfaAuthenticationFilter mfaAuthenticationFilter,
         SessionRecordingService sessionRecordingService,
-        AuthPendingStateStore authPendingStateStore) {
+        AuthPendingStateStore authPendingStateStore,
+        OAuthTransactionTokenService oauthTransactionTokenService) {
```

**Success handler — replace the bottom block (lines 216–233):**

```diff
-// Check if there's a saved request (from OAuth2 authorize redirect)
-HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
-SavedRequest savedRequest = requestCache.getRequest(request, response);
-
-if (savedRequest != null) {
-    super.onAuthenticationSuccess(request, response, authentication);
-    return;
-}
-
-// No saved request — user logged in directly
-if (tenantId != null && !tenantId.isBlank()) {
-    getRedirectStrategy().sendRedirect(request, response, "/" + tenantId + "/");
-} else {
-    getRedirectStrategy().sendRedirect(request, response, "/");
-}

+// ── Priority 1: Session SavedRequest (normal short-lived login) ──────────
+HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
+SavedRequest savedRequest = requestCache.getRequest(request, response);
+if (savedRequest != null) {
+    super.onAuthenticationSuccess(request, response, authentication);
+    return;
+}
+
+// ── Priority 2: Stateless tx token (survived session expiry) ─────────────
+String tx = request.getParameter("tx");
+if (tx != null && !tx.isBlank() && tenantId != null) {
+    Optional<OAuthTransactionContext> ctxOpt =
+            oauthTransactionTokenService.verifyContext(tx, tenantId);
+    if (ctxOpt.isPresent()) {
+        String authorizeUrl = oauthTransactionTokenService.buildAuthorizeUrl(ctxOpt.get());
+        log.info("[TxReplay] Replaying OAuth2 authorize for tenant={}, client={}",
+                tenantId, ctxOpt.get().clientId());
+        getRedirectStrategy().sendRedirect(request, response, authorizeUrl);
+        return;
+    }
+}
+
+// ── Priority 3: Tenant root fallback ─────────────────────────────────────
+getRedirectStrategy().sendRedirect(request, response,
+        (tenantId != null && !tenantId.isBlank()) ? "/" + tenantId + "/" : "/");
```

### 4b. `MfaAuthenticationFilter.java` — Carry `tx` through MFA

**File:** [`MfaAuthenticationFilter.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/security/MfaAuthenticationFilter.java) (lines 190–196)

The `tx` token must survive the MFA detour. It currently lives in the POST body of the login form → gets written to `mfa-verify` form → returns here after TOTP validation.

**Inject `OAuthTransactionTokenService` into the filter constructor.**

**Update `PendingState` to carry `tx`** — add a field:

```diff
-public record PendingState(String username, String tenantId, Long userId) {
+public record PendingState(String username, String tenantId, Long userId, String oauthTx) {
```

> This is a backward-compatible change because Jackson uses `@JsonProperty` field names; the existing Redis entries without `oauthTx` will deserialize with `null`.

**In `SecurityConfig.tenantAwareAuthenticationSuccessHandler` — MFA gate block (lines 188–202):**

```diff
 String mfaToken = authPendingStateStore.savePendingState(
         AuthPendingStateStore.TYPE_MFA,
-        new AuthPendingStateStore.PendingState(username, tenantId, userId));
+        new AuthPendingStateStore.PendingState(username, tenantId, userId,
+                request.getParameter("tx")));   // carry tx into MFA state
```

**In `MfaAuthenticationFilter.doFilterInternal` — replace the final redirect (lines 172–182):**

```diff
-// ── 6. Follow saved request or fall back ──────────────────────────────────
-HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
-SavedRequest savedRequest = requestCache.getRequest(request, response);
-if (savedRequest != null) {
-    String targetUrl = savedRequest.getRedirectUrl();
-    requestCache.removeRequest(request, response);
-    response.sendRedirect(targetUrl);
-} else {
-    response.sendRedirect("/" + tenantId + "/");
-}

+// ── 6. Follow saved request → tx replay → tenant root ────────────────────
+HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
+SavedRequest savedRequest = requestCache.getRequest(request, response);
+if (savedRequest != null) {
+    requestCache.removeRequest(request, response);
+    response.sendRedirect(savedRequest.getRedirectUrl());
+    return;
+}
+
+// Stateless tx fallback: restore OAuth2 context from PendingState
+String oauthTx = pendingState.oauthTx();
+if (oauthTx != null && !oauthTx.isBlank()) {
+    Optional<OAuthTransactionContext> ctxOpt =
+            oauthTransactionTokenService.verifyContext(oauthTx, tenantId);
+    if (ctxOpt.isPresent()) {
+        response.sendRedirect(oauthTransactionTokenService.buildAuthorizeUrl(ctxOpt.get()));
+        return;
+    }
+}
+
+response.sendRedirect("/" + tenantId + "/");
```

### 4c. `WebAuthnBridgeController.java` — Carry `tx` through WebAuthn

**File:** [`WebAuthnBridgeController.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/web/WebAuthnBridgeController.java)

**Add `@RequestParam` for `tx`** (line 100):

```diff
 @PostMapping("/{tenantId}/webauthn/bridge")
 public String completeBridgeLogin(
         @PathVariable String tenantId,
         @RequestParam("bridgeToken") String bridgeToken,
+        @RequestParam(name = "tx", required = false) String tx,
         HttpServletRequest request,
         HttpServletResponse response) {
```

**Replace the final redirect block (lines 183–192):**

```diff
-// ── 6. Redirect to complete the OAuth2 authorize flow ───────────────────
-HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
-SavedRequest savedRequest = requestCache.getRequest(request, response);
-if (savedRequest != null) {
-    return "redirect:" + savedRequest.getRedirectUrl();
-}
-return "redirect:/" + tenantId + "/";

+// ── 6. Priority 1: SavedRequest (normal flow) ──────────────────────────
+HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
+SavedRequest savedRequest = requestCache.getRequest(request, response);
+if (savedRequest != null) {
+    return "redirect:" + savedRequest.getRedirectUrl();
+}
+
+// ── Priority 2: tx token (stateless recovery) ──────────────────────────
+if (tx != null && !tx.isBlank()) {
+    Optional<OAuthTransactionContext> ctxOpt =
+            oauthTransactionTokenService.verifyContext(tx, tenantId);
+    if (ctxOpt.isPresent()) {
+        log.info("[WebAuthn Bridge] Replaying OAuth2 authorize via tx for tenant={}", tenantId);
+        return "redirect:" + oauthTransactionTokenService.buildAuthorizeUrl(ctxOpt.get());
+    }
+}
+
+// ── Priority 3: Tenant root fallback ───────────────────────────────────
+return "redirect:/" + tenantId + "/";
```

**Inject `OAuthTransactionTokenService` into the constructor:**

```java
public WebAuthnBridgeController(
        JdbcTenantUserDetailsService userDetailsService,
        SessionRecordingService sessionRecordingService,
        AuthPendingStateStore authPendingStateStore,
        OAuthTransactionTokenService oauthTransactionTokenService,
        @Value("${app.webauthn.bridge-secret}") String bridgeSecret) {
```

---

## Step 5 — Tests (Acceptance Criteria)

### 5a. `OAuthTransactionTokenServiceTest` (Unit)

| Test | Assertion |
|------|-----------|
| `signAndVerify_roundTrip` | `verifyContext(signContext(request, "t1"), "t1")` returns non-empty with all fields matching |
| `verify_rejectsExpiredToken` | Token with `expiresAt` in the past → `Optional.empty()` |
| `verify_rejectsTamperedPayload` | Flip one char in payload → `Optional.empty()` |
| `verify_rejectsTenantMismatch` | Sign for `"t1"`, verify with `"t2"` → `Optional.empty()` |
| `verify_rejectsNullToken` | `null` input → `Optional.empty()` |

### 5b. Integration / E2E

| AC | Scenario |
|----|----------|
| **AC-1** | Unit tests above |
| **AC-2** | Playwright/Selenium: open `/oauth2/authorize`, wait (or mock session expiry), submit credentials, verify redirect arrives at `redirect_uri` with `?code=` |
| **AC-3** | Deploy two pods behind a round-robin LB; login on Pod A, TOTP on Pod B — succeeds |
| **AC-4** | E2E test: enable MFA for test user, trigger stateless tx flow, complete TOTP, verify code grant |

---

## Execution Order & File Summary

| Order | File | Action |
|-------|------|--------|
| 1 | `application.yml` | Add `app.oauth-tx.hmac-secret` property |
| 2 | **NEW** `OAuthTransactionTokenService.java` | Create signing service |
| 3 | **NEW** `OAuthTransactionContext.java` | Create record (or nest in service) |
| 4 | `AuthPendingStateStore.PendingState` | Add `oauthTx` field |
| 5 | `TenantAwareAuthenticationEntryPoint.java` | Add setter + tx appending logic |
| 6 | `SecurityConfig.java` | Inject service; update entry point bean, accessDeniedHandler, successHandler |
| 7 | `LoginController.java` | Inject service; accept `tx` param; gate update |
| 8 | `login.html` | Add hidden `<input name="tx">` |
| 9 | `MfaAuthenticationFilter.java` | Inject service; carry tx through pendingState; update final redirect |
| 10 | `WebAuthnBridgeController.java` | Inject service; accept tx param; update final redirect |
| 11 | `OAuthTransactionTokenServiceTest.java` | Write unit tests |

---

## Risk Register

| Risk | Mitigation |
|------|-----------|
| Large `tx` token in URL (Base64 JSON can be ~400 chars) | Within HTTP spec limits; no issue for modern browsers |
| `tx` token leaked via Referer header | HTTPS + `Referrer-Policy: strict-origin` already in place |
| Old Redis `PendingState` without `oauthTx` field | Jackson deserializes missing fields as `null`; code guards `!= null` |
| PKCE `code_challenge` in tx token (64 chars) exposed client-side | PKCE is designed to be public; no secret is transmitted |
| MFA brute-force with replayed tx token | tx has no auth value on its own; the TOTP code is the gate |
