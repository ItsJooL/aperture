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

WireMock stands in for the SIEM. The smoke script creates and updates a product, then asserts that both the JDBC audit endpoint and WireMock's request journal contain the UPDATE audit details with `fieldPath`, `before`, and `after`.

The product manifest includes an encrypted `supplier_secret` field so the demo makes the audit redaction question visible. Today, audit details record the changed field values that Elide reports. If a sensitive field is changed, review whether your `AuditWriter` should redact or transform those values before export.

## Bruno

The `api-collection/` folder mirrors the smoke flow:

- `01-auth`: log in as the bootstrap superadmin
- `02-mutations`: create and update a product
- `03-audit-query`: query `/manage/audit`
- `04-siem-sink`: inspect WireMock's captured SIEM requests
