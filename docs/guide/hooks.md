---
title: Hooks & Lifecycle
description: Guard, validate, mutate, and react asynchronously with four hook types designed for distinct jobs.
---

# Hooks & Lifecycle

Hooks are HTTP callbacks you register on an entity. You build a small web service; Aperture calls it at the right moment and handles signing and timeouts. Declare intent in the manifest, and Aperture generates the Elide lifecycle wiring without requiring framework modifications.

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

Guard hooks may declare `retries` (default `0`, capped at `2`). See [Retries And Timeouts](#retries-and-timeouts) below for the latency this adds to every guarded request.

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

Validate hooks may declare `retries` (default `0`, capped at `2`). See [Retries And Timeouts](#retries-and-timeouts) below for the latency this adds to every validated request.

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

**Mutate hooks do not support `retries`.** The response is applied directly to the entity before it is
persisted, and there is no per-invocation identifier in the request today for your service to recognize
"this is a retry of the same attempt" and dedupe accordingly. A retried enrichment call can therefore both
re-trigger an external side effect (e.g. create a second record in a downstream CRM) *and* persist whichever
duplicate's response happens to come back last. This failure mode is materially worse than guard/validate (no
side effects by contract) or trigger (fire-and-forget, nothing fed back into the row). Declaring `retries`
on a `mutate` hook is a manifest validation error. If your enrichment call needs resilience against
transient failures, build retry logic into the enrichment service itself, where you control idempotency.

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

Trigger hooks may declare `retries` (default `0`, capped at `5`). Since they run asynchronously and never
block the response, this is the hook type where retrying costs the least. See
[Retries And Timeouts](#retries-and-timeouts) below.

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
| `retries` | integer, `0`–`5` | Optional, default `0` (opt-in; see [Retries And Timeouts](#retries-and-timeouts)). Not supported on `mutate` |

Defaults:

| Type | Default operations | Failure behavior | Max `retries` |
|---|---|---|---|
| `guard` | `create`, `update`, `delete` | `reject` | `2` |
| `validate` | `create`, `update` | `reject` | `2` |
| `mutate` | `create`, `update` | `reject` or `passthrough` | not supported |
| `trigger` | `create`, `update`, `delete` | `warn` | `5` |

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

`retries` is an optional, per-hook, opt-in field. It defaults to `0`, so every manifest written before
this field existed keeps behaving exactly as before, with no retries. Set it when a hook call failing
transiently (a brief blip in the hook service, not a real business rejection) is worth retrying rather
than immediately falling back to `onFailure`.

Guard, validate, and trigger hooks all retry through the same mechanism and backoff formula. **Mutate does
not support retries at all**. See the caveat under [Mutation Hooks](#mutation-hooks) above.

**Backoff formula**: delay before retry attempt *n* (1-indexed) is `500ms × 2^(n-1)`: 500 ms, 1 000 ms,
2 000 ms, 4 000 ms, doubling each time. A hook with `retries: N` makes at most `N + 1` total attempts
(the original call plus up to `N` retries).

**The cap is a safety ceiling, not a suggestion.** Retries are opt-in and off by default; most hooks should
never need them. Where you do configure `retries`, the maximum allowed differs by hook type because guard,
validate, and mutate are **synchronous**. Every retry attempt happens *while the API request is still
open*, adding directly to that request's latency. Trigger is asynchronous and already returns the
response before its (possibly-retried) call even starts:

| Type | Max `retries` | Blocks the response? |
|---|---|---|
| `guard` | `2` | Yes |
| `validate` | `2` | Yes |
| `trigger` | `5` | No; it runs after the response is already sent |

**Each retry attempt gets its own full timeout, not a shared budget.** `HookExecutor` does not track an
overall deadline across the attempt sequence. Every attempt (including retries) opens with the complete
configured timeout for that call, and nothing bounds the *total* wall-clock time except the number of
attempts you configure. Concretely, with the default timeouts below and each hook type's own cap, the worst
case added latency to a single request, when the hook call always fails or is always slow rather than failing
fast, works out to:

| Type | Cap | Attempts | Per-attempt timeout | Backoff total | Worst-case added latency |
|---|---|---|---|---|---|
| `validate` | 2 | 3 | `commit` (5s default) | 1.5s | **~16.5s** |
| `guard` | 2 | 3 | `async` (30s default; see quirk below) | 1.5s | **~91.5s** |
| `trigger` | 5 | 6 | `commit` (5s default) | 15.5s | ~45.5s, but never blocks the client |

`guard`'s worst case is much larger than `validate`'s at the *same* cap because of a pre-existing,
already-documented quirk unrelated to retries: `guard` runs at `PRESECURITY`, which the timeout dispatch
in `HookExecutor` does not treat as a "commit phase" (only `PRECOMMIT`/`POSTCOMMIT` are), so it falls
through to the `async` timeout even though it is fully synchronous. If you configure `retries` on a guard
hook, budget for the `async` timeout per attempt, not `commit`. Plan 032 did not change this behavior;
it is called out here because it directly multiplies the retry latency math above.

If a hook author sets `retries: 2` on a synchronous `guard` or `validate` hook, a hook service that is
down or consistently slow can hold that specific API request open for on the order of a minute or more
before Aperture finally applies `onFailure`. Set `retries` only for hooks where the underlying failure is
plausibly transient, and prefer the lowest value that covers your real failure pattern. `retries: 1` is
enough to ride out a single dropped connection or restart blip, and costs far less latency than the cap.

Timeouts are configured by runtime category:

```yaml
aperture:
  hooks:
    timeout:
      commit: 5s
      async: 30s
      connect: 2s
```

Timeout category follows how `HookExecutor` dispatches the call, not the hook type directly:

- `commit` applies to the phase-gated calls that run through `executeHook` during `PRECOMMIT`/`POSTCOMMIT`: `validate` (PRECOMMIT) and `trigger` (POSTCOMMIT).
- `async` applies to `guard` calls (PRESECURITY, which is phase-gated but neither `PRECOMMIT` nor `POSTCOMMIT`). It also applies to every `mutate` call: the synchronous enrichment request (`executeHookWithResponse`) always uses the hardcoded `async` timeout, regardless of phase.

## Hook Base URL Override

For local development or Docker environments where internal service hostnames differ from what's in the manifest:

```yaml
aperture:
  hooks:
    base-url: http://localhost:8081
```

This rewrites the scheme and host of every hook URL, leaving the path unchanged.
