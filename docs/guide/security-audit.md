---
title: Security & Audit
description: RBAC, ABAC policies, field encryption, optimistic locking, rate limiting, and the audit trail.
---

# Security & Audit

## Role-based access control (RBAC)

Permissions are declared per entity per role in the manifest:

```yaml
permissions:
  Admin:      [read, delete]
  Accountant: [create, read, update]
  Viewer:     [read]
```

The available operations are `create`, `read`, `update`, and `delete`. The semantics are OR: any role the current user holds that is listed for the requested operation grants access. A `Viewer` can read. An `Admin` can read or delete, but not create or update.

The Maven plugin translates these into Elide `@ReadPermission`, `@CreatePermission`, `@UpdatePermission`, and `@DeletePermission` annotations on the generated entity class.

Roles are named strings declared in a `RoleDefinition` manifest and assigned to users at account creation or update time. The billing demo uses two domain roles: `Accountant` and `Viewer`.

> **Note:** `SuperAdmin` and `TenantAdmin` are **platform authorities** stored as boolean flags on `AperturePrincipal`, not domain roles. Do not declare them in `RoleDefinition` manifests or reference them in entity permissions — they are for platform-level management operations only.

## Attribute-based access control (ABAC)

ABAC policies are SpEL expressions evaluated against the current principal's attributes at request time. They are declared as `AbacPolicy` manifests:

```yaml
apiVersion: aperture.itsjool.com/v1
kind: AbacPolicy
metadata:
  name: FinanceTeamOnly
spec:
  expression: "#user.securityAttributes['department'] == 'finance'"
```

```yaml
apiVersion: aperture.itsjool.com/v1
kind: AbacPolicy
metadata:
  name: EuRegionOnly
spec:
  expression: "#user.securityAttributes['region'] == 'eu'"
```

Policies are referenced by name in the entity manifest:

```yaml
policies:
  FinanceTeamOnly: [read, update]
  EuRegionOnly:    [read, update]
```

The semantics are AND (per policy) combined with RBAC (OR):

1. The user's role must grant the operation (RBAC, OR semantics)
2. **All** listed policies for that operation must pass (ABAC, AND semantics)

A user with the `Accountant` role and `department: finance` and `region: eu` can read and update invoices. An `Accountant` without `department: finance` cannot — even though the role grants access. Both `FinanceTeamOnly` and `EuRegionOnly` must pass.

Security attributes are admin-assigned claims stored in the JSONB `securityAttributes` column on the user record. They are set by administrators at account creation or when updating a user — ordinary users cannot modify their own security attributes. ABAC policies enforce attribute-based access by checking these admin-controlled values.

## Field-level encryption

Individual fields can be encrypted at rest by adding `encrypted: true` in the manifest:

```yaml
fields:
  email:
    type: string
    encrypted: true
```

The implementation uses AES-256-GCM with a random 12-byte IV generated per value. The encrypted value is stored in PostgreSQL as a Base64-encoded string: `IV (12 bytes) || ciphertext || GCM auth tag`.

The encryption key is a 32-byte Base64-encoded value provided via environment variable — never in code:

```yaml
aperture:
  encryption:
    local:
      key: ${APERTURE_ENCRYPTION_KEY}
```

**What "encrypted at rest" means here:** the column value in PostgreSQL is ciphertext. An attacker with direct database access sees Base64 strings, not plaintext. Decryption happens in the JVM at read time. The key must be present and correct or decryption fails.

Deterministic encryption (same IV derived from plaintext SHA-256) is also supported for fields that need `unique:` constraints, since random IVs produce different ciphertexts for the same value.

## Optimistic locking

Add `optimisticLocking: true` to an entity to require concurrent-update protection:

```yaml
spec:
  optimisticLocking: true
```

The changeset generator adds a `version INTEGER NOT NULL DEFAULT 0` column. Every API response includes an `ETag` header containing the current version. `PUT`, `PATCH`, and `DELETE` requests must include the `If-Match` header:

```bash
# Fetch resource and note ETag
curl -I http://localhost:8080/api/v1/customers/1 \
  -H "Authorization: Bearer $TOKEN"
# ETag: "0"

# Update with ETag
curl -X PATCH http://localhost:8080/api/v1/customers/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "If-Match: \"0\"" \
  -H "Content-Type: application/vnd.api+json" \
  -d '...'
```

| Scenario | Response |
|---|---|
| `If-Match` matches current version | 200 OK, version incremented |
| `If-Match` is stale (another write occurred) | 412 Precondition Failed |
| `If-Match` header missing | 428 Precondition Required |

Use optimistic locking for high-contention entities (e.g., `Customer`) where silent overwrites of concurrent edits would cause data loss.

## Rate limiting

The built-in rate limiter uses three independent token buckets keyed by IP, user, and tenant. The default limits match the runtime defaults and are configurable:

```yaml
aperture:
  rate-limit:
    enabled: true
    backend: valkey
    ip:
      capacity: 100
      refillTokens: 100
      windowSeconds: 60
    user:
      capacity: 50
      refillTokens: 50
      windowSeconds: 60
    tenant:
      capacity: 500
      refillTokens: 500
      windowSeconds: 60
    valkey:
      host: valkey
      port: 6379
```

**The ip bucket has no trusted-proxy support today.** The ip key is taken directly from `HttpServletRequest.getRemoteAddr()` — there is no `X-Forwarded-For` / `Forwarded` header handling and no `ForwardedHeaderFilter` configured. Behind any reverse proxy or load balancer, `getRemoteAddr()` returns the proxy's address for every request, so all clients behind that proxy share one ip-keyed bucket. Because the ip bucket is checked before the user and tenant buckets, one noisy client can exhaust it and cause every other client sharing that proxy to receive `429`s. If you deploy behind a reverse proxy or load balancer, be aware that ip-based rate limiting is effectively shared across all clients until this gets trusted-proxy support.

When a request is rate-limited, Aperture returns `429 Too Many Requests` with headers:

```
X-RateLimit-Limit: 50
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1750000000
```

The `RateLimitProvider` SPI allows swapping the implementation. The reference implementation keeps state in memory — suitable for single-instance deployments. The demo uses a Valkey-backed provider that loads a small Lua function library once and then executes `FCALL` on each request. For distributed/multi-instance deployments, implement `RateLimitProvider` backed by a shared store. Valkey is a good Redis-compatible option; to keep request overhead low, prefer no persistence or RDB-only snapshots instead of AOF.

**Fail-open on backend errors:** `RateLimitFilter` runs ahead of every request in the app (`HIGHEST_PRECEDENCE + 20`). If the configured `RateLimitProvider` throws — for example a dropped Valkey connection — the filter does not propagate the exception. It logs a single WARN and allows the request through, as if the limit were not exceeded. This is deliberate: letting the exception escape would turn a transient backend outage into a 500 for the entire application. Real limit breaches (the provider returning normally with `allowed=false`) are unaffected and still produce a 429. Each fail-open increments the `aperture.ratelimit.failopen` counter (tagged `type=ip|user|tenant`), so a sustained backend outage that disables rate limiting is alertable. Rejections are likewise counted on `aperture.ratelimit.rejections` (same `type` tag) by both the in-memory and Valkey-backed providers.

## The audit trail

Every `CREATE`, `UPDATE`, and `DELETE` operation is written to an audit log. The `JdbcAuditWriter` implementation uses a background queue and batch inserts for low write amplification:

```sql
-- aperture_audit_log schema
id         UUID     PRIMARY KEY
user_id    TEXT     NOT NULL
tenant_id  TEXT
entity     TEXT     NOT NULL
entity_id  TEXT     NOT NULL
operation  TEXT     NOT NULL     -- CREATE, UPDATE, DELETE
timestamp  TIMESTAMPTZ NOT NULL
details    JSONB                 -- changed field path plus before/after values
```

Each `AuditEvent` carries: `userId`, `tenantId`, `entity` (entity type name), `entityId`, `operation`, and `detailsJson`. For Elide change events, `detailsJson` is valid JSON with the changed field path and before/after values:

```json
{
  "fieldPath": "status",
  "before": "draft",
  "after": "approved"
}
```

Update details are captured from field-level Elide change events. Create and delete events can have empty details or `null` before/after values depending on what Elide reports for the lifecycle event.

**Write guarantee:** `JdbcAuditWriter` uses an in-memory queue with a capacity of 10,000 events processed by a single background thread. Events are dispatched after the Elide transaction commits — they are not in the same transaction, so audit write failures do not roll back the operation. If you need transactional audit guarantees (fail the mutation if audit fails), implement `AuditWriter` with a transactional outbox pattern.

**Querying audit logs:** `GET /manage/audit` (requires `SuperAdmin` or same-tenant `TenantAdmin`). Supports filtering by `tenantId`, `entity`, `entityId`, `userId`, `from`, and `to`. `from` and `to` are ISO timestamp values compared against the audit event timestamp. Tenant admins are always scoped to their own tenant, even if they pass a different `tenantId`.

**Encrypted fields are not encrypted in the audit trail:** `AuditBridge.buildDetailsJson` builds `details` from Elide's `ChangeSpec.getOriginal()`/`getModified()` values. These are the in-memory Java entity-attribute values that Elide's change tracking captures before Hibernate's JPA `AttributeConverter` runs the field's `EncryptionService` conversion at the JDBC boundary. In other words, `ChangeSpec` is built from the plaintext attribute, and encryption is applied afterward, only when Hibernate writes the column. The result: a field marked `encrypted: true` in the manifest still appears in **plaintext** in the `aperture_audit_log.details` column, and in plaintext in whatever any custom `AuditWriter` forwards downstream (for example, `WebhookAuditWriter` posting batches to an external SIEM). Encrypted-at-rest, described above, does not mean encrypted-in-the-audit-trail. If you need redaction of sensitive fields before they leave the process, that is currently the responsibility of the implementing `AuditWriter` — Aperture does not redact `details` for you. `demos/aperture-audit-demo` is a worked example of a composite `AuditWriter` and is a reasonable place to add redaction if you need it.

**Custom audit destinations:** implement the `AuditWriter` SPI to route audit events to Kafka, S3, a SIEM, or any other destination. The `demos/aperture-audit-demo` project shows a composite writer that keeps JDBC audit queries available while also POSTing audit batches to a WireMock SIEM-style HTTP sink.

```java
public interface AuditWriter {
    void write(AuditEvent event);
}
```

### aperture-audit-webhook

`aperture-audit-webhook` is a reusable `AuditWriter` implementation that batches audit events and POSTs them to an HTTP endpoint — the module used by `demos/aperture-audit-demo` to ship events to a SIEM-style sink.

Maven coordinates:

```xml
<dependency>
  <groupId>com.itsjool</groupId>
  <artifactId>aperture-audit-webhook</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`WebhookAuditWriter` takes a required `URI endpoint` and three optional tuning parameters, defaulting to:

| Parameter | Default | Description |
|---|---|---|
| `batchSize` | `100` | Events are flushed once a batch reaches this size |
| `flushInterval` | `Duration.ofSeconds(1)` | Events are also flushed on this interval, whichever comes first |
| `queueCapacity` | `10_000` | In-memory queue bound; once full, `write(event)` drops the event and logs a WARN rather than blocking |

Wire it as a Spring bean:

```java
@Configuration
class AuditWebhookConfig {
    @Bean
    WebhookAuditWriter webhookAuditWriter(@Value("${aperture.audit.webhook.url}") URI url) {
        return new WebhookAuditWriter(url); // batchSize=100, flushInterval=1s, queueCapacity=10000
    }
}
```

To combine it with the JDBC writer instead of replacing it (so a webhook outage doesn't lose the queryable audit trail), fan out from a composite `AuditWriter` bean — see `demos/aperture-audit-demo`'s `AuditDemoAuditConfiguration` for the full pattern, including per-sink failure isolation.
