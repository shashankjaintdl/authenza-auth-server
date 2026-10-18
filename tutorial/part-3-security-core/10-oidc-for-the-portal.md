# Chapter 10 — OIDC for the portal

| | |
|--|--|
| Part | [III — Security core](README.md) |
| Previous | [← Sessions](09-sessions-and-devices.md) |
| Next | [Part IV →](../part-4-adaptive-auth/README.md) |

---

## Idea

The Angular Tenant Portal is an OAuth **public client**. It does not call a proprietary “login API” as the source of truth — it completes **Authorization Code + PKCE** against Authenza’s tenant issuer.

## Terms with examples

### Issuer / discovery

```text
Issuer:    http://localhost:8081/acme
Discovery: http://localhost:8081/acme/.well-known/openid-configuration
```

Portal config points `authority` / issuer to that URL.

### Authorize → code → token

**Example path:**

1. User hits portal route that needs auth.  
2. Redirect to `/{tenant}/oauth2/authorize?client_id=…&code_challenge=…&redirect_uri=…`  
3. User completes login (+ MFA / Adaptive as needed) on core.  
4. Browser returns to `http://localhost:4200/…?code=…`  
5. Portal exchanges code + `code_verifier` at `/{tenant}/oauth2/token`.  

### SavedRequest / `tx`

During multi-step login, Authenza may stash the original authorize URL (session SavedRequest) or an OAuth transaction token (`tx`) so MFA/Adaptive redirects can resume the code flow.

**Example:** After MFA, filter restores authorize continuation instead of dumping the user on a blank home page.

## Lab

1. Open discovery JSON for your tenant; note `authorization_endpoint` and `token_endpoint`.  
2. Complete portal login; in browser Network tab, confirm authorize → login → (mfa) → redirect with `code`.  
3. Confirm API calls to IAM send the access token (Bearer).  

## Further reading

- [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)  
- Phase 3 PRDs under `docs/prd/phase-3-oauth2-oidc/`  

---

← [Ch 9](09-sessions-and-devices.md) · [Part IV — Adaptive Auth →](../part-4-adaptive-auth/README.md)
