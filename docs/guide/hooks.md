---
title: Hooks & Lifecycle
description: Guard, validate, mutate, and react asynchronously — the four kinds of hook and what each one helps you build.
---

# Hooks & Lifecycle

Hooks are HTTP callbacks you register on an entity. You build a small web service; Aperture calls it at the right moment and handles signing, retries, and timeouts. Nothing in the framework to modify — just declare a URL in the manifest.

There are four kinds of hook, each serving a different purpose:

| Kind | When it runs | Can modify data | Blocks the response |
|---|---|---|---|
| [Guard](#guard-hooks)      | Before auth is checked | No  | Yes |
| [Validate](#validation-hooks) | After auth, before write | No  | Yes |
| [Mutate](#mutation-hooks)  | After auth, before write | **Yes** | Yes |
| [Trigger](#trigger-hooks)  | After the write commits  | No  | No  |

---

## Guard hooks

Think of a guard hook as a bouncer at the door. Aperture calls your endpoint **before it even checks whether the request carries a valid token**. If you return anything other than a 2xx, the request is rejected immediately — the caller never reaches auth or permission checks.

Guard hooks are for decisions that apply across the board, not to individual users or records:

- Is this IP address on an allowlist?
- Has this API consumer exceeded a quota or burst limit?
- Is the system in a maintenance window?
- Is this request coming from an expected geographic region?

```yaml
hooks:
  IPAllowList:
    phase: PRESECURITY
    async: false
    onFailure: reject
    url: http://guard-service:8080/hooks/ip-allowlist
```

Because guard hooks run before auth, they receive the raw HTTP request but do not receive authenticated entity data. The request body Aperture sends is the same format as all other hooks (the entity fields), but auth claims are not yet populated.

---

## Validation hooks

Validation hooks run **after authentication and permission checks have passed, but before anything is written to the database**. They can see the full entity being created or modified, but they cannot change it — they can only allow or reject.

Use validation hooks for business rules:

- Does this invoice total match the sum of its line items?
- Is this customer's account in a state that allows new orders?
- Is the end date after the start date?
- Does this discount code exist and has it not been used?
- Is this booking slot still available?

```yaml
hooks:
  ValidateInvoice:
    phase: PRECOMMIT
    async: false
    onFailure: reject
    url: http://rules-service:8080/hooks/validate-invoice
```

If your endpoint returns a non-2xx, Aperture rejects the operation and returns a 400 to the caller. Nothing is written. Your endpoint can return a message body to explain the rejection — Aperture forwards it in the error response.

---

## Mutation hooks

Mutation hooks run at the same point as validation hooks — **after auth, before the write** — but with one key difference: your endpoint can return data and Aperture will merge it back into the entity before writing.

Aperture sends the entity to your endpoint. Whatever you return in `data.attributes` is applied on top of the entity fields before the database write. Use mutation hooks for enrichment and transformation:

- Look up a customer's internal ID from your CRM and stamp it on the record
- Normalise a phone number to E.164 format
- Geocode an address and store the coordinates alongside it
- Compute a derived field (tax amount, discounted price, reading level)
- Set metadata that the client shouldn't control (audit code, internal classification)

```yaml
hooks:
  EnrichCustomer:
    phase: PREENRICH
    async: false
    onFailure: reject
    url: http://enrichment-service:8080/hooks/enrich-customer
```

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

Only the fields you return are changed. Fields you omit keep their original values. Protected fields (`id`, `apertureTenantId`, `version`, `deletedAt`) are ignored even if you return them.

If you return an empty body or `null`, the entity is unchanged — you can use a mutation hook purely for validation and leave modification as optional.

---

## Trigger hooks

Trigger hooks fire **after the database transaction has successfully committed**. Aperture sends the request and moves on — it does not wait for your endpoint to respond, and the user already has their success response.

Use trigger hooks for side effects that don't belong in the critical path:

- Send a welcome email when a new customer is created
- Publish an event to a message queue or event bus
- Notify a downstream service of a state change
- Write a record to a data warehouse or analytics platform
- Kick off a background workflow (document generation, provisioning, etc.)

```yaml
hooks:
  CustomerCreated:
    phase: POSTCOMMIT
    async: true
    onFailure: warn
    url: http://events-service:8080/hooks/customer-created
```

Because trigger hooks are fire-and-forget, the `onFailure` setting only controls whether a failure is logged as a warning (`warn`) or silently ignored (`passthrough`). Failures never affect the user-facing response.

---

## Declaring hooks in the manifest

All hooks share the same YAML shape under `spec.hooks`:

```yaml
hooks:
  HookName:
    phase: PRECOMMIT        # PRESECURITY | PREENRICH | PRECOMMIT | POSTCOMMIT
    async: false            # true = fire-and-forget after commit
    onFailure: reject       # reject | warn | passthrough
    url: http://my-service:8080/hooks/my-hook
```

Hook names must be unique per entity. Multiple hooks can share the same phase — they are called in declaration order.

| Field | Values | Description |
|---|---|---|
| `phase` | `PRESECURITY`, `PREENRICH`, `PRECOMMIT`, `POSTCOMMIT` | Maps to: guard, mutate, validate, trigger |
| `async` | `true` / `false` | `true` means fire-and-forget (only meaningful on `POSTCOMMIT`) |
| `onFailure` | `reject`, `warn`, `passthrough` | What to do when the endpoint is unreachable or returns non-2xx |
| `url` | HTTP/HTTPS URL | Where Aperture sends the `POST` request |

---

## Request shape

Every hook receives a `POST` request containing the entity's current field values:

```
POST http://my-service:8080/hooks/my-hook
Content-Type: application/json
X-Hook-Secret: <configured-secret>

{
  "id": "3a8bc...",
  "apertureTenantId": "7f1d...",
  "amount": 4999.00,
  "status": "DRAFT",
  "customer_id": "1a2b..."
}
```

`ManyToOne` relationships are sent as `{fieldName}_id` (the FK value). `OneToMany` and `ManyToMany` relationships are excluded.

## Hook signing — `X-Hook-Secret`

Every request includes an `X-Hook-Secret` header with the value from `aperture.hooks.secret`. Verify this header in your endpoint before processing — it ensures requests genuinely come from Aperture and not an outside caller.

```yaml
aperture:
  hooks:
    secret: ${APERTURE_HOOK_SECRET}
```

## Retries and timeouts

Aperture retries failed hook calls with exponential backoff:

| Attempt | Delay |
|---|---|
| 1st retry | 500 ms |
| 2nd retry | 1 000 ms |
| 3rd retry | 2 000 ms |

Timeouts are configurable per hook category:

```yaml
aperture:
  hooks:
    timeout:
      commit: 5s     # validate hooks (PRECOMMIT)
      async: 10s     # guard (PRESECURITY), mutate (PREENRICH), and trigger (POSTCOMMIT) hooks
      connect: 2s    # TCP connect timeout
```

## Hook base URL override

For local development or Docker environments where internal service hostnames differ from what's in the manifest:

```yaml
aperture:
  hooks:
    base-url: http://localhost:8081
```

This rewrites the scheme and host of every hook URL, leaving the path unchanged.
