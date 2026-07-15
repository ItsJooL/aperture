---
title: MCP Demo
description: A small no-tenancy Aperture deployment focused on generated MCP tools.
---

# MCP Demo

The MCP demo is a focused proving ground for generated Model Context Protocol
tools. It runs a no-tenancy Aperture API with two entities:

| Entity | Purpose | MCP tools |
|---|---|---|
| `Project` | A small work container | list, get, create, update, delete |
| `Task` | Work items linked to a project | list, get, create, update, delete |

The demo includes a seeder that creates one project and three tasks, so MCP
clients have data to inspect immediately after startup. The demo directory
also includes local client templates for Claude Code, Codex, Gemini CLI, and
Antigravity CLI; see `demos/aperture-mcp-demo/README.md` for exact agent
setup steps.

## Running the demo

```bash
cd aperture/demos/aperture-mcp-demo
docker compose up -d --build
docker compose ps
```

Wait until `api-server` is healthy and `seeder` has exited successfully.

## Log in

```bash
export TOKEN=$(curl -s -X POST http://localhost:8082/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"agent-admin@mcp-demo.local","password":"changeme-local-only"}' \
  | jq -r .accessToken)

export APERTURE_API_KEY=$(curl -s -X POST http://localhost:8082/auth/me/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Docs MCP key","domainRoles":["Admin"],"securityAttributes":{}}' \
  | jq -r .secret)
```

## Check JSON:API data

```bash
curl -s http://localhost:8082/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].attributes.name'

curl -s http://localhost:8082/api/v1/tasks \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].attributes.title'
```

## List MCP tools

```bash
curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $APERTURE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}' \
  | jq '.result.tools[].name'
```

Expected tool names include all Project CRUD tools (`list_projects`, `get_project`,
`create_project`, `update_project`, `delete_project`) and all Task CRUD tools
(`list_tasks`, `get_task`, `create_task`, `update_task`, `delete_task`).

## Call an MCP tool

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

The MCP endpoint uses the same authentication and authorization path as the
JSON:API endpoints. Generated tools are a convenience layer over the same
domain model, permissions, and request handling.

## Principal-scoped tools/list

`tools/list` is scoped to the calling principal's roles (plan 016 phase 2): an
`Admin` key sees all ten generated tools, `Assistant` sees everything except
`delete_project`/`delete_task`, and `ReadOnly` sees only the four read tools.
This affects presentation only. It shapes what an agent is shown, not what it can
do. `tools/call` is still authorized for real by Elide on every invocation,
regardless of what `tools/list` returned, so this can never let a call through
that Elide would otherwise reject.

Mint a second API key scoped to `ReadOnly` (reusing `$TOKEN` from the login
step) and compare its `tools/list` response to the `Admin` key's:

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

Prove `tools/call` is unaffected by which key you used to *list* tools. A
call is authorized independently, every time, by Elide:

```bash
curl -s http://localhost:8082/mcp \
  -H "X-API-Key: $READONLY_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"list_tasks","arguments":{}}}' \
  | jq .
```

A tool being hidden from `tools/list` does not mean the underlying operation
is blocked if called directly. This filtering is presentation-only. Elide still
authorizes every `tools/call` invocation underneath, so a caller who
already knows a tool's name and arguments is authorized (or rejected)
exactly as if it had appeared in their `tools/list` response.

To restore the pre-phase-2 behavior (every tool listed to every caller
regardless of role), set `aperture.mcp.tool-list-scope: STATIC`. See
[the configuration reference](/reference/configuration#mcp-aperture-mcp-spring-ai-mcp).

The Bruno collection's `04-mcp` folder has the same walkthrough as runnable
requests (`05-login-agent-admin`, `06-mint-readonly-api-key`,
`07-mcp-list-tools-readonly`).

## Relationship inputs

Task's required relationship to Project is exposed as a raw `project_id` parameter on `create_task`
and `update_task`. Relationship fields are written through JSON:API `relationships`, not inline
attributes.
