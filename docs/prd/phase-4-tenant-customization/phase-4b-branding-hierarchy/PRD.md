# Feature PRD — Phase 4B: Branding, Hierarchy & Advanced Config

| Field | Value |
|-------|--------|
| Phase | 4 · Sub-phase **4B** |
| Status | **Later** |
| Parent | [Phase 4 PRD](../PRD.md) |
| Roadmap | Phase 4 → Phase 4B |

---

## 1. Problem

Growing B2B customers need white-label, org structure, self-serve OAuth clients, and richer tenant configuration.

## 2. Requirements (backlog)

- Tenant environment tagging (Dev / QA / Staging / Prod) + safety rails — see `planning/ROADMAP.md` Phase 4B  
- Master service network isolation  
- Enterprise hierarchy (orgs / sub-departments)  
- Dynamic OAuth client management + secret rotation  
- Spring Session → Redis HA  
- Branding & custom domains  
- Hierarchical feature toggles (incl. account deletion)  
- Global system config vs tenant_settings  
- Selectable captcha providers  
- Custom registration schemas / attributes  
- White-labeled email / BYO-SMTP (portal)  
- i18n on login / consent / emails  

## 3. Non-goals

Starting 4B before 4A authorization hardening is solid.

## 4. Links

- Parent: [Phase 4](../PRD.md)
- Sibling: [4A](../phase-4a-access-control/PRD.md)