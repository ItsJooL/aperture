---
title: Manifest Schema
description: Complete YAML specification for every Aperture manifest kind.
---

# Manifest Schema

> Looking for a task-oriented tour instead of a field-by-field list? See [Manifest Authoring](/guide/manifests) for minimal working YAML per kind and per feature, including the `scopedBy` deep dive.

Manifests are YAML files in the `manifests/` directory (recursive). Every manifest has the same three top-level keys:

```yaml
apiVersion: aperture.itsjool.com/v1
kind: <Kind>
metadata:
  name: <name>
spec:
  ...
```

Seven kinds are available: `Entity`, `FrameworkConfig`, `ApiVersionConfig`, `AbacPolicy`, `RoleDefinition`, `PrincipalAttributeDefinition`, and `Migration`.

---

## Entity

The most common kind. Defines a domain entity with fields, permissions, policies, and hooks.

```yaml
apiVersion: aperture.itsjool.com/v1
kind: Entity
metadata:
  name: Invoice              # PascalCase; becomes the JSON:API type "invoice"
spec:
  description: "..."         # optional — included in generated OpenAPI
  tenantScoped: true         # default: false
  optimisticLocking: false   # default: false
  softDelete: false          # default: false
  scopedBy: account          # optional — name of a ManyToOne ref field
  plural: invoices           # optional — overrides default pluralisation
  fields: { ... }
  permissions: { ... }
  policies: { ... }
  hooks: { ... }
  mcp: { ... }               # optional — entity-level MCP override
```

### `spec.fields`

Each key under `fields` is a field name (camelCase). Each value is a field definition:

| Property | Type | Default | Description |
|---|---|---|---|
| `type` | `string`, `decimal`, `integer`, `boolean`, `uuid`, `datetime`, `ref` | — | Required. Maps to a Java and SQL type |
| `required` | boolean | `false` | Generates `NOT NULL` constraint and Elide validation |
| `unique` | boolean | `false` | Generates a unique index |
| `index` | boolean | `false` | Generates a non-unique index |
| `encrypted` | boolean | `false` | Stores value as AES-256-GCM ciphertext |
| `since` | string (version number) | `null` | Field is only visible in API v{since}+ |
| `renamedFrom` | string | `null` | Old column name — generates `renameColumn` changeset |
| `relation` | `ManyToOne`, `OneToMany` | `null` | Required when `type: ref` |
| `target` | string (entity name) | `null` | Required when `type: ref` |
| `mappedBy` | string (field name on target) | `null` | Required for `OneToMany` side of a bidirectional relationship |
| `description` | string | `null` | Included in generated OpenAPI |
| `enum` | list of strings | `null` | Generates an `IN (...)` validation and OpenAPI enum |

**`type: ref` notes:** `ManyToOne` generates an FK column (`{fieldName}_id`). `OneToMany` with `mappedBy` generates no column — it's the inverse side of a relationship declared on the target entity.

### `spec.permissions`

Role → list of operations. Operations: `create`, `read`, `update`, `delete`.

```yaml
permissions:
  Admin:      [read, delete]
  Accountant: [create, read, update]
  Viewer:     [read]
```

OR semantics: a user with any matching role gets access. Roles not listed get no access for that operation.

> **Reserved names:** `SuperAdmin` and `TenantAdmin` are platform authorities, not domain roles. They cannot appear in `permissions` — the build will fail if you reference them here. See [Auth & Identity](/guide/auth#identity-administration).

### `spec.policies`

Policy name → list of operations. References `AbacPolicy` manifests by `metadata.name`.

```yaml
policies:
  FinanceTeamOnly: [read, update]
  EuRegionOnly:    [read, update]
```

AND semantics: all policies listed for an operation must pass. Combined with RBAC: role check first (OR), then all policy checks (AND).

### `spec.hooks`

Hook name → hook definition. Hooks use semantic intent; Aperture maps that intent to the generated lifecycle phase and execution mode.

| Property | Type | Values | Description |
|---|---|---|---|
| `type` | string | `guard`, `validate`, `mutate`, `trigger` | Semantic hook category |
| `on` | array | `create`, `update`, `delete` | Optional write operations. Defaults depend on `type` |
| `onFailure` | string | `reject`, `warn`, `passthrough` | Optional behaviour on non-2xx or error. Valid values depend on `type` |
| `url` | string | HTTP/HTTPS URL | Endpoint Aperture calls |

```yaml
hooks:
  ValidateInvoice:
    type: validate
    on: [create, update]
    url: http://hook-service:8080/hooks/validate-invoice
```

### `spec.mcp` (entity-level override)

Narrow which tools are generated for one entity, while still participating in MCP:

```yaml
mcp:
  enabled: true       # required — see the note below
  tools: [list, get]  # override which tools to generate for this entity
```

Exclude the entity from MCP entirely — no tool class is generated for it, regardless of `tools`:

```yaml
mcp:
  enabled: false
```

Valid tool names are `list`, `get`, `create`, `update`, and `delete`.
An entity with no `mcp` block at all inherits the framework-level default tool set
(`spec.mcp.tools` from `FrameworkConfig`, or all five operations if that is also unset).
Supplying `tools` on an entity replaces the inherited list for that entity — it does not merge
with it.

**`enabled` has no implicit default of `true`** once you write an entity-level `mcp:` block — it
is a plain boolean field, so an `mcp:` block that supplies `tools` but omits `enabled` defaults to
`enabled: false` and the entity is silently excluded. Always set `enabled: true` explicitly
alongside `tools`, as in the first example above.

### Boolean spec fields

| Field | Default | Effect when `true` |
|---|---|---|
| `tenantScoped` | `false` | Adds `aperture_tenant_id` column; auto-filters all queries |
| `optimisticLocking` | `false` | Adds `version` column; requires `If-Match` on mutations |
| `softDelete` | `false` | Adds `deleted_at` column; filters `deleted_at IS NULL` |

### `spec.scopedBy`

Name of a `ManyToOne` ref field declared in `spec.fields`. Every read of this entity is filtered
by a per-request `X-Aperture-Scope-<Field>` header value. This is a mandatory query filter, not
per-user authorization — requests without a matching header value are denied, but any caller who
can set the header can pick which scope to read.

---

## FrameworkConfig

One per project. Sets tenancy mode, default roles, and MCP configuration.

```yaml
apiVersion: aperture.itsjool.com/v1
kind: FrameworkConfig
metadata:
  name: config
spec:
  tenancyMode: pool           # pool (default) | none
  defaultRoles:               # domain roles assigned to every new user
    - Accountant
    - Viewer
  # Note: TenantAdmin and SuperAdmin are platform authorities — do not list them here
  cli:
    binaryName: acme          # name of the generated CLI binary; defaults to "aperture"
  mcp:
    enabled: true
    transport: stateless      # stateless (HTTP) is the only supported transport
    tools: [list, get, create, update, delete]
```

`transport` is optional and currently accepts only `stateless`. Valid tool
names are `list`, `get`, `create`, `update`, and `delete`.

---

## ApiVersionConfig

Declares API versions. Aperture uses the versions declared here to generate versioned entity classes and to detect breaking changes.

```yaml
apiVersion: aperture.itsjool.com/v1
kind: ApiVersionConfig
spec:
  versions:
    "1":
      status: ACTIVE
    "2":
      status: ACTIVE
```

Version names are strings. `status` is `ACTIVE` or `SUNSET`. Sunset versions still serve requests but display a deprecation warning in the response headers.

---

## AbacPolicy

Defines a named ABAC rule using SpEL. Referenced by name in `spec.policies` on an entity.

```yaml
apiVersion: aperture.itsjool.com/v1
kind: AbacPolicy
metadata:
  name: FinanceTeamOnly
spec:
  description: "Finance team members only"
  expression: "#user.securityAttributes['department'] == 'finance'"
```

**SpEL context:** `#user` is the `AperturePrincipal`. Available properties:
- `#user.securityAttributes['key']` — security attribute map (from the `securityAttributes` JSONB column, admin-assigned)
- `#user.roles` — `Set<String>` of role names
- `#user.tenantId` — tenant ID string
- `#user.userId` — user ID string

---

## RoleDefinition

Declares the named roles available in the system. Referenced in permissions and assigned to users.

```yaml
apiVersion: aperture.itsjool.com/v1
kind: RoleDefinition
metadata:
  name: roles
spec:
  roles:
    Admin:
      description: "Full access within tenant"
    Accountant:
      description: "Financial operations"
    Viewer:
      description: "Read only"
```

> **Reserved names:** `SuperAdmin` and `TenantAdmin` cannot be declared here — they are platform authorities managed via `POST /manage/tenants/{id}/tenant-admins/{userId}`, not domain roles.

---

## PrincipalAttributeDefinition

Declares the security attribute keys that ABAC policies may reference. Definitions flow into the runtime to validate attributes on users, service accounts, and personal API keys.

```yaml
apiVersion: aperture.itsjool.com/v1
kind: PrincipalAttributeDefinition
metadata:
  name: principal-attributes
spec:
  securityAttributes:
    department:
      type: string
      allowedValues: [finance, engineering, sales, ops]
      personalKeyDelegation: exact     # API keys may only carry the user's exact value
      serviceAccountAssignable: false
    region:
      type: string
      allowedValues: [us, eu, apac]
      personalKeyDelegation: exact
      serviceAccountAssignable: true
    clearance:
      type: string
      allowedValues: [public, confidential, secret]
      personalKeyDelegation: exact
      serviceAccountAssignable: true
```

| Property | Type | Default | Description |
|---|---|---|---|
| `type` | `string` | `string` | Attribute value type (currently `string`) |
| `allowedValues` | list of strings | `[]` | If non-empty, values are validated against this list on assignment |
| `personalKeyDelegation` | `exact` | `exact` | How API key attribute delegation is validated. `exact` means the key may only carry the user's current value |
| `serviceAccountAssignable` | boolean | `false` | Whether this attribute may be assigned to a service account |

Attributes referenced in `AbacPolicy` expressions that are not declared here are still evaluated — the definition is for validation on write, not enforcement on read.

---

## Migration

Manual SQL migration with rollback. Included in the Liquibase changelog at the position specified.

```yaml
apiVersion: aperture.itsjool.com/v1
kind: Migration
metadata:
  name: backfill-phone-number
spec:
  position:
    after: create-customer-table    # changeset ID to insert this migration after
  sql: |
    UPDATE aperture_customers
    SET phone_number = '000-000-0000'
    WHERE phone_number IS NULL;
  rollback: |
    UPDATE aperture_customers
    SET phone_number = NULL
    WHERE phone_number = '000-000-0000';
```

`position.after` references a Liquibase changeset ID (the `id=` attribute). Run `mvn liquibase:status` to list changeset IDs. The migration is applied once and tracked by Liquibase.
