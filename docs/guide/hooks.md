---
title: Hooks & Lifecycle
description: Guard, validate, mutate, and react asynchronously — the four hook types and what each one helps you build.
---

# Hooks & Lifecycle

Hooks are HTTP callbacks you register on an entity. You build a small web service; Aperture calls it at the right moment and handles signing, retries, and timeouts. Nothing in the framework to modify — declare intent in the manifest and Aperture generates the Elide lifecycle wiring.

There are four hook types:

| Type | When it runs | Can modify data | Blocks the response |
|---|---|---|---|
| [Guard](#guard-hooks) | Before auth is checked | No | Yes |
| [Validate](#validation-hooks) | After auth, before write | No | Yes |
| [Mutate](#mutation-hooks) | After auth, before write | Yes | Yes |
| [Trigger](#trigger-hooks) | After the write commits | No | No |

Hook operations are write lifecycle operations only: `create`, `update`, and `delete`. Reads are handled by permissions, policies, and filters rather than lifecycle hooks.

## Guard Hooks

Guard hooks run before auth and permission checks. If the endpoint returns anything other than a 2xx response, Aperture rejects the request immediately.

Use guard hooks for request-level gates:

- Is this IP address on an allowlist?
- Has this API consumer exceeded a quota or burst limit?
- Is the system in a maintenance window?
- Is this request coming from an expected region?

```yaml
hooks:
  IPAllowList:
    type: guard
    on: [create, update, delete]
    url: http://guard-service:8080/hooks/ip-allowlist
```

Guard hooks always reject on failure. They are synchronous and run before the write can proceed.

## Validation Hooks

Validation hooks run after authentication and permission checks have passed, but before anything is written to the database. They can inspect the entity being created or updated, but they cannot change it.

Use validation hooks for business rules:

- Does this invoice total match the sum of its line items?
- Is this customer's account in a state that allows new orders?
- Is the end date after the start date?
- Does this discount code exist and has it not been used?

```yaml
hooks:
  ValidateInvoice:
    type: validate
    on: [create, update]
    url: http://rules-service:8080/hooks/validate-invoice
```

If the endpoint returns a non-2xx response, Aperture rejects the operation and returns an error to the caller. Nothing is written.

## Mutation Hooks

Mutation hooks run after auth, before the write, and can return data that Aperture merges into the entity before persistence.

Use mutation hooks for enrichment and transformation:

- Look up a customer's internal ID from a CRM
- Normalize a phone number
- Compute a derived field
- Set metadata the client should not control

```yaml
hooks:
  EnrichCustomer:
    type: mutate
    on: [create]
    onFailure: passthrough
    url: http://enrichment-service:8080/hooks/enrich-customer
```

Mutation hooks default to `onFailure: reject`. Use `passthrough` when enrichment is useful but should not block the write.

Your endpoint returns a partial entity:

```json
{
  "data": {
    "attributes": {
      "internalCode": "CUS-0042",
      "phoneNormalized": "+353876543210"
    }
  }
}
```

Only returned fields are changed. Fields you omit keep their original values. Protected fields (`id`, `apertureTenantId`, `version`, `deletedAt`) are ignored.

## Trigger Hooks

Trigger hooks fire after the database transaction has committed. Aperture sends the request asynchronously and does not hold the user-facing response open.

Use trigger hooks for side effects that do not belong in the critical path:

- Send a welcome email
- Publish an event
- Notify a downstream service
- Write analytics records
- Start background provisioning

```yaml
hooks:
  CustomerCreated:
    type: trigger
    on: [create]
    url: http://events-service:8080/hooks/customer-created
```

Trigger hooks always use `onFailure: warn`. Failures are logged and never affect the already-committed user response.

## Declaring Hooks

All hooks live under `spec.hooks`:

```yaml
hooks:
  HookName:
    type: validate
    on: [create, update]
    url: http://my-service:8080/hooks/my-hook
```

Hook names must be unique per entity. Multiple hooks can share the same type and operation set; they are called in declaration order.

| Field | Values | Description |
|---|---|---|
| `type` | `guard`, `validate`, `mutate`, `trigger` | The semantic hook category |
| `on` | `create`, `update`, `delete` | Optional operation list. Each hook type has sensible defaults |
| `onFailure` | `reject`, `passthrough`, `warn` | Optional. Only valid where the hook type allows it |
| `url` | HTTP/HTTPS URL | Endpoint Aperture calls |

Defaults:

| Type | Default operations | Failure behavior |
|---|---|---|
| `guard` | `create`, `update`, `delete` | `reject` |
| `validate` | `create`, `update` | `reject` |
| `mutate` | `create`, `update` | `reject` or `passthrough` |
| `trigger` | `create`, `update`, `delete` | `warn` |

## Request Shape

Every hook receives a `POST` request containing the entity's current field values:

```http
POST http://my-service:8080/hooks/my-hook
Content-Type: application/json
X-Hook-Secret: <configured-secret>
```

```json
{
  "id": "3a8bc...",
  "apertureTenantId": "7f1d...",
  "amount": 4999.00,
  "status": "DRAFT",
  "customer_id": "1a2b..."
}
```

`ManyToOne` relationships are sent as `{fieldName}_id`. `OneToMany` and `ManyToMany` relationships are excluded.

## Hook Signing

Every request includes an `X-Hook-Secret` header with the value from `aperture.hooks.secret`. Verify this header in your endpoint before processing.

```yaml
aperture:
  hooks:
    secret: ${APERTURE_HOOKS_SECRET}
```

## Retries And Timeouts

Aperture retries failed hook calls with exponential backoff:

| Attempt | Delay |
|---|---|
| 1st retry | 500 ms |
| 2nd retry | 1 000 ms |
| 3rd retry | 2 000 ms |

Timeouts are configured by runtime category:

```yaml
aperture:
  hooks:
    timeout:
      commit: 5s
      async: 10s
      connect: 2s
```

`commit` applies to synchronous guard, validate, and mutate calls. `async` applies to trigger calls.

## Hook Base URL Override

For local development or Docker environments where internal service hostnames differ from what's in the manifest:

```yaml
aperture:
  hooks:
    base-url: http://localhost:8081
```

This rewrites the scheme and host of every hook URL, leaving the path unchanged.
