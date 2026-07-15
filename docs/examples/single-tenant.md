---
title: Single Tenant
description: A minimal NONE mode note-taking API with no multi-tenancy.
---

# Single Tenant

The single-tenant demo shows Aperture in `tenancyMode: none`. There are no `aperture_tenant_id` columns, no `/manage/tenants` API, and no tenant isolation. Everything is simpler, with one implicit tenant and one set of users.

## When to choose NONE mode

- Internal tools for a single organisation
- Self-hosted deployments where tenant isolation is not needed
- Prototyping or evaluating Aperture before committing to POOL mode

NONE mode and POOL mode produce identical JSON:API endpoints. Auth, RBAC, ABAC, hooks, encryption, optimistic locking, and audit all work the same way. Only the tenant-related infrastructure is absent.

## The demo

The single-tenant demo is a minimal note-taking API with one entity (`Note`) and two roles (`Admin`, `ReadOnly`).

### Aperture config

```yaml
apiVersion: aperture.itsjool.com/v1
kind: ApertureConfig
metadata:
  name: config
spec:
  tenancyMode: none
  defaultRoles:
    - Admin
    - ReadOnly
```

### The Note entity

```yaml
apiVersion: aperture.itsjool.com/v1
kind: Entity
metadata:
  name: Note
spec:
  optimisticLocking: true
  softDelete: true
  fields:
    title:
      type: string
      required: true
    content:
      type: string
  permissions:
    Admin:    [create, read, update, delete]
    ReadOnly: [read]
```

Note that `tenantScoped` is absent (defaults to `false`). There are no `aperture_tenant_id` columns in the generated schema.

`softDelete: true` adds a `deleted_at TIMESTAMPTZ` column. Delete operations set `deleted_at` rather than removing the row. All reads filter `WHERE deleted_at IS NULL`.

### Run it

```bash
git clone https://github.com/ItsJooL/aperture.git
cd aperture/demos/aperture-single-tenant-demo
docker compose up -d
```

Two services: `postgres` and `api-server`. No hook service, no Jaeger, no UI. The seeder creates an `Admin` user.

### Verify NONE mode

```bash
# The /manage/tenants endpoint does not exist in NONE mode
curl -s http://localhost:8080/manage/tenants \
  -H "Authorization: Bearer $TOKEN"
# 404 Not Found
```

### Use the API

```bash
# Login
export TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"changeme-local-only"}' | jq -r .accessToken)

# Create a note
curl -s -X POST http://localhost:8080/api/v1/notes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -d '{
    "data": {
      "type": "note",
      "attributes": {
        "title": "My first note",
        "content": "Aperture works in single-tenant mode too."
      }
    }
  }' | jq .

# Soft delete
curl -s -X DELETE http://localhost:8080/api/v1/notes/1 \
  -H "Authorization: Bearer $TOKEN"
# Row is still in the database, deleted_at is set
```

## What changes between NONE and POOL

| Feature | POOL | NONE |
|---|---|---|
| `aperture_tenant_id` column | Yes (on tenant-scoped entities) | No |
| `/manage/tenants` API | Available | 404 |
| Tenant context in JWT | Yes | No |
| FK constraints | Tenant-aware | Standard |
| Multi-user data isolation | Per-tenant | None; all users share all data |
| Auth, RBAC, ABAC, hooks | Unchanged | Unchanged |

## Migrating from NONE to POOL

Switching an existing NONE deployment to POOL requires:

1. Adding `aperture_tenant_id` columns to all entities you intend to make tenant-scoped (a schema migration)
2. Backfilling `aperture_tenant_id` with a tenant ID for all existing rows
3. Updating FK constraints to be tenant-aware
4. Changing `tenancyMode: none` to `tenancyMode: pool` in the Aperture config

This is non-trivial and requires careful coordination with Liquibase changesets. Plan your tenancy model before going to production.
