# Observability

Aperture provides standard Micrometer metrics and distributed tracing (OpenTelemetry) out of the box. Because Aperture is built on Spring Boot, you can configure telemetry via standard Spring Boot properties.

## Metrics

Aperture exposes metrics for internal components, and also leverages Spring Boot's built-in web and database metrics.

### Key Metrics

- `aperture.audit.write`: Timer and counter for audit log background writes (see [Audit Log Spans](#audit-log-spans) below for its tags).
- `aperture.audit.queue.size`: Gauge tracking the current size of the async audit event queue.
- `aperture.audit.dropped`: Counter of audit events dropped when the queue overflows.
- `aperture.ratelimit.rejections`: Counter of requests rejected by the rate limiter, tagged by `type` (e.g. `ip`).
- `aperture.hook`: Timer and counter for outbound hook dispatch calls (see [Hook Dispatch Spans](#hook-dispatch-spans) below for its tags).

## Distributed Tracing (OpenTelemetry)

Aperture propagates W3C trace context headers across service boundaries, and creates spans for significant operations.

### Server Spans

Incoming API requests are instrumented by Spring's `ObservationFilter` and enriched by Aperture's `ApertureObservationFilter` with the following tags:

Low cardinality (appear on both the `http.server.requests` metric and the request span):

- `aperture.entity`: The name of the API entity being accessed (parsed from a `/api/v{n}/{entity}` URI).
- `aperture.api.version`: The API version (the `v{n}` segment of the URI).
- `aperture.operation`: The HTTP method (GET, POST, etc.).

High cardinality (span-only — not promoted to the metric, since the value is unbounded):

- `aperture.tenant.id`: The ID of the authenticated tenant making the request.

This allows you to slice metrics dashboards by entity/operation and filter individual traces by tenant.

### Hook Dispatch Spans

When Aperture dispatches a webhook, it wraps the outbound HTTP call in an `aperture.hook` Observation. This observation includes the following low-cardinality tags:

- `hook.name`: The name of the hook (from the manifest).
- `hook.phase`: The phase of the hook (e.g., `PRECOMMIT`, `POSTCOMMIT`, `PREENRICH`).
- `hook.async`: Boolean indicating if the hook was executed asynchronously.
- `entity`: The entity triggering the hook.
- `outcome`: Set once the call completes — `ok` (2xx response), `rejected` (non-2xx response), or `error` (the request itself failed, e.g. timeout/connection error).
- `retry.attempt`: Present only on retried async hook calls, set to the current attempt number (the first attempt has no `retry.attempt` tag).

Trace context (like `traceparent`) is propagated to the remote webhook server, linking your hook service traces to the original API request.

### Audit Log Spans

Audit log database writes are performed asynchronously but are correctly linked to the original transaction. An `aperture.audit.write` span is emitted as a child of the original API request span, tagged with:

- `entity`: The entity that was changed.
- `operation`: The operation performed (e.g., `CREATE`, `UPDATE`, `DELETE`).
- `outcome`: `ok` or `error`, set once the batch write completes.

If the async queue is full, the event is dropped instead of blocking the request; see the `aperture.audit.dropped` counter above.

### MCP Tool Spans

When AI agents invoke MCP tools via Aperture, the invocation is wrapped in an `aperture.mcp.tool_call` Observation. This captures the execution of generated MCP tools with:

- `tool.name`: The name of the tool (e.g., `list_invoices`).
- `server.name`: Set to `aperture-mcp`.

## Configuration

To export traces to an OpenTelemetry collector (like Jaeger), add the OTel exporter dependency to your implementation project and set the following properties:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: "http://jaeger:4318/v1/traces"
```
