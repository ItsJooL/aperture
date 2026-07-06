# 12 — Scoped Reads (`scopedBy`)

`manifests/domain/work/task.yaml` declares `scopedBy: project`: every
operation on `Task` is **fail-closed** on the `X-Aperture-Scope-Project`
header, but the *shape* of the failure differs by operation. A **collection
read** without the header returns `200` with an **empty page** — Elide compiles
the scope filter into a SQL `WHERE` predicate, so no header means no matching
rows, regardless of role (`04`). **Single-object evaluation** — a create/update
response read-back, or a mismatch — is where a `403`/`400` surfaces (see below).
Sending the header admits only rows whose `project` relationship matches the
value (`05`). SuperAdmin bypasses the filter entirely (`06`), same as it
bypasses tenant scoping. See `core/aperture-core-engine/.../CodeGenerator
.generateScopeFilterCheck` for the generated `FilterExpressionCheck` and
`AuthFilter` for how the header populates `ScopeContextHolder`.

**This is partitioning, not authorization.** Any authenticated caller may name
any project id in the header — there's no check that the caller "owns" or is
otherwise entitled to that project, only that the header is present and the
row matches it. If you need real per-scope access control, pair `scopedBy`
with an ABAC policy that inspects the caller's own attributes (see
`docs/guide/manifests.md`).

**Writes need scope context too — including create.** `scopedBy` never injects
the relationship for you (`CodeGenerator.generateScopeValidationHook` only
validates), but that does not mean an unscoped create works: the JSON:API
response echoes the created `Task` back, and that read-back goes through the
same fail-closed filter as `04`, so a create with no scope header at all comes
back 403 (verified live via `cli-smoke-test.sh`). `03` therefore sends
`X-Aperture-Scope-Project` matching the relationship. If the header *is*
present but the incoming relationship names a different project, the write is
rejected with 400 before anything is persisted (`07`).

## Running this folder

Requests `01`–`03` set up fixtures (two Projects, one Task) the rest of the
folder depends on; run the collection in order, same as every other numbered
folder here. `01-auth` and `02-tenants` must have already run so
`{{tenantAdminToken}}`, `{{superadminToken}}`, and `{{tenantId}}` are
populated.

## CLI equivalent

```bash
$CLI --server http://localhost:8080 get tasks                                   # → 200, empty page (no scope header)
$CLI --server http://localhost:8080 get tasks --scope project=<projectId>       # → 200, rows
$CLI --server http://localhost:8080 config set-scope project <projectId>
$CLI --server http://localhost:8080 get tasks                                   # → 200, sticky scope, rows
$CLI --server http://localhost:8080 config unset-scope project
$CLI --server http://localhost:8080 get tasks                                   # → 200, empty page again
```

See `../10-cli/README.md` for the rest of the CLI walkthrough and
`demos/aperture-demo/cli-smoke-test.sh` for this exact sequence scripted
end-to-end against seeded data.
