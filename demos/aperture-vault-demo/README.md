# Aperture Vault Demo

This demo proves that Aperture encrypted fields can use an enterprise KMS without changing generated domain code. The `Patient.medical_history_notes` field is declared as encrypted in the manifest, and the application replaces the default local encryption bean with a HashiCorp Vault Transit-backed `EncryptionService`.

## What This Demonstrates

- Manifest-driven encrypted fields: `medical_history_notes` is marked `encrypted: true`.
- SPI replacement: a single Spring bean swaps local encryption for Vault Transit.
- End-to-end behavior: API clients send and receive plaintext, while Postgres stores Vault ciphertext.
- Tenant/entity context: encryption is bound to Aperture's `EncryptionContext`.

## Prerequisites

- Java 25
- Maven
- Docker with Compose
- Optional: Bruno

## Automated Verification

```bash
mvn -pl demos/aperture-vault-demo test
```

Expected result:

- The test starts Vault and Postgres with Testcontainers.
- The active `EncryptionService` is `VaultEncryptionService`.
- Creating a patient stores ciphertext in Postgres.
- Reading the patient through the API returns plaintext.

## Manual Demo

Build the jar:

```bash
mvn -pl demos/aperture-vault-demo package -DskipTests
```

Start the stack:

```bash
cd demos/aperture-vault-demo
docker compose up --build --force-recreate --renew-anon-volumes
```

Login:

```bash
curl -sS -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"doctor@example.test","password":"password"}'
```

Create a patient using the returned token:

```bash
curl -sS -X POST http://localhost:8080/api/v1/patients \
  -H 'Authorization: Bearer <token>' \
  -H 'X-Tenant-ID: 00000000-0000-0000-0000-000000000101' \
  -H 'Content-Type: application/vnd.api+json' \
  -d '{
    "data": {
      "type": "patients",
      "attributes": {
        "name": "Jane",
        "medical_history_notes": "Top Secret Diagnosis"
      }
    }
  }'
```

Verify Postgres stores ciphertext:

```bash
docker compose exec postgres psql -U postgres -d postgres \
  -c "select medical_history_notes from aperture_patients;"
```

Expected: the stored value starts with `vault:v` and does not contain `Top Secret Diagnosis`.

Fetch the patient:

```bash
curl -sS http://localhost:8080/api/v1/patients/<patient-id> \
  -H 'Authorization: Bearer <token>' \
  -H 'X-Tenant-ID: 00000000-0000-0000-0000-000000000101' \
  -H 'Accept: application/vnd.api+json'
```

Expected: the API response contains `Top Secret Diagnosis`.

## Bruno Walkthrough

Open `api-collection` in Bruno and select the `local` environment. Run:

1. Login
2. Create Patient
3. Get Patient
4. Verify Ciphertext

## Demo-Only Security Notes

- Vault runs in dev mode.
- The root token is `root-token`.
- The app uses local demo credentials.
- Do not copy these settings into production.

## Troubleshooting

- If the app fails with a Vault Transit error, confirm `vault-init` completed successfully.
- If login fails, confirm seed data was loaded and the demo user exists.
- If Liquibase reports a checksum mismatch after changing the manifest, recreate the demo database with `docker compose down -v` and start the stack again.
- If Postgres stores plaintext, stop: the `EncryptionService` bean replacement is not active.
