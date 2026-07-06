# Aperture MCP Demo

## What this demo proves

This demo runs a small no-tenancy Aperture API with MCP enabled. It exposes two
entities, `Project` and `Task`, so an MCP client can list seeded relational data
and create simple domain records through generated tools — with **zero
hand-written MCP code**. Every tool is generated from the manifests under
`manifests/domain/` by the `mcp-java` generation target
(`core/aperture-generation/.../McpJavaGenerationTarget`), which turns each
entity's `spec.mcp` block into a `Tool`-annotated Spring AI class.

The two entities are deliberately configured differently to show that the
tool surface is manifest-driven, not hardcoded:

| Entity | `spec.mcp` in the manifest | Generated MCP tools |
|---|---|---|
| `Project` | no entity-level override — inherits the framework default (`manifests/framework/config.yaml`) | `list_projects`, `get_project`, `create_project`, `update_project`, `delete_project` |
| `Task` | `enabled: true`, `tools: [list, get]` | `list_tasks`, `get_task` only |

The MCP endpoint (`/mcp`) enforces the same JWT auth and RBAC permissions as
the JSON:API endpoints (`/api/v1/...`) — MCP tools are a convenience layer
over the same domain model, not a separate security surface.

## Credentials

The bootstrap superadmin is created on first startup by `McpDemoBootstrap`:

| Field | Value |
|---|---|
| Username | `superadmin@framework.local` |
| Password | `changeme-local-only` (override with `APERTURE_BOOTSTRAP_ADMIN_PASSWORD`) |

## Run

```bash
cd demos/aperture-mcp-demo
docker compose up -d --build
docker compose ps
```

Wait until `api-server` is healthy and `seeder` has exited successfully. The
seeder logs in as the bootstrap superadmin, creates one project, and creates
three tasks linked to it.

## Login

```bash
export TOKEN=$(curl -s -X POST http://localhost:8082/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"superadmin@framework.local","password":"changeme-local-only"}' \
  | jq -r .accessToken)
```

## Check Seeded JSON:API Data

```bash
curl -s http://localhost:8082/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].attributes.name'

curl -s http://localhost:8082/api/v1/tasks \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].attributes.title'
```

## List MCP Tools

```bash
curl -s http://localhost:8082/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}' \
  | jq '.result.tools[].name'
```

Expected tool names include:

```text
list_projects
get_project
create_project
update_project
delete_project
list_tasks
get_task
```

## Call An MCP Tool

List tasks through MCP:

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

Create a project through MCP:

```bash
curl -s http://localhost:8082/mcp \
  -H "Authorization: Bearer $TOKEN" \
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

## Bruno API Collection

Import `api-collection/` into [Bruno](https://usebruno.com) (environment:
`local`, base URL `http://localhost:8082`) for a click-through walkthrough:

1. **Auth** — log in as the superadmin.
2. **Projects** — create, list, get, and update a project over JSON:API.
3. **Tasks** — create a task linked to that project, list, and get it.
4. **MCP** — `initialize`, `tools/list` (confirm the Project/Task tool split
   above), `tools/call list_tasks`, and `tools/call create_project`.
5. **Cleanup** — delete the task, then the project.

## Automated Verification

```bash
mise run test           # Maven component test (McpDemoComponentTest) against
                         # a Testcontainers Postgres — exercises bootstrap,
                         # JSON:API CRUD, and the MCP tool surface directly.
mise run docker-deploy  # brings up the full docker compose stack
./docker-smoke-test.sh  # exercises the running stack: bootstrap, seeded
                         # data, the MCP tool surface, and auth enforcement
mise run docker-clear   # tears it down
```

`McpDemoComponentTest` (`src/test/java/.../McpDemoComponentTest.java`) is the
source of truth for the tool-name table above — it asserts the exact set of
generated MCP tools for both entities and that a tool call round-trips
through to the JSON:API layer. `docker-smoke-test.sh` is the black-box
equivalent against the real Docker Compose stack.

## Stop

```bash
docker compose down -v
```
