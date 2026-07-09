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

## Relationship inputs

Task's required relationship to Project is exposed as a raw `project_id` parameter on `create_task`
and `update_task`. Relationship fields are written through JSON:API `relationships`, not inline
attributes.
