# Chapter 5 — Boot order lab

| | |
|--|--|
| Part | [II — Getting started](README.md) |
| Previous | [← Prerequisites](04-prerequisites.md) |
| Next | [First tenant & login →](06-first-tenant-and-login.md) |

---

## Why order matters

Master must register / provision before IAM and core can serve a tenant. Notification is optional until you need email (verify, reset, security alerts).

## Lab steps

```bash
# 1. Infra
docker-compose up -d redis
# ensure MySQL is running

# 2. Control plane
./gradlew :auth-master-service:bootRun
# wait for Started …Master…

# 3. IAM
./gradlew :auth-iam-service:bootRun

# 4. Auth / OIDC
./gradlew :auth-server-core:bootRun

# 5. Emails (Adaptive alerts, resets, …)
./gradlew :auth-notification-service:bootRun

# 6. Portal (optional)
# cd authenza-tenant-portal && npm start
```

## Port check

| URL | Expect |
|-----|--------|
| `http://localhost:8080` | Master up |
| `http://localhost:8081/{tenantId}/.well-known/openid-configuration` | OIDC discovery (after tenant exists) |
| `http://localhost:8082` | IAM up |

## Common failures

| Failure | Likely cause |
|---------|----------------|
| Liquibase / JDBC errors | MySQL creds or DB not created |
| Redis connection refused | Redis not running |
| IAM 404 for settings | IAM not rebuilt / not running |
| Core can’t call IAM | Wrong IAM base URL in core `application.yaml` |

---

← [Ch 4](04-prerequisites.md) · [Chapter 6 — First tenant & login →](06-first-tenant-and-login.md)
