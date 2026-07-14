# Aperture MCP Demo

This demo runs a small no-tenancy Aperture API with generated Model Context
Protocol tools. It proves that MCP tools can be generated from manifests and
served through the same authentication, authorization, and JSON:API request
path as the normal API.

| Entity | Manifest MCP config | Generated tools |
|---|---|---|
| `Project` | None — no `mcp` block. Full CRUD is derived from its `permissions` (`Admin: [create, read, update, delete]`). | `list_projects`, `get_project`, `create_project`, `update_project`, `delete_project` |
| `Task` | None — no `mcp` block. Full CRUD is derived from its `permissions` (`Admin: [create, read, update, delete]`). | `list_tasks`, `get_task`, `create_task`, `update_task`, `delete_task` |

Task's required `ManyToOne` project relationship is written through JSON:API
`relationships`, and MCP exposes it as a raw `project_id` parameter.

The table above is the full generated surface. What a given caller's `tools/list`
actually returns is scoped to their roles: an `Admin` API key sees all ten tools,
while a `ReadOnly` key (`permissions: {ReadOnly: [read]}` on both entities) sees
only `list_projects`, `get_project`, `list_tasks`, and `get_task`. This is
presentation only — `tools/call` is still authorized for real by Elide on every
invocation regardless of what `tools/list` showed, so this can never let a call
through that Elide would otherwise reject. See
[Extending MCP](/guide/manifests#extending-mcp) and
`aperture.mcp.tool-list-scope` in
[the configuration reference](/reference/configuration#mcp-aperture-mcp-spring-ai-mcp)
to restore the unscoped, pre-phase-2 behavior (`STATIC`).

## Prerequisites

- Docker with Compose v2
- `curl`
- `jq`
- Node.js 18 or newer, only for the Codex stdio proxy

## Quick Start With Claude Code

Five steps from a clean checkout to an agent calling generated tools.

**1. Start the stack.**

```bash
cd demos/aperture-mcp-demo
docker compose up -d --build
```

**2. Mint an agent API key.** The raw key is returned only once.

```bash
export TOKEN=$(curl -s -X POST http://localhost:8082/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"agent-admin@mcp-demo.local","password":"changeme-local-only"}' \
  | jq -r .accessToken)

export APERTURE_API_KEY=$(curl -s -X POST http://localhost:8082/auth/me/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Local MCP agent",
    "domainRoles": ["Admin"],
    "securityAttributes": {}
  }' | jq -r .secret)

printf '%s\n' "$APERTURE_API_KEY"
```

**3. Paste the key into `.mcp.json`,** replacing `REPLACE_WITH_APERTURE_API_KEY`:

```json
{
  "mcpServers": {
    "aperture-mcp-demo": {
      "type": "http",
      "url": "http://localhost:8082/mcp",
      "headers": {
        "X-API-Key": "REPLACE_WITH_APERTURE_API_KEY"
      }
    }
  }
}
```

`.mcp.json` is checked in with the placeholder. Do not commit your real key.

**4. Launch Claude Code from this directory** so it picks up `.mcp.json`:

```bash
claude
```

**5. Confirm the connection and try a tool.** Run `/mcp` inside Claude Code and
check that `aperture-mcp-demo` is connected, then ask:

```text
List the projects available from the Aperture MCP demo.
```

For Codex, Gemini CLI, or Antigravity CLI, see
[Other MCP Clients](#other-mcp-clients) below.

## Stack Details

```bash
docker compose ps --all
```

Expected state:

- `api-server` is `healthy`
- `seeder` exits with code `0`
- `docker compose logs seeder` includes `mcp demo seed complete`

The seeder logs in as the bootstrap superadmin, creates one project, and
creates three tasks linked to it. It is idempotent, so rerunning it reuses the
same records instead of creating duplicates.

If the stack is already running from another checkout, recreate the API
container so the latest bootstrap and generated classes are loaded:

```bash
docker compose up -d --build api-server
```

## Credentials

The demo creates two local users on first startup:

| User | Purpose | Password |
|---|---|---|
| `superadmin@framework.local` | Bootstrap and broad local inspection | `changeme-local-only` |
| `agent-admin@mcp-demo.local` | API-key owner for local MCP clients | `changeme-local-only` |

Override the shared password with `APERTURE_BOOTSTRAP_ADMIN_PASSWORD`.

Keep the API key from step 2 in `APERTURE_API_KEY` for curl checks and Codex,
and paste it into client config files that use the
`REPLACE_WITH_APERTURE_API_KEY` placeholder.

## Other MCP Clients

All templates point at `http://localhost:8082/mcp`.

| Client | Config | API-key setup | Start command |
|---|---|---|---|
| Claude Code | `.mcp.json` | Replace `REPLACE_WITH_APERTURE_API_KEY` | `cd demos/aperture-mcp-demo && claude` |
| Codex | `.codex/config.toml` plus `scripts/codex-mcp-http-proxy.mjs` | Export `APERTURE_API_KEY` in the same shell | `cd demos/aperture-mcp-demo && codex` |
| Gemini CLI | `.gemini/settings.json` | Replace `REPLACE_WITH_APERTURE_API_KEY` | `cd demos/aperture-mcp-demo && gemini` |
| Antigravity CLI | Global or plugin `mcp_config.json` | Add the server block below | `cd demos/aperture-mcp-demo && agy` |

Codex uses the local Node proxy because its project config launches MCP
servers through stdio. The proxy forwards to the local HTTP endpoint and adds
`X-API-Key` from the environment.

Antigravity config:

```json
{
  "mcpServers": {
    "aperture-mcp-demo": {
      "serverUrl": "http://localhost:8082/mcp",
      "headers": {
        "X-API-Key": "REPLACE_WITH_APERTURE_API_KEY"
      }
    }
  }
}
```

After launching a client, use that client's MCP status command, such as
`/mcp`, to confirm `aperture-mcp-demo` is connected.

## Try It From An Agent

```text
List the projects available from the Aperture MCP demo.
```

```text
List the seeded tasks from the Aperture MCP demo and summarize their status.
```

```text
Create a project named "Agent-created project" with the description "Created through the generated Aperture MCP tool."
```

```text
Create a new task named "Learn MCP integration" in an existing project.
```

## Verify With Curl

Check seeded JSON:API data:

```bash
curl -s http://localhost:8082/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].attributes.name'

curl -s http://localhost:8082/api/v1/tasks \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].attributes.title'
```

List generated MCP tools:

```bash
curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $APERTURE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}' \
  | jq '.result.tools[].name'
```

Expected tool names:

```text
list_projects
get_project
create_project
update_project
delete_project
list_tasks
get_task
create_task
update_task
delete_task
```

Call a read tool:

```bash
curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $APERTURE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "id": 2,
    "params": {
      "name": "list_tasks",
      "arguments": {}
    }
  }' | jq .
```

Call a write tool:

```bash
curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $APERTURE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "id": 3,
    "params": {
      "name": "create_project",
      "arguments": {
        "name": "Created from MCP",
        "description": "This project was created by a generated MCP tool."
      }
    }
  }' | jq .
```

Create a task with a relationship to an existing project. `project_id` is the
raw-id form of Task's `ManyToOne` `project` field:

```bash
export PROJECT_ID=$(curl -s http://localhost:8082/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" | jq -r '.data[0].id')

curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $APERTURE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d "{
    \"jsonrpc\": \"2.0\",
    \"method\": \"tools/call\",
    \"id\": 4,
    \"params\": {
      \"name\": \"create_task\",
      \"arguments\": {
        \"title\": \"Task created from MCP\",
        \"project_id\": \"$PROJECT_ID\"
      }
    }
  }" | jq .
```

## Principal-Scoped Tool Lists (RBAC)

`tools/list` is scoped to the calling principal's roles (plan 016 phase 2): an
`Admin` key sees all ten generated tools, `Assistant` sees everything except
`delete_project`/`delete_task`, and `ReadOnly` sees only the four read tools.
This is presentation only — it shapes what an agent is shown, not what it can
do. `tools/call` is still authorized for real by Elide on every invocation,
regardless of what `tools/list` returned, so this can never let a call through
that Elide would otherwise reject.

Mint a second API key scoped to `ReadOnly` (reusing `$TOKEN` from step 2) and
compare its `tools/list` response to the `Admin` key's:

```bash
export READONLY_API_KEY=$(curl -s -X POST http://localhost:8082/auth/me/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Local ReadOnly demo key",
    "domainRoles": ["ReadOnly"],
    "securityAttributes": {}
  }' | jq -r .secret)

echo "--- Admin key sees (expect 10 tools) ---"
curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $APERTURE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}' \
  | jq '.result.tools[].name'

echo "--- ReadOnly key sees (expect 4 tools) ---"
curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $READONLY_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}' \
  | jq '.result.tools[].name'
```

Expected `ReadOnly` tool names:

```text
list_projects
get_project
list_tasks
get_task
```

Prove `tools/call` is unaffected by which key you used to *list* tools — a
call is authorized independently, every time, by Elide:

```bash
curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $READONLY_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"list_tasks","arguments":{}}}' \
  | jq .
```

To restore the pre-phase-2 behavior (every tool listed to every caller
regardless of role), set `aperture.mcp.tool-list-scope: STATIC` — see
[the configuration reference](/reference/configuration#mcp-aperture-mcp-spring-ai-mcp).

The Bruno collection's `04-mcp` folder has the same walkthrough as runnable
requests (`05-login-agent-admin`, `06-mint-readonly-api-key`,
`07-mcp-list-tools-readonly`).

## Troubleshooting

If MCP returns `401`, create a fresh API key and make sure the client is using
the new value. If a client connects but lists no tools, validate the same key
with the `tools/list` curl command above before debugging client-specific
config.

## Bruno Walkthrough

Import `api-collection/` into Bruno with the `local` environment
(`http://localhost:8082`). The collection covers:

1. Auth as the bootstrap superadmin
2. JSON:API project and task CRUD
3. MCP `initialize`
4. MCP `tools/list`
5. MCP `tools/call` for `list_tasks` and `create_project`
6. Principal-scoped `tools/list`: mint a `ReadOnly` API key and show it sees
   only the 4 read tools, versus the 10 the superadmin token sees
7. Cleanup

## Automated Verification

```bash
mise run test
mise run docker-deploy
./docker-smoke-test.sh
mise run docker-clear
```

`mise run test` runs `McpDemoComponentTest` with Testcontainers Postgres and
asserts the exact generated tool surface, including that an `Admin` API key
sees all ten tools and a `ReadOnly` key sees only the four read tools.
`docker-smoke-test.sh` is the black-box equivalent against the running Docker
Compose stack, including the same `ReadOnly`-key check.

## Stop

```bash
docker compose down -v
```
