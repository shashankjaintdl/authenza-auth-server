# Authenza Tutorial — Book Index

| Field | Value |
|-------|--------|
| Audience | Engineers joining Authenza (backend / IAM / portal) |
| Style | Book — parts → chapters → sections |
| Status | Living tutorial (aligned to shipped code) |
| Product docs | [`docs/PRD.md`](../docs/PRD.md) · [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) |
| Roadmap | [`planning/ROADMAP.md`](../planning/ROADMAP.md) |

Welcome. This tutorial is organized like a **book**: read the parts in order the first time; later jump via this index.

**Live site (GitHub Pages):** after the deploy workflow runs —  
`https://shashankjaintdl.github.io/authenza-auth-server/tutorial/`  

**Source on GitHub:** [`tutorial/`](https://github.com/shashankjaintdl/authenza-auth-server/tree/feature/authenza-10/tutorial)

---

## How to use this book

1. Start at [Preface](00-preface.md) if you are new to the repo.
2. Follow **Part I → Part V** for a full onboarding path (~1–2 days of reading + labs).
3. Each chapter ends with **Next →** / **← Previous** links.
4. Deep product/design detail stays in `docs/` and `planning/` — chapters point there instead of duplicating.

**Convention**

| Path | Role |
|------|------|
| `tutorial/README.md` | This page — table of contents |
| `tutorial/00-*.md` | Front matter |
| `tutorial/part-N-*/README.md` | Part opener |
| `tutorial/part-N-*/NN-*.md` | Chapters |

---

## Table of contents

### Front matter

| # | Chapter | Summary |
|---|---------|---------|
| — | [Preface](00-preface.md) | Goals, prerequisites mindset, how labs work |
| — | [Glossary (terms + examples)](00-glossary.md) | Every major Authenza term with concrete examples |

---

### Part I — Foundations

*Understand what Authenza is and how the pieces fit.*

| Ch | Title | File |
|----|-------|------|
| | [Part I opener](part-1-foundations/README.md) | |
| 1 | [What is Authenza?](part-1-foundations/01-what-is-authenza.md) | Product job, isolation model |
| 2 | [Architecture at a glance](part-1-foundations/02-architecture-at-a-glance.md) | Control vs data plane, Mermaid map |
| 3 | [Module map](part-1-foundations/03-module-map.md) | Every Gradle module and its job |

---

### Part II — Getting started

*Run the stack and complete a first login.*

| Ch | Title | File |
|----|-------|------|
| | [Part II opener](part-2-getting-started/README.md) | |
| 4 | [Prerequisites & tooling](part-2-getting-started/04-prerequisites.md) | Java 17, MySQL, Redis, Gradle |
| 5 | [Boot order lab](part-2-getting-started/05-boot-order-lab.md) | docker-compose → master → IAM → core → notify |
| 6 | [First tenant & first login](part-2-getting-started/06-first-tenant-and-login.md) | Provision, OIDC discovery, portal |

---

### Part III — Security core

*Password login, MFA, sessions, and OIDC.*

| Ch | Title | File |
|----|-------|------|
| | [Part III opener](part-3-security-core/README.md) | |
| 7 | [Login & brute-force path](part-3-security-core/07-login-and-brute-force.md) | Form login, BF, pending state |
| 8 | [MFA (TOTP)](part-3-security-core/08-mfa-totp.md) | Policy MFA, enroll, verify |
| 9 | [Sessions & devices](part-3-security-core/09-sessions-and-devices.md) | Active sessions vs trusted devices |
| 10 | [OIDC for the portal](part-3-security-core/10-oidc-for-the-portal.md) | Auth Code + PKCE, tenant issuer |

---

### Part IV — Adaptive Risk-Based Auth

*Phase A as shipped — scoring, step-up, trust cookie.*

| Ch | Title | File |
|----|-------|------|
| | [Part IV opener](part-4-adaptive-auth/README.md) | |
| 11 | [Adaptive concepts](part-4-adaptive-auth/11-adaptive-concepts.md) | Signals, score, ALLOW / STEP_UP / BLOCK |
| 12 | [Code walkthrough](part-4-adaptive-auth/12-code-walkthrough.md) | Gate, evaluators, extensibility hooks |
| 13 | [Portal & ops](part-4-adaptive-auth/13-portal-and-ops.md) | Toggle, revoke trust, security email |

---

### Part V — Going further

*Contribute safely and find deeper docs.*

| Ch | Title | File |
|----|-------|------|
| | [Part V opener](part-5-going-further/README.md) | |
| 14 | [Where to change what](part-5-going-further/14-where-to-change-what.md) | Cheat sheet by feature area |
| 15 | [Reading path for PRDs & design](part-5-going-further/15-docs-reading-path.md) | `docs/prd`, design, roadmap |

---

## Suggested reading paths

| Goal | Path |
|------|------|
| Run locally this week | Preface → Ch 4 → 5 → 6 |
| Own login/MFA bugs | Part III (7–9) |
| Own Adaptive Auth | Part IV + [`FEATURES.md`](../docs/prd/phase-2-security-authentication/adaptive-risk-based-auth/FEATURES.md) |
| Architecture interview prep | Part I + [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) |

---

## Related bookshelves (outside this tutorial)

| Shelf | Location |
|-------|----------|
| Product PRD | [`docs/PRD.md`](../docs/PRD.md) |
| Feature PRDs | [`docs/prd/README.md`](../docs/prd/README.md) |
| Architecture | [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) |
| Adaptive design | [`planning/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md`](../planning/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md) |
| Project README | [`README.md`](../README.md) |

---

*End of index — start with the [Preface →](00-preface.md)*
