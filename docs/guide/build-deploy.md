---
title: Build & Deploy
description: The Maven plugin, schema migration automation, lock files, and Docker deployment.
---

# Build & Deploy

## The Maven plugin

`aperture-maven-plugin` runs during the `generate-sources` phase of every Maven build. Add it to your project's `pom.xml`:

```xml
<plugin>
  <groupId>com.itsjool.aperture</groupId>
  <artifactId>aperture-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
</plugin>
```

Default configuration (all paths relative to `${project.basedir}`):

| Parameter | Default |
|---|---|
| `manifestDirectory` | `manifests/` |
| `outputDirectory` | `target/generated-sources/aperture/` |
| `lockDirectory` | `.aperture.lock/` |
| `generatedResourcesDirectory` | `target/generated-resources/` |

### Generated CLI (optional)

Aperture can generate a fully-featured CLI for your API from your manifests — entity CRUD commands, auth, profiles, and optional GraalVM native binary support. It is **off by default**.

See the [Generated CLI guide](/guide/cli) for full setup instructions, GraalVM native builds, platform targeting, and configuration reference.

`mvn clean` deletes `target/` entirely — regenerate with `mvn package` or `mvn verify`.

## Building

```bash
# Full build — validates manifests, generates code and changesets, compiles, tests, packages
mvn verify --no-transfer-progress

# Faster: only the core runtime and engine modules (no demo)
mvn verify -pl core/aperture-core-runtime,core/aperture-core-engine,core/aperture-changeset \
    -am --no-transfer-progress

# Demo component tests (requires Docker)
mvn verify -pl demos/aperture-demo --no-transfer-progress
```

The build fails if:
- Any manifest references an undefined entity, role, or policy
- A field is removed or type-changed without a corresponding API version bump
- A global entity references a tenant-scoped entity in POOL mode

## Schema automation

The changeset generator produces two Liquibase XML files per build:

### `aperture-schema.xml` — full DDL

Contains `createTable` changesets for every entity, plus all foreign key constraints and indexes. Used when deploying to a fresh database. Liquibase runs this file idempotently — change sets that have already applied are skipped.

### `aperture-incremental.xml` — diff-only delta

Contains only the changes between the current manifest and the committed lock files. Run on every deploy against an existing database.

The four diff categories and what they generate:

| Change | Generated changeset | Notes |
|---|---|---|
| New field added | `addColumn` with `MARK_RAN` precondition | Idempotent — skipped if column already exists |
| Field renamed (`renamedFrom:`) | `renameColumn` | No data loss |
| Field removed | `dropColumn` with `context="pending"` | **Not applied automatically** (see Deferred drops) |
| New ManyToOne field | `addForeignKeyConstraint` | Added after both tables exist |

### Deferred drops explained

When you remove a field from a manifest, the changeset generator writes a `dropColumn` changeset tagged `context="pending"`:

```xml
<changeSet id="deferred-drop-aperture_invoices-old_field"
           author="aperture"
           context="pending">
    <dropColumn tableName="aperture_invoices" columnName="old_field"/>
</changeSet>
```

This changeset is **not applied during normal deployments** because the `pending` context is not active. To apply it deliberately:

```bash
mvn liquibase:update -Dliquibase.contexts=pending
```

This safety mechanism means you can remove a field from the manifest, deploy the new version (which stops reading/writing the column), and only drop the column when you are confident the data is no longer needed. Accidental `DROP COLUMN` with data loss is not possible through normal deployments.

### `renamedFrom` — zero-downtime renames

To rename a field without data loss, add `renamedFrom:` to the new field definition:

```yaml
fields:
  phone_number:                 # new name
    type: string
    since: 2
    renamedFrom: phone          # old column name
```

The diff engine generates a `renameColumn` changeset instead of a `dropColumn` + `addColumn` pair. No data is lost.

## The lock files

After a successful build, the Maven plugin writes updated JSON snapshots to `.aperture.lock/` (one file per entity). **Commit these alongside every manifest change.**

Lock files serve two purposes:
1. They tell the diff engine what the database currently looks like
2. They are the schema migration record — without them, the next build treats every entity as new and regenerates all changesets from scratch

Never delete lock files unless you intend to recreate the entire schema from scratch.

## Manual migration manifests

For data migrations, backfills, and custom SQL that doesn't map to a manifest change, use the `Migration` kind:

```yaml
apiVersion: aperture.itsjool.com/v1
kind: Migration
metadata:
  name: backfill-phone-number
spec:
  position:
    after: create-customer-table     # positions the changeset in the changelog
  sql: |
    UPDATE aperture_customers
    SET phone_number = '000-000-0000'
    WHERE phone_number IS NULL;
  rollback: |
    UPDATE aperture_customers
    SET phone_number = NULL
    WHERE phone_number = '000-000-0000';
```

Migration manifests are included in the root changelog at the position specified by `position.after`. They are applied once, tracked by Liquibase, and have a `rollback` block for safe downgrade.

## API versioning

Fields can be gated to a specific API version using `since:`:

```yaml
fields:
  phone_number:
    type: string
    since: 2           # invisible to v1 clients
```

The code generator produces versioned entity classes — `V1Customer` and `V2Customer`. Requests to `/api/v1/customers` use `V1Customer` (no `phone_number`). Requests to `/api/v2/customers` use `V2Customer` (with `phone_number`). Old clients continue to work without modification.

API versions and their status (`ACTIVE` or `SUNSET`) are declared in `ApiVersionConfig` manifests:

```yaml
apiVersion: aperture.itsjool.com/v1
kind: ApiVersionConfig
spec:
  versions:
    - name: "1"
      status: ACTIVE
    - name: "2"
      status: ACTIVE
```

Removing a version (or setting it to `SUNSET`) requires an API version bump to avoid breaking changes at build time.

Every entity is reachable over GraphQL as well as JSON:API, at `/graphql/{version}` — off by default, and gated on the same path-based versioning as the REST endpoints. Nested relationship traversal and mutations both work against the same permission and manifest model as REST. See [GraphQL configuration](/reference/configuration#graphql-elide-graphql) to turn it on.

## Docker deployment

Aperture apps are standard Spring Boot applications. Package with Maven, copy the JAR into a runtime image.

### Dockerfile

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn package -DskipTests --no-transfer-progress

# Stage 2: runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Environment variables

All Aperture configuration is environment-variable-driven. Required at runtime:

| Variable | Description |
|---|---|
| `DB_URL` | JDBC URL — e.g. `jdbc:postgresql://postgres:5432/myapp` |
| `DB_USER` | Database username |
| `DB_PASS` | Database password |
| `APERTURE_JWT_SECRET` | HMAC signing key — minimum 32 bytes |
| `APERTURE_ENCRYPTION_KEY` | AES-256 encryption key — 32-byte Base64 (`openssl rand -base64 32`) |
| `APERTURE_HOOKS_SECRET` | Shared secret for hook request signing |

`APERTURE_BOOTSTRAP_ADMIN_PASSWORD` (seen in the demo compose files) is **not** a general
framework variable — see [Bootstrap admin](/reference/configuration#bootstrap-admin) for what
actually consumes it.

### Schema migration on startup

Liquibase runs automatically on startup. Against a fresh database it applies the full DDL from `aperture-schema.xml`; against an existing database it runs only the incremental changesets from `aperture-incremental.xml`. The `/actuator/health` endpoint reports `DOWN` until migrations complete — configure your orchestrator health check accordingly:

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 10s
  retries: 15
  start_period: 90s
```

### Minimal docker-compose.yml

```yaml
services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: myapp
      POSTGRES_PASSWORD: ${DB_PASS}
      POSTGRES_DB: myapp
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U myapp"]
      interval: 10s
      retries: 5

  api-server:
    image: my-aperture-app:latest
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/myapp
      DB_USER: myapp
      DB_PASS: ${DB_PASS}
      APERTURE_JWT_SECRET: ${APERTURE_JWT_SECRET}
      APERTURE_ENCRYPTION_KEY: ${APERTURE_ENCRYPTION_KEY}
      APERTURE_HOOKS_SECRET: ${APERTURE_HOOKS_SECRET}
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 10s
      retries: 15
      start_period: 90s
```

For a complete worked example including a hook service, database seeder, and distributed tracing, see the [Billing Demo](/examples/billing-demo).
