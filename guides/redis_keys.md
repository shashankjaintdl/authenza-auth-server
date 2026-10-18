# Redis Architecture & Key Documentation

Authenza uses **Redis** as a high-performance, shared-state database and event bus. Because the auth server needs to scale horizontally across multiple instances (pods) without sticky routing, Redis serves as the single source of truth for rate limiting, active session blacklisting, brute-force protection counters, and inter-service messaging.

---

## 1. Redis Key Namespaces (State Management)

The following keys are stored in Redis to coordinate state across the cluster:

### 1.1 Tenant Rate Limiting
* **Key Format:** `rate_limit:tenant:<tenantId>`
* **Type:** Hash (Managed by Bucket4j Redis storage)
* **TTL:** Automatic (Based on `ExpirationAfterWriteStrategy` of 10 seconds of inactivity after a full refill)
* **Why it's used:** Stores the token bucket state (available tokens and last refill timestamp) for each tenant.
* **Details:** This ensures that rate limits (e.g. 200 req/min) are enforced cluster-wide. If Tenant A fires requests hitting Pod 1 and Pod 2, both pods decrement tokens from the exact same Redis bucket.

### 1.2 Session Revocation Blacklist
* **Key Format:** `authenza:revoked:session:<sessionId>`
* **Type:** String
* **Value:** `"revoked"`
* **TTL:** Dynamic (Set to the remaining lifetime of the user's JWT access token + 30 seconds buffer; defaults to 2 hours)
* **Why it's used:** Provides immediate session revocation/logout across all resource servers.
* **Details:** Since OAuth2 JWTs are stateless, they cannot be natively revoked before they expire. Authenza includes the `session_id` in the JWT claims. On logout, the session is blacklisted in Redis. On every incoming API request, `RevokedSessionJwtValidator` queries Redis in `O(1)` time. If the key exists, the request is rejected with `401 Unauthorized` immediately.

### 1.3 Brute-Force Attempt Counter
* **Key Format:** `bf:<tenantId>:<username>`
* **Type:** String (Integer counter)
* **TTL:** Lockout window duration (e.g. 15 minutes, configured per tenant)
* **Why it's used:** Tracks consecutive failed login attempts per-user, per-tenant.
* **Details:** Prevents brute-force/credential-stuffing attacks. Scoping the key with `tenantId` ensures login failures in one tenant do not affect user status in another. On the first failed login, the key is initialized and given a TTL. If the counter reaches the threshold (e.g. 5 attempts), the account is locked in the relational database.

### 1.4 WebAuthn Pending Challenges (Planned / Fallback)
* **Key Format:** `webauthn:challenge:register:<requestId>` and `webauthn:challenge:auth:<requestId>`
* **Type:** String (JSON payload)
* **TTL:** 5 minutes (`CHALLENGE_TTL_MS`)
* **Why it's used:** Stores temporary cryptographic challenges during the FIDO2/WebAuthn registration and authentication ceremonies.
* **Details:** WebAuthn is a two-step process: challenge generation (`/start`) followed by browser signature assertion verification (`/finish`). Storing these in Redis ensures that if `/finish` hits a different pod than `/start`, the challenge verification still succeeds.

---

## 2. Redis Pub/Sub Channels (Event Messaging)

Authenza uses Redis Pub/Sub for lightweight, asynchronous, real-time message broadcasting between microservices:

### 2.1 `tenant:provisioned`
* **Purpose:** Broadcasts tenant creation events.
* **Publisher:** `auth-master-service` (when a new tenant is created via administrative controls).
* **Subscriber:** `auth-server-core` (dynamic routing data source setup).
* **Why it's used:** Triggers the tenant routing engine to automatically create and bind connection pools for the new tenant database without requiring a restart.

### 2.2 `tenant:settings-invalidated`
* **Purpose:** Signals that a tenant's configuration settings have changed.
* **Publisher:** `auth-iam-service` (when an administrator modifies settings in the dashboard).
* **Subscriber:** All `auth-server-core` nodes.
* **Why it's used:** Evicts the updated tenant's cached settings from the local Caffeine cache on every pod. This guarantees instant security policy updates (e.g. enabling MFA tenant-wide) cluster-wide within milliseconds.

### 2.3 `notification:email-verification`
* **Purpose:** Queue dispatch for email verification delivery.
* **Publisher:** `auth-iam-service` (during new user registration).
* **Subscriber:** `auth-notification-service` (Spring Mail consumer).
* **Why it's used:** Decouples email sending from the synchronous user registration HTTP path to keep registration latency ultra-low.

### 2.4 `notification:password-reset`
* **Purpose:** Queue dispatch for password reset OTP delivery.
* **Publisher:** `auth-iam-service` (when a user requests a forgot-password link).
* **Subscriber:** `auth-notification-service` (Spring Mail consumer).
* **Why it's used:** Offloads email generation and delivery to the async background worker.

### 2.5 `notification:admin-invite`
* **Purpose:** Queue dispatch for administrative invitation delivery.
* **Publisher:** `auth-iam-service` (when an admin provisions/invites a new user).
* **Subscriber:** `auth-notification-service` (Spring Mail consumer).
* **Why it's used:** Delivers the secure invitation token email asynchronously to the new employee.
