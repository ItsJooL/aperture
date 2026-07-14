# Aperture Audit Demo

This demo shows a custom `AuditWriter` routing audit events to an external SIEM-style HTTP sink while the built-in JDBC audit log keeps `/manage/audit` working.

## Run

```bash
mvn -pl demos/aperture-audit-demo -am package -DskipTests
docker compose up -d
./audit-smoke-test.sh
docker compose down -v
```

The stack uses distinct host ports so it can run beside the main demo:

| Service | URL |
|---|---|
| API | http://localhost:8082 |
| WireMock SIEM sink | http://localhost:8282 |
| Postgres | localhost:5433 |

## What It Proves

`AuditDemoAuditConfiguration` defines a primary composite `AuditWriter`. It delegates each event to:

- `JdbcAuditWriter`, preserving `/manage/audit`
- `WebhookAuditWriter`, POSTing JSON batches to `POST /siem/audit`

WireMock stands in for the SIEM. The smoke script creates a product, then PATCHes both a plain field (`name`) and the encrypted `supplier_secret` field in one request, and asserts that both the JDBC audit endpoint and WireMock's request journal contain: the `name` UPDATE with its real `fieldPath`/`before`/`after` values, and a separate `supplier_secret` UPDATE with `before`/`after` both redacted to the literal string `"[REDACTED]"`.

The product manifest declares `supplier_secret: encrypted: true`, so this demo also proves Aperture's default audit-trail redaction end-to-end: `CodeGenerator` emits a runtime `@Encrypted` marker on that field, and `AuditBridge` looks it up per change to substitute the sentinel instead of the plaintext value — on by default, configurable via `aperture.audit.redaction.*` (a global `enabled` switch and a per-entity/per-field `exemptions` allowlist). See `docs/guide/security-audit.md`'s "The audit trail" section for the full config shape.

## Bruno

The `api-collection/` folder mirrors the smoke flow:

- `01-auth`: log in as the bootstrap superadmin
- `02-mutations`: create and update a product
- `03-audit-query`: query `/manage/audit`
- `04-siem-sink`: inspect WireMock's captured SIEM requests
