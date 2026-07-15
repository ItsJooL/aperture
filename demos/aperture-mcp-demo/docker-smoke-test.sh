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

# The API reports healthy before the seeder has run, so every assertion about seeded data
# races the seeder unless we wait for it. A stale volume masks this: the previous run's data
# is already there. Poll until the seeder's project shows up.
wait_for_seed() {
    local token="$1"
    local deadline=$((SECONDS + TIMEOUT))
    echo "Waiting for the seeder to finish (timeout ${TIMEOUT}s) ..."
    while :; do
        local body count
        body=$(curl -s "$BASE_URL/api/v1/projects" -H "Authorization: Bearer $token" \
            -H "Accept: application/vnd.api+json")
        count=$(echo "$body" | jq -r '.data | length' 2>/dev/null || echo 0)
        [ -n "$count" ] && [ "$count" -ge 1 ] && { pass "Seeder finished"; return 0; }
        [ $SECONDS -lt $deadline ] || { fail "Seeder did not seed within ${TIMEOUT}s"; exit 1; }
        sleep 2
    done
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
        '{"username":"superadmin@aperture.local","password":"changeme-local-only"}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    TOKEN=$(extract "$body" ".accessToken")
    [ "$status" = "200" ] && [ -n "$TOKEN" ] \
        && pass "Superadmin login" \
        || { fail "Superadmin login: status=$status"; echo "$body"; exit 1; }

    echo ""
    wait_for_seed "$TOKEN"

    echo ""
    echo "Testing: Seeded project visible over JSON:API"
    response=$(req GET "$BASE_URL/api/v1/projects" "" "$TOKEN" "application/vnd.api+json")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    project_count=$(extract "$body" ".data | length")
    SEEDED_PROJECT_ID=$(extract "$body" ".data[0].id")
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
    echo "Testing: MCP tools/list exposes Project and Task full CRUD"
    response=$(mcp_call "$TOKEN" 1 "tools/list" "{}")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    tool_names=$(extract "$body" "[.result.tools[].name] | join(\",\")")
    missing=""
    for tool in list_projects get_project create_project update_project delete_project \
                list_tasks get_task create_task update_task delete_task; do
        echo "$tool_names" | grep -q "$tool" || missing="$missing $tool"
    done
    [ "$status" = "200" ] && [ -z "$missing" ] \
        && pass "MCP tool surface matches manifest config: $tool_names" \
        || fail "MCP tools/list: status=$status missing=[$missing] tools=$tool_names"

    echo ""
    echo "Testing: Principal-scoped tools/list (plan 016 phase 2) - ReadOnly key sees only reads"
    response=$(req POST "$BASE_URL/auth/login" \
        '{"username":"agent-admin@mcp-demo.local","password":"changeme-local-only"}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    AGENT_ADMIN_TOKEN=$(extract "$body" ".accessToken")
    [ "$status" = "200" ] && [ -n "$AGENT_ADMIN_TOKEN" ] \
        || { fail "agent-admin login: status=$status"; exit 1; }

    response=$(req POST "$BASE_URL/auth/me/api-keys" \
        '{"name":"Smoke test ReadOnly key","domainRoles":["ReadOnly"],"securityAttributes":{}}' \
        "$AGENT_ADMIN_TOKEN")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    READONLY_KEY=$(extract "$body" ".secret")
    [ "$status" = "201" ] && [ -n "$READONLY_KEY" ] \
        || { fail "Mint ReadOnly API key: status=$status body=$body"; exit 1; }

    # req()/mcp_call() send an Authorization: Bearer header, but MCP demo keys authenticate via
    # X-API-Key, so build this request manually.
    response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/mcp" \
        -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
        -H "X-API-Key: $READONLY_KEY" \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}')
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    readonly_tool_names=$(extract "$body" "[.result.tools[].name] | join(\",\")")
    unexpected=""
    for tool in create_project update_project delete_project create_task update_task delete_task; do
        echo "$readonly_tool_names" | grep -q "$tool" && unexpected="$unexpected $tool"
    done
    missing=""
    for tool in list_projects get_project list_tasks get_task; do
        echo "$readonly_tool_names" | grep -q "$tool" || missing="$missing $tool"
    done
    [ "$status" = "200" ] && [ -z "$unexpected" ] && [ -z "$missing" ] \
        && pass "ReadOnly API key sees only the four read tools: $readonly_tool_names" \
        || fail "ReadOnly tools/list: status=$status unexpected=[$unexpected] missing=[$missing] tools=$readonly_tool_names"

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

    response=$(req GET "$BASE_URL/api/v1/projects" "" "$TOKEN" "application/vnd.api+json")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    created_count=$(extract "$body" '[.data[] | select(.attributes.name == "Smoke Test Project")] | length')
    [ "$status" = "200" ] && [ -n "$created_count" ] && [ "$created_count" -ge 1 ] \
        && pass "MCP-created project is visible over JSON:API" \
        || { fail "MCP-created project not visible: status=$status body=$body"; }

    echo ""
    echo "Testing: MCP tools/call create_task links a ManyToOne relationship by raw id"
    [ -n "$SEEDED_PROJECT_ID" ] || { fail "No seeded project id to link a task to"; exit 1; }
    response=$(mcp_call "$TOKEN" 4 "tools/call" \
        "{\"name\":\"create_task\",\"arguments\":{\"title\":\"Smoke Test Task\",\"project_id\":\"$SEEDED_PROJECT_ID\"}}")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    has_error=$(extract "$body" ".error")
    [ "$status" = "200" ] && [ -z "$has_error" ] \
        && pass "MCP create_task call succeeded" \
        || { fail "MCP create_task call: status=$status body=$body"; }

    response=$(req GET "$BASE_URL/api/v1/tasks" "" "$TOKEN" "application/vnd.api+json")
    status=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    linked_project_id=$(extract "$body" \
        '[.data[] | select(.attributes.title == "Smoke Test Task")][0].relationships.project.data.id')
    # Require a non-empty id: without this, an unseeded stack compares "" to "" and passes.
    [ "$status" = "200" ] && [ -n "$linked_project_id" ] && [ "$linked_project_id" = "$SEEDED_PROJECT_ID" ] \
        && pass "MCP-created task is linked to project $SEEDED_PROJECT_ID" \
        || { fail "MCP-created task relationship: expected project '$SEEDED_PROJECT_ID', got '$linked_project_id'"; }

    echo ""
    echo "Testing: Unauthenticated MCP request is rejected"
    response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/mcp" \
        -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
        -d '{"jsonrpc":"2.0","id":5,"method":"tools/list","params":{}}')
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
