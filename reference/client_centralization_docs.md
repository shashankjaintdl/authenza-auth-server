# Technical Documentation: Centralized Client Architecture

This document describes the architectural transition of OAuth2 client registrations from a decentralized multi-tenant schema model to a centralized registry in the Master Database.

---

## Architectural Comparison

```mermaid
graph TD
    subgraph Old Architecture (Decentralized)
        A[Client Authentication Request] --> B[MultiTenantSecurityFilter]
        B --> C[TenantRoutingDataSource]
        C -->|Dynamic Route| D[Tenant DB: system_admin]
        C -->|Dynamic Route| E[Tenant DB: villsyn]
        D -->|Query local table| F[(oauth2_registered_client)]
        E -->|Query local table| G[(oauth2_registered_client)]
    end

    subgraph New Architecture (Centralized)
        H[Client Authentication Request] --> I[MultiTenantSecurityFilter]
        I --> J[TenantAwareRegisteredClientRepository]
        J -->|Direct Query via masterJdbcOperations| K[(Master DB: auth-master)]
        K -->|Check tenant_id column| L{GLOBAL or matching tenant_id?}
        L -->|Allow| M[Authenticate Client]
        L -->|Block| N[AccessDeniedException]
    end
```

---

## Key Differences

| Feature / Aspect | Old Architecture (Decentralized) | New Architecture (Centralized) |
| :--- | :--- | :--- |
| **Table Location** | `oauth2_registered_client` existed inside **every tenant database** schema. | `oauth2_registered_client` exists **only inside the Master Database** (`auth-master`). |
| **Connection Strategy** | Routed dynamically at runtime via `TenantRoutingDataSource`. | Queries Master DB directly using `NamedParameterJdbcOperations masterJdbcOperations`. |
| **Startup Package Scanning** | Scanned repository package interfaces; crashed if the table was missing from any active tenant database. | Bypasses package scanning entirely. No metadata checks performed on dynamic datasources at startup. |
| **Multi-Tenancy Filtering** | Local query isolation (each tenant only saw its own database table). | Query filters on both client ID and the custom `tenant_id` database column (mapping `'GLOBAL'` or the active tenant context). |
| **Maintenance & Scaling** | Required running client schema migrations on every new tenant onboarding. | Master schema is static. Tenant onboarding is instant without client-table replication overhead. |

---

## Why `TenantRoutingDataSource` is not possible for Centralized Client lookup

`TenantRoutingDataSource` works by dynamically routing connections to different databases based on a thread-local tenant context. 

If the client table does not exist inside the tenant databases, calling `TenantRoutingDataSource` will fail for the following reasons:

1. **Table Non-Existence:** Since the `oauth2_registered_client` table is dropped from the tenant databases (like `system_admin`, `villsyn`), any query routed to these schemas will throw:
   ```
   java.sql.SQLSyntaxErrorException: Table 'system_admin.oauth2_registered_client' doesn't exist
   ```
2. **Boot Scan Failures:** Spring Data JDBC attempts to validate repository entity mapping properties against the default routing connection at startup. If the table is missing from the default schema, the application fails to boot.
3. **No Thread Context:** During initial client authentication filter checks, the tenant context might not be fully validated or resolved yet. Querying a static Master DB ensures that client configurations can be checked safely before context routing occurs.

---

## Database Changesets Reference

### Master DB Changes (`V0.0.7/ddl.xml`)
Establishes the central registry inside `auth-master`:
```sql
CREATE TABLE oauth2_registered_client (
    id VARCHAR(100) PRIMARY KEY,
    client_id VARCHAR(100) UNIQUE NOT NULL,
    client_id_issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    client_secret VARCHAR(200),
    client_secret_expires_at TIMESTAMP,
    client_name VARCHAR(200),
    client_authentication_methods VARCHAR(1000),
    authorization_grant_types VARCHAR(1000),
    redirect_uris VARCHAR(1000),
    post_logout_redirect_uris VARCHAR(1000),
    scopes VARCHAR(1000),
    client_settings TEXT,
    token_settings TEXT,
    tenant_id VARCHAR(100) DEFAULT 'GLOBAL' NOT NULL
);
```

### Tenant DB Changes (`V0.0.13/ddl.xml`)
Drops the duplicate tables locally:
```xml
<changeSet id="v0.0.13-drop-local-oauth2-client-table" author="authenza">
    <preConditions onFail="MARK_RAN">
        <tableExists tableName="oauth2_registered_client"/>
    </preConditions>
    <dropTable tableName="oauth2_registered_client"/>
</changeSet>
```
