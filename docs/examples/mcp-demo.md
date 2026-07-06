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
| `Task` | Work items linked to a project | list, get |

The demo includes a seeder that creates one project and three tasks, so MCP
clients have data to inspect immediately after startup.

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
  -d '{"username":"superadmin@framework.local","password":"changeme-local-only"}' \
  | jq -r .accessToken)
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
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}' \
  | jq '.result.tools[].name'
```

Expected tool names include `list_projects`, `create_project`, `list_tasks`,
and `get_task`.

## Call an MCP tool

```bash
curl -s http://localhost:8082/mcp \
  -H "Authorization: Bearer $TOKEN" \
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
