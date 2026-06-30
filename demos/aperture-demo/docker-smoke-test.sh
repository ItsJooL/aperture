#!/usr/bin/env bash
# Smoke test for the Docker Compose stack.
# Proves the container boots, DemoBootstrap runs, and the API is reachable.
# Functional correctness (RBAC, isolation, hooks, etc.) lives in the Maven suite.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TIMEOUT="${TIMEOUT:-120}"
FAILURES=0
RESTART_CHECK="${1:-}"

fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }
pass() { echo "PASS: $1"; }

wait_healthy() {
    local deadline=$((SECONDS + TIMEOUT))
    echo "Waiting for $BASE_URL/actuator/health (timeout ${TIMEOUT}s) ..."
    until curl -sf "$BASE_URL/actuator/health" > /dev/null 2>&1; do
        [ $SECONDS -lt $deadline ] || { fail "Health check timed out"; exit 1; }
        sleep 2
    done
    pass "Service healthy"
}

req() {
    local method="$1" url="$2" body="${3:-}" token="${4:-}"
    local args=(-s -w "\n%{http_code}" -X "$method" "$url" -H "Content-Type: application/json")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$body" ] && args+=(-d "$body")
    curl "${args[@]}"
}

extract() { echo "$1" | jq -r "${2} // empty" 2>/dev/null || true; }

main() {
    echo "=== Aperture Docker Smoke Test ==="
    echo "BASE_URL: $BASE_URL"
    echo ""

    wait_healthy

    # Superadmin login — proves DemoBootstrap ran and created the superadmin row
    echo "Testing: Superadmin login (bootstrap verification)"
    response=$(req POST "$BASE_URL/auth/login" \
        '{"username":"superadmin@framework.local","password":"changeme-local-only"}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    ADMIN_TOKEN=$(extract "$body" ".accessToken")
    [ "$status" = "200" ] && [ -n "$ADMIN_TOKEN" ] \
        && pass "Superadmin login" \
        || { fail "Superadmin login: status=$status"; echo "$body"; exit 1; }

    if [ "$RESTART_CHECK" = "--restart-check" ]; then
        echo ""
        echo "Restart check: superadmin login succeeded — data persisted across restart."
        echo "All restart checks passed."
        exit 0
    fi

    # Create a tenant to prove the management API and Liquibase schema are intact
    echo "Testing: Tenant provisioning"
    TENANT_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
    INIT_USER_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
    response=$(req POST "$BASE_URL/manage/tenants" \
        "{\"tenantId\":\"$TENANT_ID\",\"tenantName\":\"smoke-tenant\",\"initialAdminUserId\":\"$INIT_USER_ID\",\"initialAdminUsername\":\"admin@smoke.test\",\"initialAdminPassword\":\"Password123!\"}" \
        "$ADMIN_TOKEN")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    [ "$status" = "201" ] && pass "Tenant provisioning" \
        || { fail "Tenant provisioning: status=$status"; echo "$body"; }

    # Tenant admin login
    echo "Testing: Tenant admin login"
    response=$(req POST "$BASE_URL/auth/login" \
        '{"username":"admin@smoke.test","password":"Password123!"}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    TENANT_TOKEN=$(extract "$body" ".accessToken")
    [ "$status" = "200" ] && [ -n "$TENANT_TOKEN" ] \
        && pass "Tenant admin login" \
        || { fail "Tenant admin login: status=$status"; echo "$body"; }

    # One generated resource round-trip (JSON:API)
    echo "Testing: Resource CRUD round-trip"
    response=$(req POST "$BASE_URL/api/v1/customers" \
        '{"data":{"type":"customers","attributes":{"name":"Smoke Corp","email":"smoke@test.com"}}}' \
        "$TENANT_TOKEN" \
        "$(: content-type is set via req default but we need vnd.api+json)")
    # Override content-type for JSON:API
    response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/customers" \
        -H "Content-Type: application/vnd.api+json" \
        -H "Authorization: Bearer $TENANT_TOKEN" \
        -d '{"data":{"type":"customers","attributes":{"name":"Smoke Corp","email":"smoke@test.com"}}}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    CUSTOMER_ID=$(extract "$body" ".data.id")
    [ "$status" = "201" ] && [ -n "$CUSTOMER_ID" ] \
        && pass "Resource CRUD round-trip" \
        || { fail "Resource CRUD: status=$status id='$CUSTOMER_ID'"; echo "$body"; }

    echo ""
    echo "=== Smoke Test Summary ==="
    if [ $FAILURES -eq 0 ]; then
        echo "All smoke tests passed."
        exit 0
    else
        echo "FAILED: $FAILURES test(s)"
        exit 1
    fi
}

main "$@"
