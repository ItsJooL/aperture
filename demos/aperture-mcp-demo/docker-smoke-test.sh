#!/usr/bin/env bash
# Smoke test for the aperture-mcp-demo Docker Compose stack.
# Proves the container boots, the seeder ran, and both the JSON:API and the
# generated MCP tool surface are reachable and enforce auth.
# Functional correctness of the MCP tool generation itself lives in the Maven
# suite (McpDemoComponentTest).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8082}"
TIMEOUT="${TIMEOUT:-120}"
FAILURES=0

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
    local method="$1" url="$2" body="${3:-}" token="${4:-}" accept="${5:-application/json}"
    local args=(-s -w "\n%{http_code}" -X "$method" "$url" -H "Content-Type: application/json" -H "Accept: $accept")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$body" ] && args+=(-d "$body")
    curl "${args[@]}"
}

extract() { echo "$1" | jq -r "${2} // empty" 2>/dev/null || true; }

mcp_call() {
    local token="$1" id="$2" method="$3" params="$4"
    req POST "$BASE_URL/mcp" \
        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}" \
        "$token" "application/json, text/event-stream"
}

main() {
    echo "=== Aperture MCP Demo Docker Smoke Test ==="
    echo "BASE_URL: $BASE_URL"
    echo ""

    wait_healthy

    echo "Testing: Superadmin login (bootstrap verification)"
    response=$(req POST "$BASE_URL/auth/login" \
        '{"username":"superadmin@framework.local","password":"changeme-local-only"}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    TOKEN=$(extract "$body" ".accessToken")
    [ "$status" = "200" ] && [ -n "$TOKEN" ] \
        && pass "Superadmin login" \
        || { fail "Superadmin login: status=$status"; echo "$body"; exit 1; }

    echo ""
    echo "Testing: Seeded project visible over JSON:API"
    response=$(req GET "$BASE_URL/api/v1/projects" "" "$TOKEN" "application/vnd.api+json")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    project_count=$(extract "$body" ".data | length")
    [ "$status" = "200" ] && [ -n "$project_count" ] && [ "$project_count" -ge 1 ] \
        && pass "Seeded project(s) visible ($project_count found)" \
        || { fail "List projects: status=$status body=$body"; }

    echo ""
    echo "Testing: Seeded tasks visible over JSON:API"
    response=$(req GET "$BASE_URL/api/v1/tasks" "" "$TOKEN" "application/vnd.api+json")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    task_count=$(extract "$body" ".data | length")
    [ "$status" = "200" ] && [ -n "$task_count" ] && [ "$task_count" -ge 3 ] \
        && pass "Seeded task(s) visible ($task_count found)" \
        || { fail "List tasks: status=$status body=$body"; }

    echo ""
    echo "Testing: MCP tools/list exposes Project full CRUD and Task read-only"
    response=$(mcp_call "$TOKEN" 1 "tools/list" "{}")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    tool_names=$(extract "$body" "[.result.tools[].name] | join(\",\")")
    if [ "$status" = "200" ] \
        && echo "$tool_names" | grep -q "create_project" \
        && echo "$tool_names" | grep -q "delete_project" \
        && echo "$tool_names" | grep -q "list_tasks" \
        && echo "$tool_names" | grep -q "get_task" \
        && ! echo "$tool_names" | grep -q "create_task"; then
        pass "MCP tool surface matches manifest config: $tool_names"
    else
        fail "MCP tools/list: status=$status tools=$tool_names"
    fi

    echo ""
    echo "Testing: MCP tools/call list_tasks"
    response=$(mcp_call "$TOKEN" 2 "tools/call" '{"name":"list_tasks","arguments":{}}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    has_error=$(extract "$body" ".error")
    [ "$status" = "200" ] && [ -z "$has_error" ] \
        && pass "MCP list_tasks call succeeded" \
        || { fail "MCP list_tasks call: status=$status body=$body"; }

    echo ""
    echo "Testing: MCP tools/call create_project"
    response=$(mcp_call "$TOKEN" 3 "tools/call" \
        '{"name":"create_project","arguments":{"name":"Smoke Test Project","description":"created by docker-smoke-test.sh"}}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    has_error=$(extract "$body" ".error")
    [ "$status" = "200" ] && [ -z "$has_error" ] \
        && pass "MCP create_project call succeeded" \
        || { fail "MCP create_project call: status=$status body=$body"; }

    echo ""
    echo "Testing: Unauthenticated MCP request is rejected"
    response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/mcp" \
        -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
        -d '{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}')
    status=$(echo "$response" | tail -n1)
    [ "$status" = "401" ] \
        && pass "Unauthenticated MCP request rejected (401)" \
        || fail "Unauthenticated MCP request: expected 401, got $status"

    echo ""
    echo "=== Results ==="
    if [ "$FAILURES" -eq 0 ]; then
        echo "All checks passed."
        exit 0
    else
        echo "$FAILURES check(s) failed."
        exit 1
    fi
}

main "$@"
