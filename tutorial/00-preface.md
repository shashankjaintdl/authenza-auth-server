# Preface

| | |
|--|--|
| Part | Front matter |
| Next | [Glossary →](00-glossary.md) |
| Up | [Book index](README.md) |

---

## Who this book is for

Software engineers who will **run, debug, or extend** Authenza — the multi-tenant OIDC / IAM platform in this repository.

You do **not** need prior Auth0/Okta experience. You should be comfortable with Java, HTTP, and basic OAuth2 vocabulary (authorization code, token, client).

## What you will learn

1. What Authenza is and why DB-per-tenant matters  
2. How to boot the local stack in the correct order  
3. How password login, MFA, sessions, and OIDC fit together  
4. How Adaptive Risk-Based Auth (Phase A) plugs in without breaking those flows  
5. Where to look in code and product docs when you change something  

## How chapters are structured

Most chapters follow:

1. **Idea** — what and why  
2. **Terms with examples** — Authenza vocabulary in context (full catalog in [Glossary](00-glossary.md))  
3. **Map** — which modules / classes  
4. **Lab** (when useful) — something to try locally  
5. **Further reading** — PRD / design / architecture links  
6. **Navigation** — previous / next  

Keep the [Glossary](00-glossary.md) open in a second tab while reading Parts III–IV.

## Lab assumptions

- macOS or Linux  
- Java 17, MySQL 8, Redis 7, Docker optional  
- Ports as in the project README (`8080` master, `8081` core, `8082` IAM, …)  

Exact commands live in Part II; do not invent credentials beyond what `application.yaml` and README document.

## Status of truth

| Question | Source of truth |
|----------|-----------------|
| Is feature shipped? | [`planning/ROADMAP.md`](../planning/ROADMAP.md) |
| How is it built? | [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) + code |
| What should it do? | [`docs/PRD.md`](../docs/PRD.md) / [`docs/prd/`](../docs/prd/README.md) |

This tutorial summarizes; it does not replace those docs.

---

← [Index](README.md) · [Glossary →](00-glossary.md)
