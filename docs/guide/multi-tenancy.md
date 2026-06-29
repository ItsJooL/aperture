---
title: Multi-Tenancy
description: POOL and NONE tenancy modes — how they work and when to use each.
---

# Multi-Tenancy

Aperture supports two tenancy modes configured in `manifests/framework/config.yaml`:

```yaml
apiVersion: aperture.itsjool.com/v1
kind: FrameworkConfig
metadata:
  name: config
spec:
  tenancyMode: pool   # or: none
```

## The two modes at a glance

| | POOL mode | NONE mode |
|---|---|---|
| Database isolation | `aperture_tenant_id` column on every tenant-scoped table | No tenant columns |
| Query filtering | Automatic — all reads filter by the current tenant | None needed |
| Tenant management | `/manage/tenants` API active | Not available (404) |
| FK constraints | Tenant-aware (see below) | Standard FK constraints |
| Token carries tenantId | Yes | No (shadow tenant internally) |
| Use case | Multi-tenant SaaS | Single-org / self-hosted |

## POOL mode in depth

POOL mode is the default. It uses a single shared database schema with tenant isolation enforced at the column level — the "shared pool" pattern.

### The `aperture_tenant_id` column

Every entity with `tenantScoped: true` gets an `aperture_tenant_id UUID NOT NULL` column. The changeset generator adds it automatically:

```xml
<column name="aperture_tenant_id" type="UUID">
  <constraints nullable="false"/>
</column>
```

A unique constraint `uq_{table}_tenant_id` is also generated on `(aperture_tenant_id, id)` to ensure IDs are globally unique across tenants.

### Automatic query filtering

At the start of each HTTP request, the Auth filter extracts the tenant ID from the JWT and stores it in `TenantContextHolder` (a `ThreadLocal<String>`). Elide reads the tenant ID from the holder and adds a `WHERE aperture_tenant_id = ?` clause to every query automatically. The holder is cleared at the end of each request.

You cannot accidentally access another tenant's data — the filter applies before any application code runs.

### Tenant-aware foreign keys

In POOL mode, foreign key constraints for tenant-scoped entities include the `aperture_tenant_id` column. A tenant can only relate their own records — you cannot create a relationship from Tenant A's invoice to Tenant B's customer.

The generated FK constraint for a ManyToOne relationship looks like:

```xml
<addForeignKeyConstraint
  constraintName="fk_invoice_customer_id"
  baseTableName="invoice"
  baseColumnNames="aperture_tenant_id, customer_id"
  referencedTableName="customer"
  referencedColumnNames="aperture_tenant_id, id"/>
```

A global (non-tenant-scoped) entity **cannot** reference a tenant-scoped entity. The changeset generator throws at build time if you try. The error is:

```
Global entity Country cannot reference tenant-scoped entity Invoice
through relationship Country.invoices in POOL tenancy mode
```

### `tenantScoped: true` vs `false`

| | `tenantScoped: true` | `tenantScoped: false` |
|---|---|---|
| `aperture_tenant_id` column | Yes | No |
| Queries auto-filtered by tenant | Yes | No — all tenants share these rows |
| FK constraints | Tenant-aware | Standard |
| Use case | Core domain entities | Reference/lookup data shared across tenants |

The billing demo uses `tenantScoped: false` for `Country`, `Currency`, and `Product` (shared reference data) and `tenantScoped: true` for `Customer`, `Invoice`, `LineItem`, `Payment`, and `Supplier`.

### Tenant provisioning

Creating a tenant via `POST /manage/tenants` is atomic — it creates the tenant record and the initial tenant administrator in a single transaction. If either part fails, neither is committed.

Request body:

```json
{
  "tenantName": "Acme Corp",
  "initialAdminUsername": "admin@acme.com",
  "initialAdminPassword": "SecurePass123!",
  "initialAdminAttributes": {
    "department": "ops"
  }
}
```

The initial admin user is created with a `forcePasswordChange: true` flag — they must change their password on first login.

## NONE mode in depth

NONE mode is for single-tenant deployments. No `aperture_tenant_id` columns are generated, the `/manage/tenants` API returns 404, and the runtime creates a single internal shadow tenant that all users belong to.

Everything else — JWT auth, RBAC, ABAC, hooks, audit — works identically to POOL mode. Switching a deployment from NONE to POOL requires schema migration and is non-trivial; plan your tenancy model upfront.

See the [Single Tenant example](/examples/single-tenant) for a complete deployment walkthrough.

## Choosing a mode

**Use POOL when:**
- You are building a multi-tenant SaaS product
- You need the `/manage/tenants` API for tenant onboarding
- You want database-level tenant isolation without separate schemas

**Use NONE when:**
- You're deploying for a single organisation
- You want the simplest possible schema (no tenant columns)
- You know multi-tenancy won't be needed

> **SILO mode** (separate schema per tenant) is on the roadmap but not yet implemented.
