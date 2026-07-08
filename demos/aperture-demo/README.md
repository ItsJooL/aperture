# Aperture Demo

End-to-end demo of the Aperture API server with two tenants, hook validation, atomic operations, and distributed tracing.

## Quick Start

```bash
# Build and start the full stack (api-server, postgres, hook-service, jaeger, valkey, seeder)
mise run docker-deploy

# Wait for all services to become healthy (~60-90s on cold build)
docker compose ps
```

The seeder runs automatically once the api-server is healthy. It provisions two tenants (`Acme Corp` and `TechStart Inc`) with users, products, customers, and invoices.

## Mise Tasks

Run `mise run` in this directory for the interactive task picker. Common tasks:

```bash
mise run docker-deploy  # build and start API, backing services, seeder, and UI
mise run docker-clear   # stop the stack and remove containers, volumes, and local images
mise run build-api      # Maven package for the demo API
mise run build-cli      # build the generated CLI
mise run build-ui       # build the web UI
mise run test           # Maven tests
mise run test-cli       # CLI smoke test against a live stack
mise run test-ui        # UI tests
```

From the repository root, these same tasks are available as `demos:aperture-demo:*`,
for example `mise run demos:aperture-demo:docker-deploy`.

## Services

| Service      | URL                           | Description                  |
|-------------|-------------------------------|------------------------------|
| api-server  | http://localhost:8080         | JSON:API + framework routes  |
| web-ui      | http://localhost:3780         | Vue billing workspace SPA    |
| hook-service| http://localhost:8081         | Webhook handler              |
| jaeger      | http://localhost:16686        | Distributed trace UI         |
| valkey      | localhost:6379                | Rate-limit backing store     |
| postgres    | localhost:5432                | PostgreSQL 17                |

## Demo Credentials

| Tenant     | Username                | Password          | Role         |
|-----------|-------------------------|-------------------|--------------|
| Acme Corp | admin@acme.com          | AcmeAdmin123!     | TenantAdmin  |
| Acme Corp | accountant@acme.com     | Accountant123!    | Accountant   |
| Acme Corp | viewer@acme.com         | Viewer123!        | Viewer       |
| TechStart | admin@techstart.com     | TechAdmin123!     | TenantAdmin  |
| TechStart | dev@techstart.com       | DevPass123!       | Accountant   |

The web UI has built-in one-click login for these seeded users — you don't need to type
credentials by hand. Open <http://localhost:3780>: the "Demo personas" panel at the top of
the login screen lets you jump straight into any seeded workspace. Once signed in, the same
persona and tenant switcher stays pinned in the top-right of the workspace so you can hop
between roles and tenants without signing out.

## Walkthrough

### 1. Login and get a token

```bash
TOKEN=$(curl -sf -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"accountant@acme.com","password":"Accountant123!"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
```

### 2. List customers

```bash
curl -H "Authorization: Bearer $TOKEN" \
     -H "Accept: application/vnd.api+json" \
     http://localhost:8080/api/v3/customers
```

### 3. Create an invoice

```bash
# Get a customer ID first
CUSTOMER_ID=$(curl -sf -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.api+json" \
  "http://localhost:8080/api/v3/customers?filter=name=='Acme Corp HQ'" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")

curl -X POST http://localhost:8080/api/v3/invoices \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -H "Accept: application/vnd.api+json" \
  -d "{\"data\":{\"type\":\"invoices\",\"attributes\":{\"amount\":500,\"status\":\"DRAFT\"},
       \"relationships\":{\"customer\":{\"data\":{\"type\":\"customers\",\"id\":\"$CUSTOMER_ID\"}}}}}"
```

### 4. Atomic operations — create invoice with line items in one transaction

```bash
curl -X POST http://localhost:8080/api/v3/operations \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/vnd.api+json;ext="https://jsonapi.org/ext/atomic"' \
  -H 'Accept: application/vnd.api+json;ext="https://jsonapi.org/ext/atomic"' \
  -d '{
    "atomic:operations": [
      {
        "op": "add",
        "href": "/api/v3/invoices",
        "data": {"type":"invoices","lid":"inv-1","attributes":{"amount":999,"status":"DRAFT"},
                 "relationships":{"customer":{"data":{"type":"customers","id":"'"$CUSTOMER_ID"'"}}}}
      },
      {
        "op": "add",
        "href": "/api/v3/lineitems",
        "data": {"type":"lineitems","attributes":{"quantity":1,"unit_price":999},
                 "relationships":{"invoice":{"data":{"type":"invoices","lid":"inv-1"}}}}
      }
    ]
  }'
```

### 5. Trigger a hook failure

```bash
# The hook-service rejects invoices with amount <= 0
curl -X POST http://localhost:8080/api/v3/invoices \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -H "Accept: application/vnd.api+json" \
  -d '{"data":{"type":"invoices","attributes":{"amount":-1,"status":"DRAFT"},
       "relationships":{"customer":{"data":{"type":"customers","id":"'"$CUSTOMER_ID"'"}}}}}'
# → 400 with hook rejection message
```

### 6. View distributed traces

Open http://localhost:16686 and select `aperture-demo` service to see traces for the above requests.

The demo is configured to capture **100% of traces** for observability testing (production uses 10% by default). Jaeger shows:

- **API request spans**: Each `/api/v*` call produces a root span with `aperture.entity`, `aperture.operation`, `aperture.api.version`, and `aperture.tenant.id` tags.
- **Hook dispatch spans**: When a webhook is invoked (e.g., `ValidateInvoice`), the outbound HTTP call is wrapped in an `aperture.hook` span that propagates the trace context (`traceparent` header) to the hook service. This allows a single trace to span your API → hook-service → and back, showing the complete flow.
- **Audit write spans**: Asynchronous audit log writes are linked to the original request span via span links (not parent-child, since writes occur post-commit).

Try the invoice creation flow (step 3 or 4 above) and look at the Jaeger trace — you'll see the API request → hook dispatch → hook-service validation all in one trace.

### 7. Observability and Metrics

Aperture exposes metrics at `/actuator/metrics` and Prometheus scrape endpoint at `/actuator/prometheus`. Bruno includes folders for both:

```bash
# Health check
curl http://localhost:8080/actuator/health

# Metrics (structured)
curl http://localhost:8080/actuator/metrics

# Prometheus scrape (pull your backend's scraper here)
curl http://localhost:8080/actuator/prometheus | grep aperture
```

Key metrics during the walkthrough:

- `aperture.hook.duration` — how long hook dispatch took
- `aperture.audit.queue.size` — how many events are pending write
- `http.server.requests` — request count, latency, status by endpoint and tenant

## Bruno API Collection

Import `api-collection/` into [Bruno](https://usebruno.com) for a full set of ready-to-run requests: auth, tenant management, users, invites, service accounts, entity CRUD, atomic operations, MCP, optimistic locking, GraphQL, `scopedBy` filtering, and rate limiting, plus a cleanup folder. `api-collection/10-cli/README.md` maps each of those folders to the equivalent `mise run build-cli`-built CLI command for people who'd rather run commands than click through requests — see also `mise run test-cli` for the automated version of that walkthrough.

The `13-rate-limit` folder is the interactive mirror of the rate-limit walk-through. It exercises the Valkey-backed provider and demonstrates the `429 Too Many Requests` path with rate-limit headers (`X-RateLimit-*`, `Retry-After`) when the IP bucket is exhausted. By default, the demo uses a safe IP bucket (capacity=100); to see the 429 response, override the limit with `APERTURE_RATE_LIMIT_IP_CAPACITY=2 docker compose up -d --force-recreate api-server`, or run the automated test with `./rate-limit-smoke.sh`.

## Tear Down

```bash
mise run docker-clear
```

## TLS (optional)

Aperture delegates TLS entirely to Spring Boot — no framework code changes are needed.

To enable HTTPS for local development:

```bash
# Generate a self-signed certificate
mkdir -p certs
openssl req -x509 -newkey rsa:4096 -keyout certs/tls.key -out certs/tls.crt \
  -days 365 -nodes \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

Then uncomment the TLS lines in `docker-compose.yml` and restart:

```bash
mise run docker-clear
mise run docker-deploy
```

Self-signed certificates will show browser security warnings. For production, use a certificate from a trusted CA (Let's Encrypt, internal PKI, etc.). HTTP→HTTPS redirect requires a reverse proxy (nginx, traefik, AWS ALB) — Aperture does not provide a redirect connector.
