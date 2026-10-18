# Implementation Plan — High-Value Generic Architecture Patterns for Authenza

## Overview
Following our architectural analysis of generic patterns in Authenza, this plan outlines the introduction of high-value generic utilities where generics solve concrete engineering problems without polluting domain entities or introducing anemic CRUD layers.

The primary deliverable is **`TenantContextRunner`**—a generic functional runner (`executeInTenant(tenantId, Supplier<T>)` and `runInTenant(tenantId, Runnable)`) that eliminates repeated `try ... finally { TenantContextHolder.clear(); }` boilerplate across the codebase and prevents Tomcat thread-pool ThreadLocal leakage.

---

## User Review Required

> [!IMPORTANT]
> **ThreadLocal Safety**: In a multi-tenant servlet container, un-cleared `ThreadLocal` values leak across reused worker threads. Right now, `TenantContextHolder.setTenantId(tenantId)` is manually called in over 40 places. If any code branch throws an unchecked exception before reaching a manual `clear()`, subsequent HTTP requests on that thread could execute against the wrong tenant database.
>
> Introducing a functional generic `TenantContextRunner` guarantees that context cleanup is always executed in a `finally` block, regardless of outcome.

---

## Proposed Changes

### 1. `auth-tenant-adapter` (Context Management Layer)

#### [NEW] [`TenantContextRunner.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-tenant-adapter/src/main/java/com/authenza/adapter/context/TenantContextRunner.java)

Create a Spring `@Component` providing functional execution boundaries with generic return types:

```java
package com.authenza.adapter.context;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Functional executor that guarantees TenantContextHolder cleanup
 * using generic lambda suppliers and runnables.
 */
@Component
public class TenantContextRunner {

    /**
     * Executes the given action in the context of the specified tenant and returns its result.
     * Restores the previous tenant context after execution.
     *
     * @param tenantId The tenant context to switch to
     * @param action   The supplier returning type T
     * @param <T>      The return type
     * @return Result of the action
     */
    public <T> T executeInTenant(String tenantId, Supplier<T> action) {
        Objects.requireNonNull(action, "Action must not be null");
        String previousTenant = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            return action.get();
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantId(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    /**
     * Executes the given runnable in the context of the specified tenant.
     * Restores the previous tenant context after execution.
     *
     * @param tenantId The tenant context to switch to
     * @param action   The runnable to execute
     */
    public void runInTenant(String tenantId, Runnable action) {
        Objects.requireNonNull(action, "Action must not be null");
        executeInTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Executes a throwing callable in the context of the specified tenant.
     */
    public <T> T callInTenant(String tenantId, Callable<T> callable) throws Exception {
        Objects.requireNonNull(callable, "Callable must not be null");
        String previousTenant = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            return callable.call();
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantId(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }
}
```

---

### 2. `auth-server-core` (Refactoring Manual Invocations)

#### [MODIFY] [`MfaProxyController.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/web/MfaProxyController.java)
- Inject `TenantContextRunner`.
- Replace manual `TenantContextHolder.setTenantId(tenantId)` calls with `tenantContextRunner.executeInTenant(...)` or `runInTenant(...)`.

#### [MODIFY] [`ForcePasswordChangeController.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-server-core/src/main/java/com/authenza/core/web/ForcePasswordChangeController.java)
- Inject `TenantContextRunner`.
- Replace manual `TenantContextHolder.setTenantId(tenantId)` in form submissions and password resets.

---

### 3. Unit Tests (`auth-tenant-adapter`)

#### [NEW] [`TenantContextRunnerTest.java`](file:///Users/shashankjain/Downloads/authenza-auth-server/auth-tenant-adapter/src/test/java/com/authenza/adapter/context/TenantContextRunnerTest.java)
Verify:
1. `executeInTenant` returns typed value and sets correct `TenantContextHolder` during execution.
2. `executeInTenant` restores previous tenant context if nested contexts are run.
3. `executeInTenant` cleans up even if `action.get()` throws a runtime exception.
4. `runInTenant` behaves identically for void operations.

---

## Verification Plan

### Automated Verification
Run the compiler and test suite:
```bash
./gradlew :auth-tenant-adapter:test
./gradlew :auth-server-core:compileJava
./gradlew compileJava
```

### Manual Verification
1. Verify nested tenant switching (e.g. platform admin executing an action on behalf of `tenant-a` while preserving master admin context).
2. Confirm no `ThreadLocal` residue on servlet worker threads after requests complete.
