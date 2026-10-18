# HTTP Headers Documentation

This document outlines the custom and standard HTTP headers used across the Authenza multi-tenant authentication server modules (`auth-tenant-adapter`, `auth-server-core`, `auth-iam-service`, and `auth-notification-service`).

---

## 1. Request Headers

Client applications and internal services must attach these headers on requests to control routing, authentication, and logging context.

### 1.1 `X-Tenant-ID`
* **Type:** Request Header
* **Service(s):** All modules (Intercepted by `TenantValidationInterceptor` in `auth-tenant-adapter`)
* **Purpose:** Multi-tenant database routing.
* **Why it's used:** Authenza implements a **database-per-tenant physical isolation** architecture. The `TenantValidationInterceptor` extracts this header and binds it to the thread-local `TenantContextHolder`. When the Spring data layer executes queries, the `TenantRoutingDataSource` reads this context to establish connection pools to the correct tenant database.
* **Format:** String (e.g., `X-Tenant-ID: customer-acme`)

### 1.2 `Authorization`
* **Type:** Request Header
* **Service(s):** `auth-iam-service`, `auth-server-core`
* **Purpose:** User/Client authentication.
* **Why it's used:** Used to pass OIDC/OAuth2 JSON Web Tokens (JWT) for authenticated API endpoints. The resource server decodes the token, checks the revocation blacklist, and maps authorities for Role-Based Access Control (RBAC).
* **Format:** Bearer Token (e.g., `Authorization: Bearer eyJhbGci...`)

### 1.3 `X-Inviter-Name`
* **Type:** Request Header
* **Service(s):** `auth-iam-service` (`UserController.createUser`)
* **Purpose:** Personalization of administrator invitation emails.
* **Why it's used:** When a Tenant Administrator invites a new employee to provision their account, the inviter's display name is passed through this header. The IAM service parses it and passes it to the `auth-notification-service` via Redis Pub/Sub, customizing the sent email (e.g., *"You have been invited by [Inviter Name] to join Authenza"*).
* **Format:** String (e.g., `X-Inviter-Name: John Doe`)

### 1.4 `User-Agent`
* **Type:** Request Header
* **Service(s):** `auth-server-core` (`SessionRecordingService`)
* **Purpose:** Device tracking for session audit logs.
* **Why it's used:** Used to extract client browser, platform, and operating system details. Authenza parses this header using a user-agent analyzer and stores the descriptive output (e.g., `Chrome on macOS`) in the `user_session` table to render the user's "Active Devices" list.
* **Format:** Standard Browser User-Agent String

### 1.5 `X-Forwarded-For`
* **Type:** Request Header
* **Service(s):** `auth-server-core` (`SessionRecordingService`)
* **Purpose:** Client IP address tracking behind reverse proxies.
* **Why it's used:** When Authenza runs behind a load balancer, CDN, or reverse proxy (like Nginx), the standard `request.getRemoteAddr()` returns the local proxy's IP. The `X-Forwarded-For` header contains the client's actual remote IP, which is saved in the `user_session` table for auditing and geolocation anomaly checks.
* **Format:** IP Address (e.g., `X-Forwarded-For: 203.0.113.195`)

---

## 2. Response Headers (Rate Limiting)

These headers are injected into the HTTP response by the `TenantRateLimitFilter` to provide transparency, allowing client applications to throttle their own requests and prevent being rate-limited.

### 2.1 `X-RateLimit-Limit`
* **Type:** Response Header (Returned on all requests)
* **Purpose:** Indicates the tenant's maximum burst request capacity.
* **Why it's used:** Informs client developers of their absolute rate limit threshold (e.g., `200` requests/minute) so they know the API capacity tier limits.

### 2.2 `X-RateLimit-Remaining`
* **Type:** Response Header (Returned on all requests)
* **Purpose:** Indicates remaining requests allowed in the current rate limit window.
* **Why it's used:** Allows client code to programmatically throttle outgoing requests. If the count drops close to 0, the client can slow down its calls before receiving HTTP 429 failures.

### 2.3 `X-RateLimit-Retry-After`
* **Type:** Response Header (Returned ONLY on HTTP `429 Too Many Requests` failures)
* **Purpose:** Specifies wait time in seconds.
* **Why it's used:** Tells the client application exactly how many *seconds* it must wait and sleep its execution threads before the server will accept requests again.

### 2.4 `X-RateLimit-Reset`
* **Type:** Response Header (Returned ONLY on HTTP `429 Too Many Requests` failures)
* **Purpose:** Specifies the window reset epoch timestamp.
* **Why it's used:** Provides the exact Unix epoch timestamp (in milliseconds) when the rate limit token bucket will have refilled enough tokens for requests to succeed. Useful for scheduling background job workers to restart syncs.
