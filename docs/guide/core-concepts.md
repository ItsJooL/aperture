---
title: Core Concepts
description: Manifests, the build pipeline, generated code, and lock files.
---

# Core Concepts

## The manifest-driven model

YAML manifests are the single source of truth for everything Aperture generates. Every entity class, every permission annotation, every database migration, every hook wiring — all of it derives from the manifests. You never edit generated code; you edit the manifest and rebuild.

Manifests live under the `manifests/` directory of your project:

```
manifests/
  domain/
    billing/
      invoice.yaml
      line-item.yaml
    identity/
      customer.yaml
  framework/
    config.yaml
  roles/
    abac-policies.yaml
    roles.yaml
  migrations/
    backfill-phone-number.yaml
```

## Manifest kinds

| Kind | Purpose |
|---|---|
| `Entity` | Declares a domain entity with fields, permissions, policies, and hooks |
| `FrameworkConfig` | Sets tenancy mode, default roles, and MCP configuration |
| `ApiVersionConfig` | Declares API versions and their status (ACTIVE or SUNSET) |
| `AbacPolicy` | Defines an attribute-based access control rule by name |
| `RoleDefinition` | Declares the named domain roles available in the system |
| `PrincipalAttributeDefinition` | Declares security attribute keys for ABAC and API key delegation |
| `Migration` | Defines a manual SQL migration with rollback SQL |

## A complete entity manifest

The billing demo's `invoice.yaml` shows all the sections:

```yaml
apiVersion: aperture.itsjool.com/v1
kind: Entity
metadata:
  name: Invoice
spec:
  description: "A billing invoice linking a customer to line items and payments"
  tenantScoped: true           # every row gets aperture_tenant_id
  optimisticLocking: false     # set true to require ETag/If-Match
  softDelete: false            # set true for soft-delete (deleted_at column)
  fields:
    amount:
      type: decimal
      required: true
    status:
      type: string
      enum: [DRAFT, ISSUED, PAID]
    customer:
      type: ref
      target: Customer
      relation: ManyToOne
      required: true
    lineItems:
      type: ref
      target: LineItem
      relation: OneToMany
      mappedBy: invoice
  permissions:
    Admin:      [read, delete]
    Accountant: [create, read, update]
    Viewer:     [read]
  policies:
    FinanceTeamOnly: [read, update]
    EuRegionOnly:    [read, update]
  hooks:
    ValidateInvoice:
      phase: PRECOMMIT
      async: false
      onFailure: reject
      url: http://hook-service:8080/hooks/validate-invoice
```

## Field types

| Type | Java type | DB column |
|---|---|---|
| `string` | `String` | `VARCHAR(255)` |
| `decimal` | `BigDecimal` | `DECIMAL(19,2)` |
| `integer` | `Integer` | `INTEGER` |
| `boolean` | `Boolean` | `BOOLEAN` |
| `uuid` | `UUID` | `UUID` |
| `datetime` | `Instant` | `TIMESTAMPTZ` |
| `ref` | `@ManyToOne` / `@OneToMany` | FK column (ManyToOne only) |

## The build pipeline

When you run `mvn verify`, the `aperture-maven-plugin` runs during the `generate-sources` phase:

1. **Parse** — reads all YAML files from `manifests/` into the domain model (`EntityDef`, `FieldDef`, `HookDef`, etc.)
2. **Diff** — reads snapshot JSON from `.aperture.lock/` and computes a diff against the current manifests
3. **Breaking change check** — if fields were removed or types changed without an API version bump, the build fails
4. **Code generation** — writes Java source files to `target/generated-sources/aperture/`
5. **Changeset generation** — writes Liquibase XML to `target/generated-resources/db/changelog/`
6. **Lock file update** — writes updated JSON snapshots to `.aperture.lock/`

Spring Boot then compiles the generated Java, and Liquibase runs the changesets on startup.

## What gets generated

For each entity, the code generator produces:

- A Spring `@Entity` class (one per API version if versioning is configured)
- Elide controller registration
- Permission annotations (`@ReadPermission`, `@CreatePermission`, `@UpdatePermission`, `@DeletePermission`)
- ABAC policy check classes for any `policies:` declared
- MCP tool class (if `mcp.enabled: true` in framework config)

For the changeset generator:

- `aperture-schema.xml` — full DDL (used when deploying to a fresh database)
- `aperture-incremental.xml` — diff-only delta (used for rolling upgrades to existing databases)
- Manual migration files are copied from `manifests/migrations/` and included in the root changelog

## The lock files

`.aperture.lock/` contains one JSON file per entity, committed to the repository:

```
.aperture.lock/
  1-Invoice.json
  1-Customer.json
  1-LineItem.json
  ...
```

These are JSON snapshots of each entity's state at the time of the last successful build. The `1-` prefix is the API version number.

Example (`1-Invoice.json`):
```json
{
  "name": "Invoice",
  "tenantScoped": true,
  "fields": {
    "amount": { "type": "decimal", "required": true, ... },
    "status":  { "type": "string",  "required": false, ... },
    "customer": { "type": "ref", "relation": "ManyToOne", "target": "Customer", ... }
  },
  "permissions": {
    "Admin":      ["read", "delete"],
    "Accountant": ["create", "read", "update"]
  }
}
```

**Commit lock files alongside manifest changes.** They are the schema migration record — the diff engine needs them to know what the database currently looks like. Deleting them forces Aperture to treat every entity as new and regenerate all changesets from scratch.

## Generated code is never hand-edited

The `target/generated-sources/aperture/` directory is regenerated on every build. Any changes made directly to files there are overwritten. The correct workflow is:

1. Edit the manifest
2. Run `mvn verify`
3. The generated code reflects the new manifest
4. Commit: the manifest change + the updated lock files

If you need logic that goes beyond what manifests can express, use hooks — they let you attach your own code over HTTP at the right point in the request lifecycle.
