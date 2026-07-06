#!/usr/bin/env bash
# CLI smoke test — runs against a live demo server.
# Usage: ./cli-smoke-test.sh
# Requires: demo server running, BOOTSTRAP_PASSWORD set (default: changeme-local-only)
set -euo pipefail

CLI_DIR="target/generated-cli/aperture-cli"
SERVER="${APERTURE_SERVER:-http://localhost:8080}"
SUPERADMIN_USERNAME="${SUPERADMIN_USERNAME:-superadmin@framework.local}"
SUPERADMIN_PASSWORD="${APERTURE_BOOTSTRAP_ADMIN_PASSWORD:-changeme-local-only}"
TENANT_USERNAME="${TENANT_USERNAME:-admin@acme.com}"
TENANT_PASSWORD="${TENANT_PASSWORD:-AcmeAdmin123!}"
BINARY="${BINARY_NAME:-aperture}"
CONFIG_DIR="$(mktemp -d)"
export APERTURE_CONFIG="$CONFIG_DIR/config.json"
trap 'rm -rf "$CONFIG_DIR"' EXIT

# JVM invocation (always available after mvn package)
CLI="java -jar $CLI_DIR/target/aperture-cli-0.0.1-SNAPSHOT.jar"

# Prefer native binary if present (built with -Pnative)
if [ -x "$CLI_DIR/target/$BINARY" ]; then
  CLI="$CLI_DIR/target/$BINARY"
fi

echo "==> Using CLI: $CLI"
echo "==> Server: $SERVER"
echo "==> Config: $APERTURE_CONFIG"

# version
$CLI version

# status — contributed by SimpleStatusCliContribution (CliCommandContribution demo)
$CLI --server "$SERVER" status

# shell completion generation
$CLI generate-completion > "$CONFIG_DIR/completion.sh"
grep -q "$BINARY" "$CONFIG_DIR/completion.sh"
echo "==> Shell completion generation: OK"

# auth login as superadmin (validates auth mechanism)
$CLI --server "$SERVER" auth login --username "$SUPERADMIN_USERNAME" --password "$SUPERADMIN_PASSWORD"
echo "==> Superadmin login: OK"

# auth me
$CLI --server "$SERVER" auth me --format json
echo "==> Auth me: OK"

# auth logout
$CLI --server "$SERVER" auth logout
echo "==> Superadmin logout: OK"

# login as tenant admin for CRUD operations (customers are tenant-scoped)
$CLI --server "$SERVER" auth login --username "$TENANT_USERNAME" --password "$TENANT_PASSWORD"
echo "==> Tenant admin login: OK"

# config profile helpers
$CLI --profile config-smoke config set-server "$SERVER"
$CLI --profile config-smoke config set-api-version 1
$CLI --profile config-smoke config set-tenant cli-smoke-tenant
$CLI --profile config-smoke config show | grep -q "Auth:"
$CLI config delete-profile config-smoke
echo "==> Config profile helpers: OK"

# list customers (get with no id) — no --api-version passed: the CLI defaults to this
# manifest's ACTIVE version (baked in at generation time, see GlobalOptions.DEFAULT_API_VERSION).
$CLI --server "$SERVER" get customers --format json
echo "==> Customer list: OK"

# list query passthrough
$CLI --server "$SERVER" get customers --page 1 --size 5 --sort name --format json
echo "==> Customer list query options: OK"

# API key creation and profile storage (this tenant disallows non-expiring keys)
API_KEY_OUTPUT=$($CLI --server "$SERVER" auth api-keys create --name "cli-smoke-$(date +%s)" --expires-at "$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)")
API_KEY_SECRET=$(echo "$API_KEY_OUTPUT" | tail -n1)
if [ -z "$API_KEY_SECRET" ]; then
  echo "FAIL: API key secret was not printed"
  exit 1
fi
$CLI --profile api-key-smoke config set-api-key "$API_KEY_SECRET"
$CLI --server "$SERVER" --profile api-key-smoke get customers --format json
echo "==> API key auth: OK"

# create a customer
CREATED=$($CLI --server "$SERVER" create customers --name "Smoke Test Co" --email "smoke@test.example" --format json)
echo "==> Created: $CREATED"

CUSTOMER_ID=$(echo "$CREATED" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || echo "")
CUSTOMER_ETAG=$(echo "$CREATED" | python3 -c "import sys,json; print('\"' + str(json.load(sys.stdin)['data']['attributes']['version']) + '\"')" 2>/dev/null || echo "")

if [ -n "$CUSTOMER_ID" ]; then
  echo "==> Customer ID: $CUSTOMER_ID"
  # get by id
  $CLI --server "$SERVER" get customers "$CUSTOMER_ID" --format json
  echo "==> Customer get: OK"
  # update (optimistic locking requires --if-match; the CLI does not fetch it automatically)
  $CLI --server "$SERVER" update customers "$CUSTOMER_ID" --name "Smoke Test Co Updated" --if-match "$CUSTOMER_ETAG"
  echo "==> Customer update: OK"
  # re-fetch to get the post-update etag (update's own output isn't --format-aware JSON)
  AFTER_UPDATE=$($CLI --server "$SERVER" get customers "$CUSTOMER_ID" --format json)
  CUSTOMER_ETAG=$(echo "$AFTER_UPDATE" | python3 -c "import sys,json; print('\"' + str(json.load(sys.stdin)['data']['attributes']['version']) + '\"')")
  # delete (--yes skips prompt; verify the resource is gone after)
  $CLI --server "$SERVER" delete customers "$CUSTOMER_ID" --if-match "$CUSTOMER_ETAG" --yes
  echo "==> Customer delete: OK"
  # post-delete verification: get should fail with 404
  if $CLI --server "$SERVER" get customers "$CUSTOMER_ID" --format json 2>/dev/null; then
    echo "FAIL: customer $CUSTOMER_ID still accessible after delete"
    exit 1
  fi
  echo "==> Post-delete verification: OK (resource no longer accessible)"
  echo "==> CRUD lifecycle passed for customer $CUSTOMER_ID"
else
  echo "WARN: Could not extract customer ID from response — skipping get/update/delete"
fi

# declarative apply/delete -f round trip
APPLY_NAME="CLI Apply Smoke $(date +%s)"
APPLY_FILE="$CONFIG_DIR/cli-smoke.yaml"
cat > "$APPLY_FILE" <<YAML
customers:
  - name: "$APPLY_NAME"
    email: "cli-apply-smoke-$(date +%s)@test.example"
YAML

$CLI --server "$SERVER" apply -f "$APPLY_FILE"
echo "==> CLI apply: OK"

# idempotency: every demo entity's natural key is now unique-constrained, so re-applying
# the same file must skip rather than duplicate (previously silent no-op due to missing
# unique constraints — see dev-notes/reviews/2026-07-03-cli-kubectl-comparison.md).
REAPPLY=$($CLI --server "$SERVER" apply -f "$APPLY_FILE" 2>&1)
if echo "$REAPPLY" | grep -q "Applied 0 resource(s)  skipped=1  failed=0"; then
  echo "==> Idempotent re-apply (skip-on-conflict): OK"
else
  echo "FAIL: re-applying the same file did not skip as expected:"
  echo "$REAPPLY"
  exit 1
fi

$CLI --server "$SERVER" delete -f "$APPLY_FILE" --yes
echo "==> CLI delete -f: OK"

# atomic multi-resource order (see resources-atomic/order.yaml for the full story).
# products.yaml is idempotent (skips on repeat runs, verified above); order.yaml's
# customer name is templated per run since --atomic has no skip-on-conflict path at
# all (documented, intentional — see dev-notes/reviews/2026-07-03-cli-kubectl-comparison.md)
# and this script must stay repeatable against a persistent, already-seeded database.
$CLI --server "$SERVER" apply -f resources/products.yaml
ORDER_FILE="$CONFIG_DIR/order.yaml"
sed "s/Roadrunner Distribution/Roadrunner Distribution $(date +%s)/;s/ap@roadrunner-distribution.example/ap-$(date +%s)@roadrunner-distribution.example/" \
  resources-atomic/order.yaml > "$ORDER_FILE"
$CLI --server "$SERVER" apply -f "$ORDER_FILE" --atomic
echo "==> CLI atomic apply: OK"

# per-version field visibility (v1 hides phone_number and carries Sunset; v2+ shows it) —
# these two calls deliberately override the default to prove --api-version still works.
V1_BODY=$($CLI --server "$SERVER" --api-version 1 get customers --format json)
if echo "$V1_BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(1 if any('phone_number' in c['attributes'] for c in d['data']) else 0)"; then
  echo "==> v1 hides phone_number: OK"
else
  echo "FAIL: v1 customers response unexpectedly includes phone_number"
  exit 1
fi
V2_BODY=$($CLI --server "$SERVER" --api-version 2 get customers --format json)
if echo "$V2_BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if all('phone_number' in c['attributes'] for c in d['data']) else 1)"; then
  echo "==> v2 shows phone_number: OK"
else
  echo "FAIL: v2 customers response missing phone_number"
  exit 1
fi
echo "==> Per-version field visibility: OK"

# GraphQL (CLI is JSON:API-only, so this exercises the raw API directly) — proves the
# GraphQL schema builds and serves real queries/mutations across versioned entity classes.
# v3 is hardcoded here (not derived from the CLI) since GraphQL isn't reachable through it.
GRAPHQL_TOKEN=$(curl -s -X POST "$SERVER/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$TENANT_USERNAME\",\"password\":\"$TENANT_PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
GRAPHQL_RESULT=$(curl -s "$SERVER/graphql/v3" \
  -H "Authorization: Bearer $GRAPHQL_TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"{ invoices { edges { node { id amount customer { edges { node { name } } } lineItems { edges { node { description } } } } } } }"}')
if echo "$GRAPHQL_RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if 'data' in d and 'invoices' in d['data'] else 1)"; then
  echo "==> GraphQL nested query (invoice -> customer + lineItems): OK"
else
  echo "FAIL: GraphQL query did not return expected data: $GRAPHQL_RESULT"
  exit 1
fi

# scopedBy context: Task (manifests/domain/work/task.yaml) declares scopedBy: project — ALL
# operations on a scoped entity require the scope context, including create: Elide evaluates
# ReadPermission when serializing the created entity back, and the fail-closed scope filter
# denies it without X-Aperture-Scope-Project (verified live 2026-07-05). The PRESECURITY hook
# additionally rejects a create whose relationship MISmatches a present scope header (400).
# Create a fresh project per run so the read checks below see exactly this run's tasks.
SCOPE_PROJECT=$($CLI --server "$SERVER" create projects --name "Smoke Scope Project $(date +%s)" --format json)
SCOPE_PROJECT_ID=$(echo "$SCOPE_PROJECT" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || echo "")

if [ -n "$SCOPE_PROJECT_ID" ]; then
  echo "==> Scope project ID: $SCOPE_PROJECT_ID"
  # create WITHOUT scope context must fail closed (Elide read-back of the created entity)
  if $CLI --server "$SERVER" create tasks --title "Smoke scope task" --status TODO --project-id "$SCOPE_PROJECT_ID" --format json 2>/dev/null; then
    echo "FAIL: create tasks without scope context unexpectedly succeeded"
    exit 1
  fi
  echo "==> create tasks without scope: correctly rejected (fail-closed)"

  $CLI --server "$SERVER" create tasks --title "Smoke scope task" --status TODO --project-id "$SCOPE_PROJECT_ID" --scope "project=$SCOPE_PROJECT_ID" --format json
  echo "==> Task created with matching --scope context: OK"

  # get tasks with no scope context: fail-closed means ZERO rows, not an HTTP error —
  # for collection reads Elide evaluates the scope FilterExpressionCheck as a SQL predicate,
  # so the ForbiddenAccessException surfaces as an empty page (200), unlike the single-object
  # create read-back which yields 403 (verified live + ScopedByProbeComponentTest 2026-07-06).
  # The task created above must NOT appear.
  NO_SCOPE_BODY=$($CLI --server "$SERVER" get tasks --format json 2>/dev/null || echo '{"data":[]}')
  if ! echo "$NO_SCOPE_BODY" | python3 -c "import sys,json; sys.exit(0 if len(json.load(sys.stdin).get('data',[]))==0 else 1)"; then
    echo "FAIL: get tasks with no scope context returned rows (scope filter leaked)"
    exit 1
  fi
  echo "==> get tasks without scope: zero rows (fail-closed)"

  # one-shot --scope flag — this run's task MUST be visible now
  SCOPED_BODY=$($CLI --server "$SERVER" get tasks --scope "project=$SCOPE_PROJECT_ID" --format json)
  if ! echo "$SCOPED_BODY" | python3 -c "import sys,json; sys.exit(0 if len(json.load(sys.stdin).get('data',[]))>=1 else 1)"; then
    echo "FAIL: get tasks --scope returned no rows (expected this run's task)"
    exit 1
  fi
  echo "==> get tasks --scope project=<id>: task visible: OK"

  # sticky scope via config set-scope, then a bare (no --scope flag) get
  $CLI --profile scope-smoke config set-server "$SERVER"
  $CLI --profile scope-smoke auth login --username "$TENANT_USERNAME" --password "$TENANT_PASSWORD"
  $CLI --profile scope-smoke config set-scope project "$SCOPE_PROJECT_ID"
  STICKY_BODY=$($CLI --profile scope-smoke get tasks --format json)
  if ! echo "$STICKY_BODY" | python3 -c "import sys,json; sys.exit(0 if len(json.load(sys.stdin).get('data',[]))>=1 else 1)"; then
    echo "FAIL: sticky-scope get tasks returned no rows (expected this run's task)"
    exit 1
  fi
  echo "==> config set-scope + bare get tasks (sticky context): OK"

  # cleanup: unset-scope, then bare get is fail-closed again (zero rows, see above)
  $CLI --profile scope-smoke config unset-scope project
  UNSET_BODY=$($CLI --profile scope-smoke get tasks --format json 2>/dev/null || echo '{"data":[]}')
  if ! echo "$UNSET_BODY" | python3 -c "import sys,json; sys.exit(0 if len(json.load(sys.stdin).get('data',[]))==0 else 1)"; then
    echo "FAIL: get tasks after config unset-scope returned rows (scope filter leaked)"
    exit 1
  fi
  echo "==> config unset-scope: OK (zero rows again)"
  $CLI config delete-profile scope-smoke
else
  echo "WARN: Could not extract scope project ID from response — skipping scoped tasks checks"
fi

# auth logout
$CLI --server "$SERVER" auth logout
echo "==> Tenant logout: OK"

echo "==> Smoke test passed"
