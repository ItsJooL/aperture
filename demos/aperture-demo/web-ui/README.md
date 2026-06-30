# Aperture Demo Web UI

A Vue 3 single-page application for the Aperture billing demo. Connects to the Aperture JSON:API backend and provides a product-style interface for managing customers, invoices, payments, products, and suppliers across tenants.

## Running against the Java backend

```bash
npm install
cp .env.example .env.local
npm run dev
```

Open <http://localhost:5173>. The backend must be running (`docker compose up -d` from `demos/aperture-demo`).

`.env.example`:

```env
VITE_API_BASE_URL=http://localhost:8080
VITE_DEFAULT_API_VERSION=v3
```

## Running against the mock API service

Run the mock service in one terminal:

```bash
npm run dev:mock-api
```

Run the SPA against it in another terminal:

```bash
cp .env.mock.example .env.local
npm run dev
```

The mock service listens on `http://localhost:8081`. Use any password with these usernames:

```
admin@aperture.local   — admin role
viewer@aperture.local  — read-only role
Password: aperture-demo
```

## Project layout

```
mock-api/         Local mock HTTP service for demos and E2E tests (not bundled with SPA)
src/
  api/
    http/         Fetch client, content-type handling, error mapping
    jsonapi/      JSON:API document and relationship adapters
    services/     Domain-facing services used by screens
    types/        Product domain types
  components/
    ui/           Shared Vue components and UX primitives
    product/      Workflow-specific product components
  config/         Runtime configuration
  layouts/        Product shell layout
  router/         Route config, auth guard, admin guard
  stores/         Pinia stores for app, auth, and workspace state
  styles/         Theme and app styling
  views/          Product screens and user journey flows
tests/
  mocks/          MSW handlers and seed data
  unit/           Unit tests for adapters, utilities, and components
  integration/    Service/component/router tests backed by MSW
  e2e/            Browser journey tests against the mock API service
```

## Test commands

```bash
npm run typecheck
npm run test:unit
npm run test:integration
npm run test
npm run build
npm run test:e2e
```

Unit and integration tests do not require the Java backend — they use MSW inside Vitest.

Playwright E2E tests start the mock API service and Vue dev server automatically:

```bash
npx playwright install chromium
npm run test:e2e
```
