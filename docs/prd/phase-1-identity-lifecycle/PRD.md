# Feature PRD — Phase 1: User Onboarding & Identity Lifecycle

| Field | Value |
|-------|--------|
| Phase | 1 |
| Status | **Completed** |
| Parent | [`docs/PRD.md`](../../PRD.md) |
| Children | None |
| Roadmap | [`planning/ROADMAP.md`](../../../planning/ROADMAP.md) → Phase 1 |
| Architecture | [`docs/ARCHITECTURE.md`](../../ARCHITECTURE.md) §6 |

---

## 1. Problem

Tenants need a complete identity lifecycle inside their isolated workspace: sign-up, verification, recovery, invites, and an owner who can administer from day one.

## 2. Goals

- Activate users only after email verification.
- Support B2B invite (set-password) vs public registration.
- Bootstrap tenant owner as `TENANT_ADMIN` on provision.
- Enforce password policy on signup/reset.

## 3. Personas

Tenant owner, end user, tenant admin.

## 4. Requirements (summary)

| ID | Requirement | Status |
|----|-------------|--------|
| FR-1.1 | Registration + email verification | Shipped |
| FR-1.2 | Forgot / reset password | Shipped |
| FR-1.3 | Profile management | Shipped |
| FR-1.4 | Account deletion (toggleable) | Deferred → Phase 4 |
| FR-1.5 | Admin invite flow | Shipped |
| FR-1.6 | Owner shadow user on provision | Shipped |
| FR-1.7 | Password policies | Shipped |

## 5. Acceptance (phase done when)

- [x] New user can register, verify, and log in within a tenant.
- [x] Invitee can set password without public signup.
- [x] Owner has admin access immediately after tenant create.

## 6. Out of scope

Full GDPR/DPDP erasure workflows (Phase 7); account deletion feature toggle UI (Phase 4).