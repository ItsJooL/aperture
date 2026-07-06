---
title: Quick Start
description: Run the billing demo and make your first JSON:API call in under five minutes.
---

# Quick Start

The billing demo is a full-featured multi-tenant billing API built with Aperture. It runs entirely in Docker and exercises every major feature: JWT auth, multi-tenancy, RBAC, lifecycle hooks, and the complete JSON:API protocol.

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and Docker Compose v2

No Java or Maven needed to run the demo — everything builds inside Docker.

## Start the demo

```bash
git clone https://github.com/ItsJooL/aperture.git
cd aperture/demos/aperture-demo
docker compose up -d
```

This starts six services:

| Service | Port | Purpose |
|---|---|---|
| `postgres` | 5432 | PostgreSQL — data store |
| `api-server` | 8080 | The Aperture-generated JSON:API server |
| `hook-service` | 8081 | Demo webhook handler (validate-invoice, enrich-customer) |
| `ui` | 3780 | Web dashboard — browse to `http://localhost:3780` |
| `seeder` | — | Seeds demo tenants, users, and data, then exits |
| `jaeger` | 16686 | Distributed tracing UI (optional) |

The API server takes about 60 seconds to start on first run (Liquibase applies schema migrations). Wait until the health check passes:

```bash
docker compose ps   # api-server should show "healthy"
```

## Log in

The seeder creates two tenants. Log in as `admin@acme.com` (the Acme Corp tenant admin):

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin@acme.com","password":"AcmeAdmin123!"}' | jq .
```

Response:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

Export the token:

```bash
export TOKEN=eyJhbGciOiJIUzI1NiJ9...
```

## List invoices

```bash
curl -s http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Response shape (JSON:API):

```json
{
  "data": [
    {
      "type": "invoice",
      "id": "1",
      "attributes": {
        "amount": 4999.00,
        "status": "PAID"
      },
      "relationships": {
        "customer": {
          "data": { "type": "customer", "id": "1" }
        }
      }
    }
  ],
  "meta": {
    "pagination": {
      "number": 1,
      "size": 20,
      "totalPages": 1,
      "totalRecords": 3
    }
  }
}
```

All three invoices belong to the Acme Corp tenant. The TechStart invoices are invisible — they're in a different tenant, isolated at the database level.

## Filter, sort, and paginate

JSON:API filtering is RSQL syntax via the `filter` query parameter:

```bash
# Invoices with status = PAID
curl -s "http://localhost:8080/api/v1/invoices?filter=status==PAID" \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].attributes.status'

# Invoices over $1000, sorted by amount descending
curl -s "http://localhost:8080/api/v1/invoices?filter=amount=gt=1000&sort=-amount" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

## Include related resources

Fetch invoices with their customers in a single request:

```bash
curl -s "http://localhost:8080/api/v1/invoices?include=customer" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

The response includes a `included` array with the customer records — one round-trip, no N+1.

## Create an invoice

```bash
curl -s -X POST http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -d '{
    "data": {
      "type": "invoice",
      "attributes": {
        "amount": 750.00,
        "status": "DRAFT"
      },
      "relationships": {
        "customer": {
          "data": { "type": "customer", "id": "1" }
        }
      }
    }
  }' | jq .
```

The `ValidateInvoice` hook fires synchronously before the record is committed. If the hook service rejects the request, the API returns an error and nothing is written to the database.

## Test tenant isolation

Log in as the TechStart admin and try to access Acme's invoices:

```bash
export TECH_TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin@techstart.com","password":"TechAdmin123!"}' | jq -r .accessToken)

# This returns only TechStart's invoices — Acme's are invisible
curl -s http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $TECH_TOKEN" | jq '.meta.pagination.totalRecords'
```

## What you just saw

- **JSON:API** — every response follows the standard: `data` array with `type`/`id`/`attributes`/`relationships`, `meta.pagination`, `included` for compound documents
- **Multi-tenancy** — the `aperture_tenant_id` column is present on every tenant-scoped table; all queries are auto-filtered by the current principal's tenant
- **JWT auth** — the `/auth/login` endpoint is Aperture-generated; the token carries tenant ID and roles as claims
- **Hooks** — the `ValidateInvoice` hook fires on every `POST /invoices`; the hook service URL is declared in `manifests/domain/billing/invoice.yaml`

## Next steps

- **[Core Concepts](/guide/core-concepts)** — how manifests, the build pipeline, and lock files work
- **[Auth & Identity](/guide/auth)** — service accounts, API keys, and swapping the auth provider
- **[Billing Demo walkthrough](/examples/billing-demo)** — full exploration of every feature
