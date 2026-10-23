# Implementation Plan: BYOD + Portal Must-Fix Hardening

**Parent design:** [byod_and_portal_tenant_context_design.md](./byod_and_portal_tenant_context_design.md)  
**Scope lock:** `1A + 2A`  
**Repos:** `authenza-auth-server` + `authenza-tenant-portal`  
**Date:** 2026-10-14  
**Code status:** PR-1…PR-5 application changes implemented (manual R1–R6 / BYOD matrix still required before production sign-off)  


---

## 0. Rules of engagement

1. **Do not break** hosted MySQL provisioning (blank `jdbcUrl`).
2. **Do not change** OIDC authority (stays `/system-admin`).
3. **Do not redesign** tenant switcher — keep current behavior working.
4. **Do not implement** `CROSS_TENANT_SESSION_BLEED` / re-auth-on-switch.
5. After every PR: re-run regression **R1–R6** + switcher smoke (design §9.2).

### Regression baseline (run before Phase 1)

| ID | Check | Pass? |
|---|---|---|
| R1 | Portal login via system-admin OIDC | [ ] |
| R2 | User list under `/system-admin/...` | [ ] |
| R3 | Sessions list/revoke | [ ] |
| R4 | MFA setup/confirm/disable | [ ] |
| R5 | Passkey register/list/delete | [ ] |
| R6 | Personal onboarding (hosted MySQL, no BYOD) | [ ] |
| S1 | Existing tenant switcher still works | [ ] |

---

## Sprint / PR map

| PR | Phase | Repo | Goal | Depends on |
|---|---|---|---|---|
| **PR-1** | Phase 1 | `authenza-tenant-portal` | IAM header fix + env safety | Baseline R1–R6 |
| **PR-2** | Phase 2a | `authenza-auth-server` | Allowlist + MariaDB driver + preflight API | None (backend) |
| **PR-3** | Phase 2b | `authenza-auth-server` | SQL portability (`LIMIT` cleanup) | PR-2 preferred |
| **PR-4** | Phase 3 | `authenza-tenant-portal` | BYOD onboarding UX + company path | PR-1 + PR-2 |
| **PR-5** | Phase 4 | both | Verification sign-off + README BYOD notes | PR-1…PR-4 |

Suggested order: **PR-1 ∥ PR-2** → **PR-3** → **PR-4** → **PR-5**.

---

## PR-1 — Portal must-fix (Phase 1)

**Repo:** `authenza-tenant-portal`  
**Estimate:** 0.5–1 day  

### Tasks

| # | Task | File(s) | Done |
|---|---|---|---|
| 1.1 | Remove `MASTER_TENANT_ID` and manual `X-TENANT-ID` headers | `src/app/core/services/session.service.ts` | [x] |
| 1.2 | Same as 1.1 | `src/app/core/services/session-guard.service.ts` | [x] |
| 1.3 | Same as 1.1 for passkeys/MFA calls (prefer interceptor-only) | `src/app/core/services/webauthn.service.ts` | [x] |
| 1.4 | Null-safe `authBaseUrl` in platform-admin skip check | `src/app/core/interceptors/tenant.interceptor.ts` | [x] |
| 1.5 | Add `authBaseUrl` + `secureRoutes` (confirm prod hostname with ops) | `src/environments/environment.ts` | [x] |
| 1.6 | Same for QA hosts | `src/environments/environment.qa.ts` | [x] |
| 1.7 | Confirm `environment.development.ts` unchanged in behavior | `src/environments/environment.development.ts` | [x] |

### Explicitly do **not** touch

- `tenant-switcher.component.ts`
- `authConfig.authority` (must remain `…/system-admin`)

### PR-1 acceptance

- [ ] R1–R6 + S1 green
- [ ] DevTools: sessions/MFA/passkeys send `X-TENANT-ID: system-admin` when active tenant is system-admin (via interceptor)
- [ ] On a customer-tenant URL, those same APIs send that tenant id (not forced `system-admin`)

### Rollback

Revert the three service files + env changes.

---

## PR-2 — Backend BYOD core (Phase 2a)

**Repo:** `authenza-auth-server`  
**Estimate:** 1.5–2.5 days  

### Tasks

| # | Task | File(s) | Done |
|---|---|---|---|
| 2.1 | Add `isByodSupported()` → MYSQL, POSTGRES_SQL, MARIA_DB | `auth-common/.../enums/DBType.java` | [x] |
| 2.2 | Add validation request/response DTOs | `auth-common/.../dto/TenantDatabaseValidationRequest.java`, `…ValidationResponse.java` | [x] |
| 2.3 | Implement preflight: allowlist, driver resolve, connect timeout, `SELECT 1`, metadata | `auth-master-service/.../TenantDatabasePreflightService.java` (**new**) | [x] |
| 2.4 | Expose `POST /api/v1/admin/tenant/validate-db` | `auth-master-service/.../TenantRegistrationController.java` | [x] |
| 2.5 | Map validation/business errors → **400** (not 500); keep unexpected as 500 | same controller | [x] |
| 2.6 | On BYOD (`jdbcUrl` present): allowlist + preflight **before** `repository.save` | `auth-master-service/.../TenantProvisioningService.java` | [x] |
| 2.7 | Hosted path (blank `jdbcUrl`): unchanged MySQL derivation | same | [x] |
| 2.8 | Liquibase failure strategy: mark `FAILED` or delete master row (pick one; document in PR) | same (INACTIVE) | [x] |
| 2.9 | Add MariaDB JDBC driver | `auth-tenant-adapter/build.gradle` | [x] |
| 2.10 | Ensure master runtime sees MariaDB driver (transitive or explicit) | via adapter | [x] |
| 2.11 | Add MariaDB to liquibaseRuntime + `generateSqlReports` map | `auth-tenant-schema/build.gradle` | [x] |
| 2.12 | Unit tests: unsupported engine rejected; blank jdbcUrl hosted path; bad password preflight | `auth-master-service/src/test/...` | [x] |

### Error codes to implement

| Code | When |
|---|---|
| `UNSUPPORTED_ENGINE` | ORACLE / MSSQL / IBMDB2 (or unknown) with jdbcUrl |
| `INVALID_JDBC_URL` | Bad / mismatched URL |
| `DRIVER_MISSING` | Class not on classpath |
| `DB_UNREACHABLE` | Network / timeout |
| `DB_AUTH_FAILED` | Bad credentials |
| `DB_QUERY_FAILED` | `SELECT 1` failed |
| `DB_PRIVILEGE` | Best-effort privilege check failed (optional) |

### PR-2 acceptance

- [ ] R6 still green (hosted MySQL)
- [ ] `validate-db` 200 for reachable MySQL / Postgres / MariaDB
- [ ] Unsupported engine → 400 `UNSUPPORTED_ENGINE`
- [ ] Bad password → 400; no new `ACTIVE` master row
- [ ] `./gradlew :auth-tenant-schema:generateSqlReports` includes mariadb (or documented fallback token)

### Rollback

Feature is opt-in via `jdbcUrl`; revert PR-2 leaves hosted path intact.

---

## PR-3 — SQL portability (Phase 2b)

**Repo:** `authenza-auth-server`  
**Estimate:** 0.5–1 day  
**Depends on:** ideally after PR-2 (can parallel if careful)

### Tasks

| # | Task | File(s) | Done |
|---|---|---|---|
| 3.1 | Remove / replace `LIMIT 1` with unique lookup or Java first-row | `auth-server-core/.../SessionRecordingService.java` | [x] |
| 3.2 | Remove `ORDER BY … LIMIT 1` MySQL patterns | `auth-server-core/.../AuthorizationServerConfig.java` | [x] |
| 3.3 | Grep tenant-path SQL for remaining `LIMIT`, `NOW()`, MySQL-only syntax; fix or ticket | core + iam | [x] |
| 3.4 | Smoke: login + session recording on MySQL tenant still works | manual | [ ] |

### PR-3 acceptance

- [ ] R1–R6 green on MySQL
- [ ] Postgres BYOD tenant: login at `/{tenantId}/login` + session row written without SQL error

---

## PR-4 — Portal BYOD onboarding (Phase 3)

**Repo:** `authenza-tenant-portal`  
**Estimate:** 1–2 days  
**Depends on:** PR-1 + PR-2  

### Tasks

| # | Task | File(s) | Done |
|---|---|---|---|
| 4.1 | Add `validateDatabase(payload)` → `POST {authBaseUrl}/api/v1/admin/tenant/validate-db` | `src/app/core/services/tenant-registration.service.ts` | [x] |
| 4.2 | Hosted DB toggle default **ON** (omit jdbcUrl when ON) | `src/app/components/onboarding/*` | [x] |
| 4.3 | When OFF: fields `dbType` (MYSQL/POSTGRES/MARIADB), jdbcUrl, username, password | same | [x] |
| 4.4 | Submit BYOD: validate-db → then registerTenant | same | [x] |
| 4.5 | Personal hosted path unchanged (toggle ON) | same | [x] |
| 4.6 | Company path: send `accountType`, `orgName`, `employeeRange`, `ownerId` (no dead redirect) | same | [x] |
| 4.7 | Success: `setActiveTenantId` + navigate `/{tenantId}/dashboard` | same | [x] |
| 4.8 | Surface validate/provision errors in UI | same | [x] |

### Explicitly do **not** touch

- `tenant-switcher.component.ts`
- Switch API semantics

### PR-4 acceptance

- [ ] Hosted personal onboarding = today’s behavior
- [ ] Company onboarding registers successfully
- [ ] BYOD MySQL path: validate → provision → dashboard
- [ ] S1 switcher still works

---

## PR-5 — Verification matrix + docs (Phase 4)

**Repos:** both  
**Estimate:** 1 day  

### Matrix

| Case | Action | Pass |
|---|---|---|
| H1 | Hosted MySQL (no jdbcUrl) — R1–R6 | [ ] |
| B1 | BYOD MySQL — validate → provision → IAM users w/ tenant header | [ ] |
| B2 | BYOD PostgreSQL — same + `/{tenantId}/login` | [ ] |
| B3 | BYOD MariaDB — same | [ ] |
| N1 | Oracle/MSSQL attempt → 400 `UNSUPPORTED_ENGINE` | [ ] |
| N2 | Bad password → 400; no ACTIVE row | [ ] |
| P1 | Portal headers on system-admin | [ ] |
| P2 | Portal headers on customer tenant URL | [ ] |
| S1 | Normal switcher unchanged | [ ] |

### Docs updates

| # | Task | Done |
|---|---|---|
| 5.1 | Short BYOD section in root `README.md` (supported engines, validate-db, hosted default) | [x] |
| 5.2 | Mark design doc status → **Implemented** (or partially) with PR links | [x] |

---

## Dependency diagram

```text
Baseline R1-R6
     │
     ├──────────────► PR-1 (portal headers/env)
     │                      │
     └──────────────► PR-2 (allowlist/preflight/drivers)
                            │
                            ▼
                         PR-3 (SQL portability)
                            │
                            ▼
                         PR-4 (onboarding BYOD)  ◄── needs PR-1 + PR-2
                            │
                            ▼
                         PR-5 (matrix + README)
```

---

## Definition of Done (whole initiative)

- [ ] All PR-1…PR-5 acceptance boxes checked
- [ ] Hosted MySQL path unchanged
- [ ] BYOD works for MYSQL, POSTGRES, MARIADB
- [ ] Unsupported engines rejected
- [ ] OIDC authority still `/system-admin`
- [ ] Tenant switcher unmodified and still working
- [ ] No session-bleed / re-auth-on-switch work introduced

---

## Out of scope reminder (do not pull into these PRs)

- Dynamic OIDC authority
- Re-auth on tenant switch
- `CROSS_TENANT_SESSION_BLEED`
- Oracle / MSSQL / DB2 GA
- Full RBAC / `@PreAuthorize` re-enable

---

## How to start coding

When ready to leave doc-only mode:

1. Run baseline R1–R6 + S1 and record results.
2. Open **PR-1** and **PR-2** in parallel.
3. Continue PR-3 → PR-4 → PR-5.

Say “start PR-1” (or “implement Phase 1”) to begin application code.
