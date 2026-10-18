# Chapter 4 — Prerequisites & tooling

| | |
|--|--|
| Part | [II — Getting started](README.md) |
| Previous | [← Part opener](README.md) |
| Next | [Boot order lab →](05-boot-order-lab.md) |

---

## Checklist

| Tool | Version / note |
|------|----------------|
| Java | **17** (toolchain-enforced) |
| Gradle | Wrapper only — `./gradlew` |
| MySQL | 8.x |
| Redis | 7.x (Docker or Homebrew) |
| Node / Angular | For portal (`4200`) — optional for pure API labs |
| Docker | Recommended for Redis via `docker-compose.yml` |

## Verify

```bash
java -version          # 17.x
./gradlew --version
mysql -u root -p -e "SELECT VERSION();"
redis-cli ping         # PONG
```

## Config you will touch

Each service’s `src/main/resources/application.yaml`:

- MySQL URL / user / password  
- Redis host / port  
- Cross-service URLs (IAM ↔ core, etc.)  

Default credentials in README examples may not match your machine — update before boot.

## Further reading

- Root README → Prerequisites, Environment Configuration  

---

← [Part II](README.md) · [Chapter 5 — Boot order lab →](05-boot-order-lab.md)
