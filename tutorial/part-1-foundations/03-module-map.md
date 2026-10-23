# Chapter 3 — Module map

| | |
|--|--|
| Part | [I — Foundations](README.md) |
| Previous | [← Architecture](02-architecture-at-a-glance.md) |
| Next | [Part II →](../part-2-getting-started/README.md) |

---

## Gradle modules (backend)

| Module | Responsibility |
|--------|----------------|
| `auth-common` | Shared DTOs, enums, Redis channel names, adaptive settings keys |
| `auth-tenant-adapter` | Multi-tenant datasource routing, settings cache |
| `auth-tenant-schema` | Liquibase changelogs for tenant DBs |
| `auth-master-service` | Tenant registry, provisioning, platform APIs |
| `auth-server-core` | OIDC, login UI, MFA filters, Adaptive risk gate |
| `auth-iam-service` | Users, settings, sessions, passkeys, trusted-devices APIs |
| `auth-notification-service` | Email templates + Redis listeners |

## Where Adaptive Auth lives

| Concern | Module / package |
|---------|------------------|
| Risk gate & signals | `auth-server-core` → `com.authenza.core.risk` |
| Settings keys / enums | `auth-common` |
| Admin settings + device revoke APIs | `auth-iam-service` |
| Security alert email | `auth-notification-service` |
| Schema (`user_trusted_device`) | `auth-tenant-schema` |

## Lab (optional)

From repo root:

```bash
./gradlew projects
```

Skim the list and match each name to the table above.

## Further reading

- Root [`README.md`](../../README.md) — architecture bullets  
- Adaptive feature glossary: [`docs/prd/.../FEATURES.md`](../../docs/prd/phase-2-security-authentication/adaptive-risk-based-auth/FEATURES.md)  

---

← [Ch 2](02-architecture-at-a-glance.md) · [Part II — Getting started →](../part-2-getting-started/README.md)
