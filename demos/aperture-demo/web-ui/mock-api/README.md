# Aperture Demo Mock API

This is a separate local/demo HTTP service. The Vue SPA does not start or bundle this mock server.

Run it in one terminal:

```bash
npm run dev:mock-api
```

Run the SPA against it in another terminal:

```bash
cp .env.mock.example .env.local
npm run dev
```

The service listens on `http://localhost:8081` by default and exposes `/health` for readiness checks.

This service is for local demos and E2E tests only. Production builds should point `VITE_API_BASE_URL` at the real Java backend.
