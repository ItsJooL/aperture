# 10 — CLI

The other folders in this collection (`01-auth` through `09-optimistic-lock-missing`)
drive the demo's HTTP API directly with Bruno. This folder is different: instead of
`.bru` request files, it's a walkthrough of the same demo using the generated CLI
(`docs/guide/cli.md`), for people who'd rather run commands than click through
requests. Build it once with:

```bash
mvn package -pl demos/aperture-demo -am
CLI="java -jar target/generated-cli/aperture-cli/target/aperture-cli-0.0.1-SNAPSHOT.jar"
# or, if built with -Pnative: CLI="target/generated-cli/aperture-cli/target/aperture"
```

No `--api-version` needed: this demo has no unversioned fallback (once a manifest
declares an `ApiVersionConfig`, every entity is registered *only* under its real
versions), but the generated CLI bakes in the manifest's `ACTIVE` version
(`3` here, per `manifests/aperture/versions.yaml`) as `GlobalOptions
.DEFAULT_API_VERSION`, used automatically whenever `--api-version` isn't passed.
Add `--server` per call if it's not `http://localhost:8080`. Use `--api-version 1`
or `--api-version 2` to explicitly target a `SUNSET` version instead (see the
`06-entities` section below).

## Maps to `01-auth`, `08-cleanup`

```bash
$CLI --server http://localhost:8080 auth login --username superadmin@aperture.local --password "$APERTURE_BOOTSTRAP_ADMIN_PASSWORD"
$CLI --server http://localhost:8080 auth me
$CLI --server http://localhost:8080 auth refresh
$CLI --server http://localhost:8080 auth logout
```

`accept-invite` (`01-auth/05-accept-invite.bru`) has no CLI equivalent — see
"Not covered" below.

## Maps to `02-tenants`, `03-users`, `04-invites`

Not covered by the CLI. Tenant provisioning, user administration (create/list/
update/replace-roles/delete), and invite management are admin/provisioning APIs the
CLI doesn't wrap — it's an end-user/operator tool for a tenant that already exists,
not a platform-admin tool. Use the Bruno requests in those folders, or the raw API,
for this.

## Maps to `05-service-accounts`

```bash
# Service-account token (POST /auth/token)
$CLI --server http://localhost:8080 auth token --client-id "$CLIENT_ID" --client-secret "$CLIENT_SECRET"

# Personal API keys (POST/GET/POST .../auth/me/api-keys)
$CLI --server http://localhost:8080 auth api-keys create --name "My API Key" --expires-at 2027-01-01T00:00:00Z
$CLI --server http://localhost:8080 auth api-keys list
$CLI --server http://localhost:8080 auth api-keys disable <keyId>

# Use a stored API key instead of a bearer token for subsequent calls:
$CLI --profile my-key config set-api-key <secret>
$CLI --profile my-key --server http://localhost:8080 get customers
```

Creating a service account itself, and disabling one (`05-service-accounts/01`,
`07`), are not covered — same reasoning as tenants/users above: this manages the
*calling* account's own tokens and keys, not other accounts.

`--expires-at` above is deliberate, not `--non-expiring`: this demo tenant rejects
non-expiring personal API keys (`IdentityAdministrationValidationException`), which
is correct, intentional tenant policy, not a bug.

## Maps to `06-entities`

```bash
$CLI --server http://localhost:8080 create customers --name "Initech Corporation" --email accounts@initech.example
$CLI --server http://localhost:8080 get customers --page 1 --size 20 --sort name
$CLI --server http://localhost:8080 get customers <id>
$CLI --server http://localhost:8080 update customers <id> --name "Initech Corp" --if-match '"0"'
$CLI --server http://localhost:8080 delete customers <id> --if-match '"0"' --yes
```

Every generated entity gets the same subcommand under each of the `get`/`create`/
`update`/`delete` verbs. `update`/`delete` need `--if-match` on entities with
`optimisticLocking: true` (Customer included) — the CLI does not fetch the current
ETag for you on these two subcommands; quote the `version` attribute from a prior
`get`/`create` call (`apply --upsert`, `update -f`, and `delete -f` *do* fetch it
automatically — see the `07` section below).

`06-entities/06-create-customer-v3-mutate.bru` (exercising the `EnrichCustomer` mutate hook,
which requires `/api/v3/`) has a direct equivalent — no explicit `--api-version`
needed, since `3` is already the default:

```bash
$CLI --server http://localhost:8080 create customers --name "  Acme Corp  " --email acme-enriched@example.com --format json
# → name comes back trimmed to "Acme Corp": the EnrichCustomer mutate hook ran before persisting.
```

## Maps to `07-atomic-operations`

```bash
$CLI --server http://localhost:8080 apply -f resources/products.yaml       # one-time: seed products
$CLI --server http://localhost:8080 apply -f resources-atomic/order.yaml --atomic --dry-run
$CLI --server http://localhost:8080 apply -f resources-atomic/order.yaml --atomic
```

`resources-atomic/order.yaml` is the closest CLI equivalent to
`07-atomic-operations/01-atomic-create-invoice-with-line-items.bru`: same
JSON:API Atomic Operations extension, same all-or-nothing guarantee, verified live
with a deliberate-failure rollback test (see the file's own comments for how). It
uses one Customer, one Invoice, one LineItem, and one Payment — not two LineItems —
because two *new* LineItems referencing the same *new* Invoice in one batch hits a
narrower, separate Hibernate bug; see
`../../../../dev-notes/known-issues/elide-multi-version-api-bugs.md` (addendum) for the
isolation trail. The plain (non-atomic) form is `apply -f <file-or-dir>`; see
`docs/guide/cli.md` for `--upsert`, `--continue-on-error`, and `delete -f`.

`07-atomic-operations/04-atomic-rollback-demo.bru` (the deliberate-failure case) is
exactly what the atomic apply above demonstrates when the invoice's hook rejects it
— nothing partial is left behind, not even the customer.

## Maps to `08-mcp`

Not covered. The CLI talks JSON:API; the demo's MCP server is a separate integration
surface (`aperture-simple-mcp`) with its own protocol, unrelated to this CLI.

## Maps to `09-optimistic-lock-missing`

```bash
$CLI --server http://localhost:8080 update customers <id> --name "Missing If-Match Corp"
```

This reproduces the same 428 the Bruno request demonstrates: the generated
`update`/`delete` commands only send `If-Match` when you pass `--if-match`
yourself — there's no automatic ETag fetch on these two subcommands, so omitting
it is exactly the "missing If-Match" case. (Contrast with `apply --upsert`,
`update -f`, and `delete -f`, which do fetch it automatically — see `07` above.)

## GraphQL (not part of the numbered Bruno flow)

Every entity is also queryable/mutable over GraphQL at `/graphql/v{n}` — same
dictionary, same permissions, no CLI equivalent (the CLI is JSON:API-only). See
`../11-graphql/` for worked Bruno examples, including a nested Invoice → Customer →
LineItems query in one round trip.

## Maps to `12-scoped`

```bash
$CLI --server http://localhost:8080 get tasks                                # 403 — no scope header
$CLI --server http://localhost:8080 get tasks --scope project=<projectId>    # 200 — one-shot scope
$CLI --server http://localhost:8080 config set-scope project <projectId>
$CLI --server http://localhost:8080 get tasks                                # 200 — sticky, from the profile
$CLI --server http://localhost:8080 config unset-scope project
```

`Task` (`manifests/domain/work/task.yaml`) declares `scopedBy: project`: reads
are fail-closed without `X-Aperture-Scope-<Field>`, which `--scope field=value`
(one-shot, repeatable) or `config set-scope`/`unset-scope` (persisted per
profile, kubectl-namespace-style) send as a header. See `../12-scoped/README.md`
for the SuperAdmin-bypass and mismatched-scope-create cases, which have no CLI
equivalent shown here since they don't need one to demonstrate.
