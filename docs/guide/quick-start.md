---
title: Quick Start
description: Run the billing demo and make your first JSON:API call in under five minutes.
---

# Quick Start

The billing demo is a full-featured multi-tenant billing API built with Aperture. It runs entirely in Docker and exercises every major feature: JWT auth, multi-tenancy, RBAC, lifecycle hooks, and the complete JSON:API protocol.

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and Docker Compose v2

You do not need Java or Maven to run the demo because everything builds inside Docker.

## Start the demo

```bash
git clone https://github.com/ItsJooL/aperture.git
cd aperture/demos/aperture-demo
docker compose up -d
```

This starts six services:

| Service | Port | Purpose |
|---|---|---|
| `postgres` | 5432 | PostgreSQL data store |
| `api-server` | 8080 | The Aperture-generated JSON:API server |
| `hook-service` | 8081 | Demo webhook handler (validate-invoice, enrich-customer) |
| `ui` | 3780 | Web dashboard at `http://localhost:3780` |
| `seeder` | N/A | Seeds demo tenants, users, and data, then exits |
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

All three invoices belong to the Acme Corp tenant. The TechStart invoices are invisible because database-level isolation places them in a different tenant.

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

The response includes an `included` array with the customer records in one round-trip, with no N+1.

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

# This returns only TechStart's invoices; Acme's are invisible
curl -s http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $TECH_TOKEN" | jq '.meta.pagination.totalRecords'
```

## What you just saw

- **JSON:API:** every response follows the standard: `data` array with `type`/`id`/`attributes`/`relationships`, `meta.pagination`, `included` for compound documents
- **Multi-tenancy:** the `aperture_tenant_id` column is present on every tenant-scoped table; all queries are auto-filtered by the current principal's tenant
- **JWT auth:** the `/auth/login` endpoint is Aperture-generated; the token carries tenant ID and roles as claims
- **Hooks:** the `ValidateInvoice` hook fires on every `POST /invoices`; the hook service URL is declared in `manifests/domain/billing/invoice.yaml`

## Starting your own project

Everything above ran against the pre-built billing demo. To start a project of your own, you're pulling in Aperture as a set of Maven artifacts. Every Aperture artifact shares the groupId `com.itsjool.aperture` (not just `com.itsjool`) — double-check that when copying coordinates from elsewhere.

### 1. Add the build-time plugin

`aperture-maven-plugin` reads your manifests and generates code on every build. Add it to a fresh project's `pom.xml`:

```xml
<plugin>
  <groupId>com.itsjool.aperture</groupId>
  <artifactId>aperture-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
</plugin>
```

See [Build & Deploy](/guide/build-deploy#the-maven-plugin) for the full configuration reference (manifest/output directories, lock files, changeset generation).

### 2. Add the runtime dependency

`aperture-simple-starter` is the batteries-included reference implementation: JWT auth, in-memory rate limiting, AES-256-GCM field encryption, and JDBC audit, wired together. It's what the billing demo uses, and the fastest path to a running server:

```xml
<dependency>
  <groupId>com.itsjool.aperture</groupId>
  <artifactId>aperture-simple-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

This one dependency transitively pulls in `aperture-core-runtime`, `aperture-provider-spi`, `aperture-simple-auth`, `aperture-simple-audit`, `aperture-simple-encryption`, and `aperture-simple-ratelimit` — you don't need to declare those separately.

### 3. Add optional pieces as you need them

Everything else is opt-in. Add these only for the features you're using:

| Artifact | When you need it |
|---|---|
| `aperture-simple-mcp` | Generating MCP tool stubs from your manifests |
| `aperture-simple-cli` | The [generated CLI](/guide/cli), using the built-in username/password auth flow |
| `aperture-cli-auth-oidc` | The generated CLI with OIDC device-code login instead |
| `aperture-keycloak-auth` | Swapping the default JWT auth provider for Keycloak (see [Keycloak Integration](/examples/keycloak)) |
| `aperture-audit-webhook` | Shipping audit events to a webhook instead of JDBC |

Each follows the same pattern — `com.itsjool.aperture:<artifact>:0.1.0` as either a `<dependency>` (runtime pieces) or under the plugin's own `<dependencies>` (CLI extensions, since those run at build time — see [Custom auth extensions](/guide/cli#custom-auth-extensions)).

**Bring your own stack:** skip `aperture-simple-*` entirely and depend on just `aperture-core-engine` and `aperture-core-runtime`, implementing the `CredentialValidator`, `PrincipalMapper`, `AuditWriter`, and `RateLimitProvider` SPIs yourself.

## Next steps

- **[Core Concepts](/guide/core-concepts):** how manifests, the build pipeline, and lock files work
- **[Auth & Identity](/guide/auth):** service accounts, API keys, and swapping the auth provider
- **[Billing Demo walkthrough](/examples/billing-demo):** full exploration of every feature
