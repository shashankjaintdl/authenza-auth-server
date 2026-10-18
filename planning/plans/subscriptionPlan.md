# Authenza Subscription & Pricing Strategy

When designing a multi-tenant Identity Provider, how you divide your features across these subscription tiers determines if your business scales or fails. This document outlines the business strategy and technical boundaries for the three standard subscription tiers.

---

## 1. `ServicePlan.FREE` (The Entry Hook)

*   **The Goal:** Reduce the barrier to entry to absolute zero. You want developers at small startups to adopt Authenza without asking their boss for a credit card.
*   **The Psychology:** Authentication is sticky. Once a startup builds their architecture around your Auth Server, they will almost never leave, because migrating users to another platform is a nightmare.

### What they get:
*   Generous allowance (e.g., first 5,000 Monthly Active Users for free).
*   Basic login, registration, and JWT minting.

### What you block (Technical Enforcement):
*   **NO Custom Branding:** Every login screen they use must prominently display `"Secured by Authenza"`. (This acts as free marketing for you when their end-users log in!).
*   **NO Custom Domains:** Their URLs will be `tenant1.authenza.com/auth` instead of `auth.their-startup.com`.
*   **NO Webhooks:** If they want real-time server-to-server updates, they must upgrade.

---

## 2. `ServicePlan.PRO` (The Core Money Maker)

*   **The Goal:** Monetize established companies that care highly about their brand perception. This plan generates the majority of your recurring revenue.
*   **The Psychology:** Once a startup grows and hires enterprise clients, those enterprise clients will say, *"Why does your login page say powered by Authenza?"*. The startup will immediately panic and pay you $249/month just so they can upload their own logo to hide yours.

### What they get:
*   Higher baseline quota (e.g., 25,000 MAUs) and they pay $0.05 for every user over that limit (The Twilio model).
*   **Full Customization (Phase 4):** They unlock the API endpoints to upload custom HTML email templates, custom logos, and use custom domains.
*   **Advanced DX (Phase 6):** You unblock endpoints for Event Webhooks so their backend can react instantly when a new user signs up.

### What you block:
*   Strict infrastructure guarantees and massive compliance protocols are still locked.

---

## 3. `ServicePlan.ENTERPRISE` (The Big Whales)

*   **The Goal:** Land massive, $2,000+/month contracts with Fortune 500 companies, Banks, and Healthcare providers.
*   **The Psychology:** Huge corporations do not care if a service costs $50 or $5,000. They only care about compliance, liability shifting, and dedicated support. If they get breached, they need an SLA (Service Level Agreement) to legally protect themselves.

### What they get:
*   **SLA & Compliance:** A legal guarantee of 99.99% uptime, and prioritized 1-hour email helpdesk support.
*   **Interoperability (Phase 5):** You unlock the **SCIM 2.0 Directory Sync** APIs. A corporation will never manually invite 10,000 employees. They need SCIM to sync Authenza automatically with their internal Microsoft Azure AD.
*   **Advanced Security (Phase 7):** Takeout APIs, Administrator Impersonation APIs, and Custom Claim Providers.
*   *(Optional):* Some highly strict enterprises demand a physically isolated database (Single-Tenant deploy) instead of sharing the master tenant table.

---

### Implementation Overview
By modeling these three tiers in your master `tenant_subscriptions` database table, your Spring API gateway can intelligently `return 403 Forbidden` if a FREE user tries to touch an ENTERPRISE API, automatically upselling them to a paid tier.
