---
title: Billing Demo
description: A full-featured multi-tenant billing API — POOL mode, all Aperture features enabled.
---

# Billing Demo

The billing demo is the reference Aperture deployment. It exercises every major feature: POOL multi-tenancy, JWT auth with two domain roles, RBAC and two ABAC policies, four hook types across five hook instances, field encryption, optimistic locking on customers, an async trigger for audit events, and the MCP server.

## Running the demo

```bash
git clone https://github.com/ItsJooL/aperture.git
cd aperture/demos/aperture-demo
docker compose up -d
```

Wait ~60 seconds for all services to become healthy:

```bash
docker compose ps    # all should show "healthy" or "exited(0)" for seeder
```

## Domain model

Eight entities in two domain areas:

### Billing domain

| Entity | Tenant-scoped | Key features |
|---|---|---|
| `Invoice` | Yes | PRECOMMIT validation hook, two ABAC policies |
| `LineItem` | Yes | PRESECURITY guard hook |
| `Payment` | Yes | PRECOMMIT validation hook |
| `Product` | No | Shared reference data |
| `Country` | No | Shared reference data |
| `Currency` | No | Shared reference data |

### Identity domain

| Entity | Tenant-scoped | Key features |
|---|---|---|
| `Customer` | Yes | `encrypted: true` on email, optimistic locking, PREENRICH hook |
| `Supplier` | Yes | POSTCOMMIT async trigger |

## Seeded data

The `seeder` service creates two tenants at startup:

**Acme Corp** (`admin@acme.com` / `AcmeAdmin123!`)
- `admin@acme.com` — `TenantAdmin`
- `accountant@acme.com` — `Accountant` with `department=finance`, `region=eu`, `status=active`
- `viewer@acme.com` — `Viewer`
- 3 customers, 3 invoices (PAID / ISSUED / DRAFT), 3 products

**TechStart Inc** (`admin@techstart.com` / `TechAdmin123!`)
- `admin@techstart.com` — `TenantAdmin`
- `dev@techstart.com` — `Accountant`
- 2 customers, 2 invoices, 2 products

## Feature walkthroughs

### 1. JWT auth and tenant isolation

Log in as Acme's accountant:

```bash
export TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"accountant@acme.com","password":"Accountant123!"}' | jq -r .accessToken)
```

Acme sees 3 invoices:

```bash
curl -s http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $TOKEN" | jq '.meta.pagination.totalRecords'
# 3
```

Log in as TechStart:

```bash
export TECH_TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dev@techstart.com","password":"DevPass123!"}' | jq -r .accessToken)

curl -s http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $TECH_TOKEN" | jq '.meta.pagination.totalRecords'
# 2  — TechStart's invoices only
```

### 2. RBAC — Viewer cannot create

```bash
export VIEWER_TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"viewer@acme.com","password":"Viewer123!"}' | jq -r .accessToken)

curl -s -X POST http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $VIEWER_TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -d '{"data":{"type":"invoice","attributes":{"amount":100,"status":"DRAFT"},"relationships":{"customer":{"data":{"type":"customer","id":"1"}}}}}' | jq '.errors[0].status'
# "403"
```

### 3. ABAC — FinanceTeamOnly policy

The `Accountant` role grants access to invoices, but the `FinanceTeamOnly` policy additionally requires `department=finance`.

The seeded `accountant@acme.com` has `department=finance` — they can read invoices. A user with `Accountant` role but `department=marketing` would be denied by the policy even though the role grants access.

### 4. The ValidateInvoice hook

Creating an invoice fires the PRECOMMIT `ValidateInvoice` hook synchronously. The hook service is a small HTTP service bundled in the demo:

```bash
curl -s -X POST http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -d '{
    "data": {
      "type": "invoice",
      "attributes": { "amount": 750.00, "status": "DRAFT" },
      "relationships": {
        "customer": { "data": { "type": "customer", "id": "1" } }
      }
    }
  }' | jq .
```

The hook service logs the request and returns 200, so the invoice is created. To see the hook in action, check the hook-service logs:

```bash
docker compose logs hook-service --tail=10
```

### 5. PRESECURITY guard (LineItem)

`LineItem` has a `CheckLineItem` hook with `phase: PRESECURITY`. This runs before auth checks — it can block the request before Aperture even evaluates permissions:

```bash
curl -s http://localhost:8080/api/v1/lineItems \
  -H "Authorization: Bearer $TOKEN" | jq .
```

The hook service allows the request in the demo. Change the hook service response to a non-2xx to see a 403 before the JWT is checked.

### 6. Optimistic locking (Customer)

`Customer` has `optimisticLocking: true`. Every response includes an `ETag`:

```bash
curl -sI http://localhost:8080/api/v1/customers/1 \
  -H "Authorization: Bearer $TOKEN" | grep -i etag
# ETag: "0"
```

Update without `If-Match` → 428:

```bash
curl -s -X PATCH http://localhost:8080/api/v1/customers/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -d '{"data":{"type":"customer","id":"1","attributes":{"name":"Updated"}}}' | jq '.errors[0].status'
# "428"
```

Update with correct ETag → 200:

```bash
curl -s -X PATCH http://localhost:8080/api/v1/customers/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "If-Match: \"0\"" \
  -H "Content-Type: application/vnd.api+json" \
  -d '{"data":{"type":"customer","id":"1","attributes":{"name":"Updated"}}}' | jq .
```

### 7. Field encryption (Customer email)

The `email` field on `Customer` is `encrypted: true`. The API returns the plaintext value to authorised callers — decryption happens in the JVM. But if you query the database directly:

```bash
docker compose exec postgres psql -U aperture -c "SELECT email FROM aperture_customers LIMIT 1;"
```

You'll see the ciphertext (Base64-encoded AES-256-GCM), not the email address.

### 8. MCP server

With the MCP server enabled, AI assistants can access all entities through the Model Context Protocol:

```bash
curl -s http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' | jq '.result.tools[].name'
# "list_invoices", "get_invoice", "create_invoice", "update_invoice", "delete_invoice"
# ... one set per entity
```

The MCP tools respect the same auth, tenancy, and RBAC rules as the REST API.

### 9. Distributed tracing

The demo ships with Jaeger. Browse to `http://localhost:16686` to see traces for all requests, including hook calls.

## Diagram — entity relationships

```
Country (global) ←─── Customer (tenant-scoped, encrypted email, optimistic lock)
Currency (global)         │
                          │
Product (global) ←─── LineItem ───→ Invoice ←─── Payment
                                        │
                                    ValidateInvoice (PRECOMMIT hook)
                                    FinanceTeamOnly (ABAC policy)
                                    EuRegionOnly (ABAC policy)
```

Tenant-scoped FK constraints: `Invoice.customer_id` references `(aperture_tenant_id, id)` on `Customer`. A customer from one tenant cannot be referenced on another tenant's invoice.
