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

Eight kinds are available: `Entity`, `OneOf`, `FrameworkConfig`, `ApiVersionConfig`, `AbacPolicy`, `RoleDefinition`, `PrincipalAttributeDefinition`, and `Migration`.

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
| `type` | `string`, `decimal`, `integer`, `boolean`, `uuid`, `datetime`, `ref`, `oneof` | — | Required. Maps to a Java and SQL type |
| `required` | boolean | `false` | Requires a value through generated validation and database constraints |
| `unique` | boolean | `false` | Generates a unique index |
| `index` | boolean | `false` | Generates a non-unique index |
| `encrypted` | boolean | `false` | Stores value as AES-256-GCM ciphertext |
| `since` | string (version number) | `null` | Field is only visible in API v{since}+ |
| `renamedFrom` | string | `null` | Old column name — generates `renameColumn` changeset |
| `relation` | `ManyToOne`, `OneToMany` | `null` | Required when `type: ref` |
| `target` | string (entity or OneOf name) | `null` | Required when `type: ref` or `type: oneof` |
| `mappedBy` | string (field name on target) | `null` | Required for `OneToMany` side of a bidirectional relationship |
| `description` | string | `null` | Included in generated OpenAPI |
| `enum` | list of strings | `null` | Generates an `IN (...)` validation and OpenAPI enum |

**`type: ref` notes:** `ManyToOne` generates an FK column (`{fieldName}_id`). `OneToMany` with `mappedBy` generates no column — it's the inverse side of a relationship declared on the target entity.

**`type: oneof` notes:** `target` names a `OneOf` manifest. The field generates `{fieldName}_type`
and `{fieldName}_id` columns and is represented as a JSON:API relationship whose `data.type` is the
concrete member resource type. `relation` and `mappedBy` do not apply to `oneof` fields and are
rejected. Aperture always creates a composite lookup index over the type and ID columns (unique
when `unique: true`, non-unique otherwise). In POOL mode for a tenant-scoped owner, the tenant
column is the leading index column. For `required: true`, Aperture validates JSON:API resource and
relationship writes and generates a deferred database constraint. The generated Hibernate
association remains nullable only for transaction staging, so JSON:API Atomic Operations can insert
a resource and attach its relationship later in the same transaction. This is not optional domain
state: request validation rejects missing or cleared relationships, and the transaction cannot
commit while either pointer column is null.

## OneOf

Declares a named closed set of entities that a `type: oneof` field may target.

```yaml
apiVersion: aperture.itsjool.com/v1
kind: OneOf
metadata:
  name: Billable
spec:
  members:
    - Product
    - ServicePackage
    - SubscriptionPlan
```

| Property | Type | Default | Description |
|---|---|---|---|
| `spec.members` | list of entity names | — | Required. Every member must name an `Entity` manifest |

Validation rules:

- Every member must be a known entity.
- A member entity may belong to only one `OneOf`.
- Members must share the same `tenantScoped` shape.
- A `type: oneof` field's `target` must name a known `OneOf`.
- A global owner entity cannot reference a tenant-scoped `OneOf`; there would be no owner tenant
  with which to constrain the target lookup.

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
| `retries` | integer | `0`–`5`, default `0` | Optional, opt-in retry count on a failed call. The schema's own ceiling (5) is the loosest per-type cap (`trigger`'s); `guard`/`validate` are capped tighter at `2`, and `mutate` does not support it at all — both enforced by manifest validation, not this schema, since a per-`type` maximum isn't expressible here. See [Hooks & Lifecycle → Retries And Timeouts](../guide/hooks.md#retries-and-timeouts) for the backoff formula and the latency this adds to synchronous hook types. |

```yaml
hooks:
  ValidateInvoice:
    type: validate
    on: [create, update]
    url: http://hook-service:8080/hooks/validate-invoice
    retries: 2
```

### `spec.mcp` (entity-level override)

The MCP tool surface is **derived from the model**, not hand-declared: an operation is exposed as
an MCP tool only if the entity's own `permissions`, `policies`, or `publicOperations` already
grant it. This block can only *restrict* that derived surface further — it can never widen it.

The effective tool set for an entity is:

```
derived(entity) ∩ ceiling(FrameworkConfig.spec.mcp.tools) ∩ narrowing(entity.spec.mcp.tools)
```

where `derived(entity)` maps `read → list, get`, `create → create`, `update → update`, and
`delete → delete`, and an operation counts as derived iff some role in `permissions`, some policy
in `policies`, or an entry in `publicOperations` grants it. Most entities need no `mcp:` block at
all — the full block collapses to nothing to configure:

```yaml
mcp:
  tools: [list, get]  # narrow this entity's tools beyond what it already derives
```

Exclude the entity from MCP entirely — no tool class is generated for it, regardless of `tools`:

```yaml
mcp:
  enabled: false
```

Valid tool names are `list`, `get`, `create`, `update`, and `delete`. `enabled` absent (or the
`mcp:` block absent entirely) means **inherit**: the entity participates in MCP. Only an explicit
`enabled: false` excludes it — there is no implicit-`false` trap here, because `enabled` is a
tri-state field (`null` means unset, not "off"). `enabled: false` together with a non-empty
`tools` list is a validation error: it's contradictory, and Aperture rejects the manifest rather
than silently picking one interpretation.

Listing a tool the entity's own access rules don't reach, or one outside the framework ceiling, is
also a validation error — `tools: [delete]` on an entity where nothing grants `delete` describes a
tool that could never succeed for anyone but a superadmin, and Aperture rejects it at parse time
rather than generating it.

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
    tools: [list, get]        # optional ceiling — see below
```

`transport` is optional and currently accepts only `stateless`. `tools` is a **ceiling, not a
default**: the framework-wide upper bound on MCP tools any entity may expose, applied on top of
whatever each entity's own `permissions`/`policies`/`publicOperations` already derive (see
`Entity.spec.mcp` above for the full derive/ceiling/narrow resolution rule). Omitting it means no
ceiling — every entity's derived tools remain possible, subject only to entity-level narrowing.
Valid tool names are `list`, `get`, `create`, `update`, and `delete`.

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
