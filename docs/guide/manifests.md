---
title: Manifest Authoring
description: Every manifest kind and field-level feature, each with a minimal working YAML snippet and a pointer to the deep dive.
---

# Manifest Authoring

This page is the practical, by-example companion to the [Manifest Schema reference](/reference/manifest-schema). Where the reference lists every field, this page shows the smallest YAML that exercises each feature, says when you'd reach for it, and links out to the deep-dive guide. It does not repeat what those pages already say well — start with [Core Concepts](/guide/core-concepts) for the manifest directory layout, the build pipeline, and lock files.

## Manifest kinds

Every manifest is a YAML file under `manifests/` (any subdirectory, read recursively) with the same three top-level keys — `apiVersion`, `kind`, `metadata.name` — plus a `spec` whose shape depends on `kind`. Eight kinds exist:

| Kind | Declares |
|---|---|
| [`Entity`](#entity) | A domain entity: fields, permissions, policies, hooks |
| [`OneOf`](#oneof) | A named closed set of entities for one-of relationship fields |
| [`ApertureConfig`](#apertureconfig) | Tenancy mode, default roles, MCP, CLI binary name — one per project |
| [`ApiVersionConfig`](#apiversionconfig) | API versions and their ACTIVE/SUNSET status |
| [`RoleDefinition`](#roledefinition) | The named domain roles available in the system |
| [`AbacPolicy`](#abacpolicy) | A named attribute-based access rule, referenced by name in `policies` |
| [`PrincipalAttributeDefinition`](#principalattributedefinition) | The security attribute keys ABAC policies may reference |
| [`Migration`](#migration) | A manual SQL migration with rollback |

Each kind has a full field-by-field table in the [schema reference](/reference/manifest-schema). What follows is the minimal instance of each.

### Entity

```yaml
apiVersion: aperture.itsjool.com/v1
kind: Entity
metadata:
  name: Product
spec:
  fields:
    name:
      type: string
      required: true
      unique: true
  permissions:
    Viewer: [read]
```

`spec.fields` is the only required key. Everything else — `tenantScoped`, `scopedBy`, `optimisticLocking`, `softDelete`, `permissions`, `policies`, `hooks` — is covered section by section below. See [Entity](/reference/manifest-schema#entity) for the full field table.

### OneOf

```yaml
apiVersion: aperture.itsjool.com/v1
kind: OneOf
metadata:
  name: Billable
spec:
  members: [Product, ServicePackage, SubscriptionPlan]
```

Names a closed set of entity types that a `type: oneof` field may reference. See
[One-of relationships](#one-of-relationships) for field usage and runtime shape.

### ApertureConfig

```yaml
apiVersion: aperture.itsjool.com/v1
kind: ApertureConfig
metadata:
  name: config
spec:
  tenancyMode: pool          # pool (default) | none
  defaultRoles: [Viewer]
```

One per project. See [Multi-Tenancy](/guide/multi-tenancy) for `tenancyMode`, and [MCP exposure](#mcp-exposure) / [CLI generation config](#cli-generation-config) below for the rest of this kind's spec.

### ApiVersionConfig

```yaml
apiVersion: aperture.itsjool.com/v1
kind: ApiVersionConfig
metadata:
  name: versions
spec:
  versions:
    "1":
      status: ACTIVE
```

Declares which API versions exist and whether each is `ACTIVE` or `SUNSET`. Fields gated with `since:` (see [Fields, types, and constraints](#fields-types-and-constraints)) only appear in versions at or above that number. See [Build & Deploy → API versioning](/guide/build-deploy#api-versioning) for how this drives generated versioned entity classes.

### RoleDefinition

```yaml
apiVersion: aperture.itsjool.com/v1
kind: RoleDefinition
metadata:
  name: roles
spec:
  roles:
    Viewer:
      description: "Read only"
```

Declares the domain roles available to reference in `permissions` blocks and assign to users. `SuperAdmin` and `TenantAdmin` are platform authorities, not domain roles — declaring them here fails the build. See [Auth & Identity → Identity administration](/guide/auth#identity-administration).

### AbacPolicy

```yaml
apiVersion: aperture.itsjool.com/v1
kind: AbacPolicy
metadata:
  name: FinanceTeamOnly
spec:
  expression: "#user.securityAttributes['department'] == 'finance'"
```

A named SpEL rule, referenced by name from an entity's `policies` block. See [Permissions, roles, and ABAC policies](#permissions-roles-and-abac-policies) below.

### PrincipalAttributeDefinition

```yaml
apiVersion: aperture.itsjool.com/v1
kind: PrincipalAttributeDefinition
metadata:
  name: principal-attributes
spec:
  securityAttributes:
    department:
      type: string
      allowedValues: [finance, engineering, sales]
```

Declares the security attribute keys that ABAC policies and API key delegation may reference. See [PrincipalAttributeDefinition](/reference/manifest-schema#principalattributedefinition) for the delegation and service-account-assignable flags.

### Migration

```yaml
apiVersion: aperture.itsjool.com/v1
kind: Migration
metadata:
  name: backfill-phone-number
spec:
  sql: |
    UPDATE aperture_customers SET phone_number = '000-000-0000' WHERE phone_number IS NULL;
  rollback: |
    UPDATE aperture_customers SET phone_number = NULL WHERE phone_number = '000-000-0000';
```

For hand-written SQL that doesn't map to a manifest field change — backfills, data fixes. `rollback` is required; the build fails without it. See [Build & Deploy → Manual migration manifests](/guide/build-deploy#manual-migration-manifests).

## Fields, types, and constraints

```yaml
fields:
  name:
    type: string
    required: true
    unique: true
  internalNotes:
    type: string
    encrypted: true
  legacyCode:
    type: string
    index: true
  phoneNumber:
    type: string
    since: 2
    renamedFrom: phone
```

`unique: true` generates a unique index (and is required for encrypted fields you also want to filter on — see [deterministic encryption](/guide/security-audit#field-level-encryption)). `index: true` generates a plain, non-unique index for a field you filter or sort on often. `since:`/`renamedFrom:` are for versioned rollout and zero-downtime renames — see [Build & Deploy → Schema automation](/guide/build-deploy#schema-automation). `encrypted:` is covered in [Security & Audit → Field-level encryption](/guide/security-audit#field-level-encryption). The full type table (`string`, `decimal`, `integer`, `boolean`, `uuid`, `datetime`, `ref`, `oneof`) is in [Core Concepts](/guide/core-concepts#field-types) and [the reference](/reference/manifest-schema#spec-fields).

## Relationships

```yaml
# Invoice
fields:
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
```

A `ManyToOne` field generates an FK column (`{fieldName}_id`) and is the only kind of relationship field that can also be named in `scopedBy` (below). A `OneToMany` field with `mappedBy` is the inverse side of a relationship declared on the target entity — it generates no column of its own. `target` must name another entity declared somewhere in the manifest set, or the build fails with an unknown-relationship-target error. See [`type: ref` notes](/reference/manifest-schema#spec-fields) in the reference.

## One-of relationships

Use a `OneOf` manifest when a relationship field can point at one member of a closed set of entity
types:

```yaml
apiVersion: aperture.itsjool.com/v1
kind: OneOf
metadata:
  name: Billable
spec:
  members: [Product, ServicePackage, SubscriptionPlan]
```

An entity field then targets that named model concept:

```yaml
fields:
  billable:
    type: oneof
    target: Billable
    description: "Product, service package, or subscription plan associated with this line item"
```

The build validates that every member is an entity, each member belongs to only one `OneOf`, and all
members have the same tenant shape. A global owner cannot reference a tenant-scoped one-of set:
without an owner tenant, Aperture could not safely constrain the member lookup. `relation` and
`mappedBy` are invalid on a `oneof` field because the selected-member pointer defines its storage shape:
a `oneof` field is always to-one — one owning row selects exactly one member. There is no
collection-shaped `oneof` (no "many billables per line item"); model a repeated selection with
multiple owner rows instead, the way this demo gives each `LineItem` its own `billable` pointer.

In generated storage, the field becomes two columns: `{field}_type` and `{field}_id`, plus a
composite index (unique when `unique: true`, non-unique otherwise). For tenant-scoped owners in
POOL mode, that index is `(aperture_tenant_id, {field}_type, {field}_id)` so selected-member lookups
retain tenant locality. JSON:API clients send the concrete resource type in the relationship data:

```json
{
  "relationships": {
    "billable": { "data": { "type": "servicepackages", "id": "…" } }
  }
}
```

Set `required: true` when every owner must select a member. Aperture rejects ordinary and atomic
resource writes that omit the relationship, rejects attempts to clear it, and enforces the same
invariant at transaction commit. The generated Hibernate association remains nullable only for
transaction staging; it does not make the domain relationship optional. The database check is
deferred deliberately because Atomic Operations stages new resources before wiring their
relationships, so a valid batch may temporarily contain an unset pointer but can never commit one.

Adding a member to a `OneOf` is treated as a compatible model expansion. Removing a member or
deleting a `OneOf` is a breaking model change because existing rows may still point at that member.

## Tenant scoping (`tenantScoped`)

```yaml
spec:
  tenantScoped: true
```

Adds an `aperture_tenant_id` column and auto-filters (and auto-injects on create) every query by the caller's tenant, in POOL mode. Use it for core domain entities that belong to one tenant; leave it `false` for shared reference data (countries, currencies, product catalogs). This is a full guide's worth of behavior on its own — see [Multi-Tenancy](/guide/multi-tenancy) for POOL vs. NONE, tenant-aware foreign keys, and provisioning.

## Scoping by relationship (`scopedBy`)

`scopedBy` partitions an entity's rows by the value of a relationship, carried per-request in a header. It is new, and it is easy to reach for expecting it to behave like `tenantScoped` — it doesn't. Read this section before you use it.

**`scopedBy` is partitioning, not authorization.** Declaring `scopedBy: project` means every read of that entity requires a `X-Aperture-Scope-Project` header naming *which* project's rows to return. It does **not** check whether the caller is allowed to see that project — any authenticated caller who already has ordinary `read` permission on the entity can put any value in that header and read that partition. If you need per-user or per-role restriction on top of the partition, pair `scopedBy` with an `AbacPolicy`, shown below.

### Declaring it

```yaml
apiVersion: aperture.itsjool.com/v1
kind: Entity
metadata:
  name: Task
spec:
  tenantScoped: true
  scopedBy: project
  fields:
    title:
      type: string
      required: true
    project:
      type: ref
      target: Project
      relation: ManyToOne
      required: true
  permissions:
    Member: [create, read, update]
    Admin:  [create, read, update, delete]
  policies:
    ProjectTeamOnly: [read, update]
```

```yaml
apiVersion: aperture.itsjool.com/v1
kind: AbacPolicy
metadata:
  name: ProjectTeamOnly
spec:
  description: "Only the platform team may touch scoped tasks"
  expression: "#user.securityAttributes['team'] == 'platform'"
```

- **Opt-in, per entity.** Only entities that declare `scopedBy` get this filter; every other entity is untouched. This is true by construction, not convention — the filter only exists for an entity whose manifest names it.
- **Must name a declared `ManyToOne` ref field.** `scopedBy: project` requires a `project` field with `type: ref` and `relation: ManyToOne` on the same entity. Naming an unknown field, a scalar field, or a `OneToMany` field fails the build.
- **The header is `X-Aperture-Scope-<Field>`.** For `scopedBy: project`, send `X-Aperture-Scope-Project: <project-id>`. The field name is matched **case-insensitively** — the header suffix is canonicalized to lowercase on both the request-parsing side and the generated filter, so `X-Aperture-Scope-Project`, `X-Aperture-Scope-PROJECT`, and a camelCase manifest field like `scopedBy: parentProject` (matched via `X-Aperture-Scope-Parentproject`) all resolve to the same key.
- **Fail-closed, on every operation — but the failure *shape* differs.** There is no "no scope means everything" fallback; a missing header never widens access. What you get back depends on whether the request evaluates the filter against a **collection** or a **single object**. A **collection read** (`GET /api/v1/tasks`) with no header returns `200` with an **empty page**: Elide compiles the scope check into a SQL `WHERE` predicate for list queries, so nothing matches and no `ForbiddenAccessException` surfaces. **Single-object evaluation** returns `403 Forbidden` — this covers a fetch by id (`GET /api/v1/tasks/<id>`) *and* the read-back a `create`/`update` performs to echo the record it just wrote (so an unscoped `create` still comes back 403 even though the write itself was valid). Either way, no data leaks without the header.
- **SuperAdmin bypasses it**, the same as tenant scoping.
- **Applies to JSON:API and GraphQL alike.** The filter is an Elide-level permission check on the generated entity class, not something wired into one API surface — every transport that serves the entity is filtered the same way.

```
GET /api/v1/tasks
Authorization: Bearer <token>
X-Aperture-Scope-Project: 8f14e45f-ceea-4c3f-b8b8-6a1f2f4f5e11
```

Without the header, this collection query returns `200` with an empty page (the scope predicate matches nothing). With it, Elide adds `WHERE task.project.id = :scope` to the query, on top of any tenant filter already in play. A fetch of a single task by id, by contrast, `403`s without the header — see the fail-closed note above.

### Pair it with ABAC for real per-scope access control

The header narrows *which* partition is queried; it never checks whether the caller *should* see that partition — that's exactly what `policies:` is for. In the example above, `scopedBy: project` picks the partition and `ProjectTeamOnly` (an `AbacPolicy` evaluated against `#user.securityAttributes`) decides who's allowed to query it at all. Neither one substitutes for the other: `scopedBy` alone lets any caller with `read` permission pick any project; `policies` alone doesn't understand "which project" at all. Use both together whenever a scope value should actually gate access, not just organize the result set. See [Permissions, roles, and ABAC policies](#permissions-roles-and-abac-policies) below for how RBAC and ABAC compose.

### The write path: validate on mismatch, fail closed on absence

Unlike `tenantScoped` (which auto-injects the tenant id on create), `scopedBy` never assigns the relationship for you. What it does on a write is two separate checks, with two separate failure modes:

1. **Mismatch → 400, before anything is persisted.** If the request carries a scope value for the entity's `scopedBy` field and the relationship in the payload names a *different* one, the write is rejected outright. Creating a `Task` pointed at a different project than the one named in `X-Aperture-Scope-Project` fails this way.
2. **Absence → 403, on the response.** Omitting the scope header entirely does **not** let the write through unchecked. A JSON:API response echoes the record it just wrote, and that echo is a read — subject to the same fail-closed scope filter as a plain `GET`. So a `create`/`update` with no scope header at all still comes back `403 Forbidden`, even though the mismatch check found nothing to reject and the row may already be written.

The practical rule: **every operation on a scoped entity needs the matching scope header, full stop.** There's no such thing as an unscoped write that "goes through untouched" — it either matches the header (succeeds), contradicts it (400), or lacks it (403 on read-back). Scope is read context the server checks going in and checks again coming out — it is not an ownership grant, and it will not silently repoint a relationship the way tenant scoping fills in `apertureTenantId`.

### Publishing `scopedBy` is a breaking change

Because the read filter is fail-closed, adding `scopedBy` to an entity that's already published (or repointing it at a different field) silently breaks every existing client that isn't yet sending the header — list calls start returning empty pages and fetch-by-id / write read-backs start returning 403. The diff engine flags both as **breaking**, same as removing a field or changing its type — it requires an API version bump. Removing `scopedBy` only widens access and is classified as safe.

### From the CLI

The generated CLI carries scope context the same way it carries tenant context: `--scope <field>=<value>` for a one-off command, or `config set-scope`/`config unset-scope` to persist it on a profile. See [Scope context (`scopedBy`)](/guide/cli#scope-context-scopedby) in the Generated CLI guide for the full flag reference and precedence rules.

## Optimistic locking and soft delete

```yaml
spec:
  optimisticLocking: true
  softDelete: true
```

`optimisticLocking` adds a `version` column and requires an `If-Match` header on `PUT`/`PATCH`/`DELETE`, returning `412` on a stale version and `428` if the header is missing — see [Security & Audit → Optimistic locking](/guide/security-audit#optimistic-locking). `softDelete` adds a `deleted_at` column and filters `deleted_at IS NULL` into every read automatically. Both default to `false`.

## Permissions, roles, and ABAC policies

```yaml
permissions:
  Accountant: [create, read, update]
  Viewer:     [read]
policies:
  FinanceTeamOnly: [read, update]
```

RBAC (`permissions`) is OR: any role the caller holds that's listed for an operation grants it. ABAC (`policies`) is AND on top of that: every listed policy for an operation must also pass. `SuperAdmin` and `TenantAdmin` cannot appear in either block — they're platform authorities, not domain roles. Full semantics, the invite/role-assignment flow, and the audit trail are in [Security & Audit](/guide/security-audit) and [Auth & Identity](/guide/auth).

Two build-time rules worth knowing before you write policies:

- **Every declared `AbacPolicy` must be used.** If a policy manifest exists but no entity's `policies` block references it, the build fails with an unattached-policy error.
- **SpEL expressions can reference `#user` on every operation, `#record` (the existing row) on `read`/`update`/`delete`, and `#input` (the incoming payload) on `create`** — but never `#record` on `create` (there's no existing row yet) or `#input` outside of it. Mixing them fails validation. The demo policies only use `#user.securityAttributes[...]`, which is valid everywhere and the pattern to reach for first.

## Lifecycle hooks

```yaml
hooks:
  ValidateInvoice:
    type: validate
    on: [create, update]
    onFailure: reject
    url: http://hook-service:8080/hooks/validate-invoice
```

A hook's `type` declares what it does; the framework maps each type to the lifecycle phase and blocking behavior that fits:

| Type       | Runs                        | Blocking                        | Default `on`           |
|------------|-----------------------------|---------------------------------|------------------------|
| `guard`    | before security & persistence | sync, rejects the request      | create, update, delete |
| `validate` | before commit               | sync, rejects the request       | create, update         |
| `mutate`   | before commit (enrichment)  | sync, may rewrite the entity    | create, update         |
| `trigger`  | after commit                | async, fire-and-forget          | create, update, delete |

`on` is optional and defaults per type. See [Hooks & Lifecycle](/guide/hooks) for what each type is for, the request/response shape, signing, retries, and the legacy phase/async compatibility mapping.

## MCP exposure

```yaml
# framework/config.yaml
spec:
  mcp:
    enabled: true
    transport: stateless
```

Turns on MCP tool generation project-wide. That's it in the common case — the MCP tool surface is
**derived from the model**, not hand-declared. An entity's own `permissions`, `policies`, and
`publicOperations` already say which CRUD operations it permits; MCP maps those onto tool names
(`read → list, get`; `create → create`; `update → update`; `delete → delete`) and exposes exactly
that set. Most entities need no `mcp:` block of their own at all.

**Every MCP knob is subtractive.** Configuration can only restrict this derived projection of the
model — never extend it. There are two knobs, both optional, and both narrowing:

- `spec.mcp.tools` on `ApertureConfig` is a **ceiling**: a project-wide upper bound on which tools
  any entity may expose, applied on top of what each entity already derives. Omit it and there is
  no ceiling.
- `spec.mcp` on an `Entity` **narrows further**, and can exclude the entity from MCP entirely with
  `enabled: false`. See [`spec.mcp`](/reference/manifest-schema#spec-mcp-entity-level-override) in
  the reference for the full resolution rule and the entity-level knob's tri-state `enabled`
  semantics (absent means inherit, not excluded).

The effective tool set for an entity is `derived(entity) ∩ ceiling ∩ narrowing`. Declaring a tool
that the entity's own access rules don't reach, or that the ceiling excludes, fails manifest
validation rather than silently producing a tool nobody could ever call — an agent-facing tool
list functions like a prompt on every conversation, so the framework prefers a validation error at
build time over quietly showing an agent something it's tempted to try and can never do.

`stateless` is the only supported MCP transport today. Valid tool names are
`list`, `get`, `create`, `update`, and `delete`; invalid names fail manifest
validation during generation.

### Relationship fields as tool parameters

A `ManyToOne` field is generated as a tool parameter named `<field>_id`. Given the demo's `Task`
entity with a `project` field (`type: ref`, `relation: ManyToOne`, `target: Project`), the
generated `createTask`/`updateTask` tools take a `project_id` string parameter, not `project`.

A `oneof` field is generated as two tool parameters: `<field>_type` and `<field>_id`. The type is
the JSON:API resource type for the concrete member (`products`, `servicepackages`, and so on), not
the `OneOf` manifest name.

At the JSON:API layer, the adapter writes scalar fields into `data.attributes` and relationship
fields into `data.relationships.<field>.data`, keyed by the manifest field name (`project`, not
`project_id`):

```json
{
  "data": {
    "type": "tasks",
    "attributes": { "title": "Ship the release", "status": "TODO" },
    "relationships": {
      "project": { "data": { "type": "projects", "id": "…" } }
    }
  }
}
```

On `updateTask`, a relationship parameter that's left unset (`null`) is omitted from the request
body entirely rather than sent as an explicit null — a partial update that doesn't mention
`project_id` leaves the task's existing project untouched. `OneToMany` fields never become tool
parameters; they have no column of their own and are read via the owning side.

## CLI generation config

```yaml
# framework/config.yaml
spec:
  cli:
    binaryName: acme
```

`spec.cli.binaryName` names the generated CLI binary (defaults to `aperture`); it only matters if the Maven plugin's CLI generator is enabled, which is a separate, off-by-default `pom.xml` setting. See the [Generated CLI guide](/guide/cli) for enabling generation, native builds, and everything else about the generated binary.

## Extending MCP

Beyond the per-entity tools the generator emits from `spec.mcp` and `mcp.enabled`, there are two
separate seams for adding MCP behavior — one at build time, one at runtime.

### Build time: contributing new tool classes

`McpToolContribution` (in `aperture-mcp-spi`) adds entirely new generated tool classes alongside
the per-entity ones — for example, a tool that isn't tied to any single entity.

Generated MCP tool classes are ordinary Spring `@Component` beans with `@Tool`-annotated methods,
discovered at runtime by the reflective `ToolCallbackProvider` that
`ApertureMcpAutoConfiguration#apertureToolCallbackProvider` builds by scanning
`com.itsjool.aperture.generated.mcp` for `@Component` beans with `@Tool` methods. But generation
runs inside the Maven build — long before the generated project's own JVM exists — so there is no
live MCP server or application context yet to register a tool instance with. `McpToolContribution`
implementations therefore act as **source emitters**, exactly like the CLI's
`CliCommandContribution`: `toolSource(latestApiVersion)` returns the complete Java source of a
`@Component` class with one or more `@Tool`-annotated methods, which the MCP generation target
writes into the generated project's `com.itsjool.aperture.generated.mcp` package alongside the
entity tool classes. The result is an ordinary generated-project class — no reflection or
ServiceLoader machinery involved at generation time, and fully native-image friendly.

Contributed tools have no entity or manifest operation, so they are never governed by the
principal-scoped `tools/list` registry (see [MCP configuration](/reference/configuration#mcp-aperture-mcp-spring-ai-mcp)):
they are always listed to every caller, regardless of `aperture.mcp.tool-list-scope`.

```java
public interface McpToolContribution {
    String id();
    String toolClassName();
    String toolSource(String latestApiVersion);
}
```

Implementations are configured explicitly under the Maven plugin's `<mcp><extensions>` list —
not ServiceLoader-discovered — and the implementation class must be on the `aperture-maven-plugin`
plugin's classpath (typically via a `<dependency>` on the `<plugin>` element):

```xml
<plugin>
  <groupId>com.itsjool</groupId>
  <artifactId>aperture-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>com.itsjool</groupId>
      <artifactId>billing-mcp-tools</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>
  <configuration>
    <mcp>
      <extensions>
        <extension>com.example.billing.mcp.BillingMcpToolContribution</extension>
      </extensions>
    </mcp>
  </configuration>
</plugin>
```

A minimal implementation, following the same conventions as the generator's own entity tool
classes (a `McpRequestAdapter` constructor dependency, `@ToolParam` on parameters):

```java
public class BillingMcpToolContribution implements McpToolContribution {
    @Override public String id() { return "billing-mcp-tools"; }
    @Override public String toolClassName() { return "BillingMcpTools"; }

    @Override
    public String toolSource(String latestApiVersion) {
        return """
            package com.itsjool.aperture.generated.mcp;

            import com.itsjool.aperture.mcp.McpRequestAdapter;
            import org.springframework.stereotype.Component;
            import org.springframework.ai.tool.annotation.Tool;

            @Component
            public class BillingMcpTools {
                private final McpRequestAdapter adapter;

                public BillingMcpTools(McpRequestAdapter adapter) {
                    this.adapter = adapter;
                }

                @Tool(name = "current_balance", description = "Look up the current billing balance")
                public String currentBalance() {
                    return adapter.get("/api/v%s/billing/balance");
                }
            }
            """.formatted(latestApiVersion);
    }
}
```

A misconfigured contribution fails the build loudly rather than silently corrupting the generated
project: the generator rejects an invalid `toolClassName()`, source that doesn't declare package
`com.itsjool.aperture.generated.mcp`, a declared top-level type that doesn't match
`toolClassName()`, and two contributions (or a contribution and an entity) that both try to emit
the same class name.

### Runtime: replacing the request adapter

Generated `@Tool` methods depend on `McpRequestAdapter`, not a concrete class. The default bean,
`McpElideAdapter`, dispatches straight into Elide's `JsonApiController` and is declared
`@ConditionalOnMissingBean(McpRequestAdapter.class)` in `ApertureMcpAutoConfiguration`. Declare
your own `@Bean` of type `McpRequestAdapter` in your application and it replaces the default
wholesale — every generated and contributed tool class calls through it instead:

```java
@Bean
public McpRequestAdapter mcpRequestAdapter(/* your dependencies */) {
    return new GatewayRoutingMcpRequestAdapter(/* ... */);
}
```

This is the seam to reach for to route MCP traffic through a gateway, add caching, or target a
different Elide setup — and it mirrors the audit epic's `AuditWriter` seam: a well-known interface
with a conditional default implementation that a single `@Bean` declaration replaces.
