<template>
  <section class="matrix">
    <p class="section-label">Complete feature set</p>
    <h2>Every feature, at a glance</h2>
    <p class="section-sub">The curated tour above is the highlight reel. This is the whole inventory — grouped, linked, and ready to scan.</p>

    <div class="matrix-frame">
      <div class="matrix-scroll">
        <table>
          <thead>
            <tr>
              <th class="col-feature">Feature</th>
              <th class="col-desc">What it does</th>
              <th class="col-link">Docs</th>
            </tr>
          </thead>
          <tbody v-for="cat in categories" :key="cat.name">
            <tr class="cat-row">
              <td colspan="3">{{ cat.name }}</td>
            </tr>
            <tr v-for="row in cat.rows" :key="row.name">
              <td class="col-feature">{{ row.name }}</td>
              <td class="col-desc">{{ row.desc }}</td>
              <td class="col-link">
                <a :href="row.href">Read more <span aria-hidden>→</span></a>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
interface Row { name: string; desc: string; href: string }
interface Category { name: string; rows: Row[] }

const categories: Category[] = [
  {
    name: 'API Surface',
    rows: [
      { name: 'JSON:API', desc: 'Atomic operations, sparse fieldsets, compound documents, RSQL filtering, sorting, pagination.', href: '/reference/rest-api#json-api-features' },
      { name: 'GraphQL (Elide)', desc: 'Same entities, same permissions, same manifests — queries, nested traversal, and mutations at /graphql/v{n}.', href: '/reference/configuration#graphql-elide-graphql' },
      { name: 'OpenAPI / Swagger', desc: 'Full spec generated from your manifests, served at /swagger-ui.html.', href: '/reference/configuration#openapi-swagger' },
      { name: 'API versioning', desc: 'ACTIVE / SUNSET lifecycle per version, with deprecation headers.', href: '/guide/build-deploy#api-versioning' },
      { name: 'MCP server', desc: 'list / get / create / update / delete tool stubs for AI assistants.', href: '/reference/configuration#mcp-aperture-mcp-spring-ai-mcp' },
    ],
  },
  {
    name: 'Data Model',
    rows: [
      { name: 'Entities & relationships', desc: 'ManyToOne / OneToMany, with mappedBy for the inverse side.', href: '/reference/manifest-schema#spec-fields' },
      { name: 'Unique & indexed fields', desc: 'Declared per field — generates unique or non-unique DB indexes.', href: '/reference/manifest-schema#spec-fields' },
      { name: 'Optimistic locking', desc: 'Adds a version column, enforced via If-Match on mutations.', href: '/guide/security-audit#optimistic-locking' },
      { name: 'Soft delete', desc: 'Adds deleted_at; reads are auto-filtered to live rows.', href: '/reference/manifest-schema#boolean-spec-fields' },
      { name: 'Scope partitioning (scopedBy)', desc: 'Partitions rows by a relationship, selected per request via header. Partitioning, not authorization — pair with ABAC to gate access.', href: '/guide/manifests#scoping-by-relationship-scopedby' },
      { name: 'Field encryption', desc: 'AES-256-GCM ciphertext at rest, transparent to API callers.', href: '/guide/security-audit#field-level-encryption' },
      { name: 'Liquibase migrations', desc: 'Full-DDL and incremental changesets generated on every build.', href: '/guide/build-deploy#schema-automation' },
      { name: 'Manifest diffing', desc: 'Breaking-change detection against committed lock files.', href: '/guide/core-concepts#the-build-pipeline' },
    ],
  },
  {
    name: 'Security',
    rows: [
      { name: 'JWT + API keys', desc: 'Login, refresh, service accounts, and personal API keys.', href: '/guide/auth#built-in-auth-endpoints' },
      { name: 'Pluggable identity', desc: 'Swap providers behind the CredentialValidator SPI.', href: '/guide/auth' },
      { name: 'RBAC', desc: 'Role-based permissions declared per entity and operation.', href: '/guide/security-audit#role-based-access-control-rbac' },
      { name: 'ABAC', desc: 'SpEL attribute policies for fine-grained, contextual rules.', href: '/guide/security-audit#attribute-based-access-control-abac' },
      { name: 'Rate limiting', desc: 'Three independent token buckets keyed by IP, user, and tenant — configurable, with a pluggable in-memory or Valkey-backed provider.', href: '/guide/security-audit#rate-limiting' },
      { name: 'Audit trail', desc: 'Transactional log of every mutation, tied to the request.', href: '/guide/security-audit#the-audit-trail' },
      { name: 'Bootstrap admin (demo)', desc: 'Demo-only today: aperture-demo seeds a superadmin from an env var on first boot — not yet a general framework feature.', href: '/reference/configuration#bootstrap-admin' },
    ],
  },
  {
    name: 'Tenancy',
    rows: [
      { name: 'POOL mode', desc: 'Auto-filtered queries and tenant-aware foreign keys.', href: '/guide/multi-tenancy#pool-mode-in-depth' },
      { name: 'NONE mode', desc: 'Single-tenant deployments — same codebase, different config.', href: '/guide/multi-tenancy#none-mode-in-depth' },
      { name: 'Tenant lifecycle', desc: 'Provisioning, tenant admins, and the invite flow.', href: '/guide/multi-tenancy#tenant-provisioning' },
    ],
  },
  {
    name: 'Lifecycle Hooks',
    rows: [
      { name: 'Guard hooks', desc: 'Pre-auth veto before a request is even processed.', href: '/guide/hooks#guard-hooks' },
      { name: 'Validation hooks', desc: 'Synchronous block-or-allow check before commit.', href: '/guide/hooks#validation-hooks' },
      { name: 'Mutation hooks', desc: 'Rewrite the payload before it is persisted.', href: '/guide/hooks#mutation-hooks' },
      { name: 'Trigger hooks', desc: 'Fire-and-forget async side effects after commit.', href: '/guide/hooks#trigger-hooks' },
      { name: 'Delivery guarantees', desc: 'Signed payloads, retries, and timeouts, handled for you.', href: '/guide/hooks#retries-and-timeouts' },
    ],
  },
  {
    name: 'Generated CLI',
    rows: [
      { name: 'Verb-first entity commands', desc: 'kubectl-style get / create / update / delete for every entity, with JSON:API query options.', href: '/guide/cli#what-the-cli-generates' },
      { name: 'Declarative apply', desc: 'Create resources from YAML via apply or -f on the verbs; --atomic batches all-or-nothing.', href: '/guide/cli#declarative-apply' },
      { name: 'Profiles & contexts', desc: 'Per-user config profiles with server, tenant, API version, and sticky scope context.', href: '/guide/cli#configuration-profiles' },
      { name: 'Scope context', desc: '--scope and config set-scope layer scopedBy headers, kubectl-namespace-style.', href: '/guide/cli#scope-context-scopedby' },
      { name: 'Shell completion', desc: 'Generated completion script, regenerated as manifests change.', href: '/guide/cli#shell-completion' },
      { name: 'API keys & tokens', desc: 'Create and store personal API keys; service-account tokens via auth token.', href: '/guide/cli#api-keys' },
      { name: 'Fat JAR or native binary', desc: 'Any JDK builds the JAR; GraalVM builds a ~30 ms native binary.', href: '/guide/cli#fat-jar-vs-native-binary' },
      { name: 'Two extension SPIs', desc: 'CliAuthExtension (two-tier auth) and CliCommandContribution (custom commands), both source-emitting.', href: '/guide/cli#custom-auth-extensions' },
      { name: 'OIDC device-code login', desc: 'RFC 8628 device flow via the aperture-cli-auth-oidc extension.', href: '/guide/cli#full-auth-command-override' },
    ],
  },
  {
    name: 'Operations & Tooling',
    rows: [
      { name: 'Maven plugin', desc: 'Codegen and migrations wired into the build lifecycle.', href: '/guide/build-deploy#the-maven-plugin' },
      { name: 'Docker Compose', desc: 'Demo-ready stack with Postgres and Jaeger.', href: '/guide/build-deploy#docker-deployment' },
      { name: 'Database seeder', desc: 'Seeds demo tenants, users, and data, then exits.', href: '/guide/quick-start#start-the-demo' },
      { name: 'Distributed tracing', desc: 'Jaeger wired into the demo stack out of the box.', href: '/examples/billing-demo#_9-distributed-tracing' },
    ],
  },
]
</script>

<style scoped>
.matrix {
  background: var(--home-bg);
  border-top: 1px solid var(--home-border);
  padding: 88px 48px;
}
.section-label {
  text-align: center; color: var(--home-accent);
  font-size: 12px; font-weight: 700; letter-spacing: 1.2px;
  text-transform: uppercase; margin-bottom: 10px;
}
h2 {
  font-size: 34px; font-weight: 800; letter-spacing: -1px;
  text-align: center; color: var(--home-text); margin-bottom: 10px;
}
.section-sub {
  text-align: center; color: var(--home-muted);
  font-size: 15px; margin-bottom: 48px;
}

.matrix-frame {
  max-width: 980px;
  margin: 0 auto;
  background: var(--home-surface);
  border: 1px solid var(--home-border);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.06);
}
.matrix-scroll {
  overflow-x: auto;
}

table {
  width: 100%;
  min-width: 600px;
  border-collapse: collapse;
  font-size: 13.5px;
}

thead th {
  text-align: left;
  font-size: 11px; font-weight: 700; letter-spacing: .6px; text-transform: uppercase;
  color: var(--home-muted);
  background: var(--home-surface-2);
  padding: 12px 20px;
  border-bottom: 1px solid var(--home-border);
  white-space: nowrap;
}

.cat-row td {
  background: var(--home-surface-2);
  color: var(--home-accent);
  font-size: 11px; font-weight: 700; letter-spacing: .8px; text-transform: uppercase;
  padding: 9px 20px;
  border-top: 1px solid var(--home-border);
  border-bottom: 1px solid var(--home-border);
}

tbody tr:not(.cat-row) {
  border-bottom: 1px solid var(--home-border);
  transition: background .15s;
}
tbody tr:not(.cat-row):last-child { border-bottom: none; }
tbody tr:not(.cat-row):hover { background: var(--home-surface-2); }

td {
  padding: 13px 20px;
  vertical-align: top;
  color: var(--home-text-2);
}
.col-feature {
  font-weight: 700;
  color: var(--home-text);
  white-space: nowrap;
}
.col-desc { color: var(--home-text-2); line-height: 1.5; }
.col-link { white-space: nowrap; text-align: right; }
.col-link a {
  color: var(--home-accent);
  font-size: 12.5px; font-weight: 600;
  text-decoration: none;
}
.col-link a:hover { text-decoration: underline; }

@media (max-width: 640px) {
  .matrix { padding: 64px 20px; }
  h2 { font-size: 26px; }
  table { min-width: 540px; }
}
</style>
