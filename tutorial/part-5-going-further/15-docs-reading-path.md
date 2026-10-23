# Chapter 15 — Docs reading path

| | |
|--|--|
| Part | [V — Going further](README.md) |
| Previous | [← Where to change what](14-where-to-change-what.md) |
| Next | [Back to index →](../README.md) |

---

## Recommended order

```text
1. tutorial/          ← you are here (how it works + terms)
2. docs/ARCHITECTURE.md
3. docs/PRD.md        ← product parent
4. docs/prd/README.md ← phase feature PRDs
5. planning/ROADMAP.md ← status source of truth
6. planning/*_DESIGN.md when implementing a feature
```

## Authenza “bookshelves”

| Shelf | Path | Use when |
|-------|------|----------|
| This tutorial | [`tutorial/README.md`](../README.md) | Onboarding + term examples |
| Architecture | [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) | How modules connect |
| Product PRD | [`docs/PRD.md`](../../docs/PRD.md) | What the product must do |
| Feature PRDs | [`docs/prd/`](../../docs/prd/README.md) | Phase / sub-phase scope |
| Adaptive feature pack | [`docs/prd/.../adaptive-risk-based-auth/`](../../docs/prd/phase-2-security-authentication/adaptive-risk-based-auth/PRD.md) | PRD + FEATURES + diagrams |
| Design | [`planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md`](../../planning/designs/ADAPTIVE_RISK_BASED_AUTH_DESIGN.md) | Engineering design + generics-later |
| Roadmap | [`planning/ROADMAP.md`](../../planning/ROADMAP.md) | Shipped vs next vs later |
| Runbook-ish | [`README.md`](../../README.md) | Ports, boot order, BYOD |

## Status rule

Update **roadmap** first when something ships; then refresh PRDs/tutorial notes if behavior changed.

---

← [Ch 14](14-where-to-change-what.md) · [Book index →](../README.md)

**End of the tutorial book.** For term lookup anytime: [Glossary](../00-glossary.md).
