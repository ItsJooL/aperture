<template>
  <section class="features">
    <p class="section-label">Features</p>
    <h2>Enterprise-ready. Declared, not hand-written.</h2>
    <p class="section-sub">Everything a production multi-tenant API needs, with a pluggable architecture so you own what matters.</p>

    <div class="trail" ref="trailEl">

      <FeatureRow icon="codegen">
        <template #title>Zero-boilerplate code generation</template>
        The Maven plugin generates all Spring entities, controllers, repositories, and auth filters from your manifests on every build. Regenerating from truth every time prevents drift and stale code.
      </FeatureRow>

      <FeatureRow icon="schema" :reverse="true">
        <template #title>Zero schema management</template>
        Aperture diffs your manifest against committed lock files and generates Liquibase migrations automatically. Add or rename a field, and the SQL writes itself. Drops are deferred so you never lose data accidentally.
      </FeatureRow>

      <FeatureRow icon="mcp">
        <template #title>MCP integration, AI-ready out of the box</template>
        Every entity gets Model Context Protocol tool stubs for list, get, create, update, and delete. AI assistants respect the same auth, tenancy, and permission rules as the REST API.
      </FeatureRow>

      <FeatureRow icon="jsonapi" :reverse="true">
        <template #title>JSON:API: the full protocol, not just the format</template>
        Atomic operations, sparse fieldsets, compound documents, RSQL filtering, sorting, and pagination come standard on every entity. The open standard answers the questions your team would otherwise argue about.
      </FeatureRow>

      <FeatureRow icon="graphql">
        <template #title>GraphQL, powered by Elide</template>
        GraphQL can query the same entity dictionary under the same permissions and manifests. Traverse an invoice, its customer, and every line item in one round trip instead of chaining REST calls.
      </FeatureRow>

      <FeatureRow icon="tenancy" :reverse="true">
        <template #title>Multi-tenancy out of the box</template>
        POOL mode adds tenant isolation at the database level, with every query auto-filtered and every FK constraint tenant-aware. NONE mode serves single-tenant deployments. Same codebase, different config.
      </FeatureRow>

      <FeatureRow icon="auth">
        <template #title>Pluggable auth and identity</template>
        JWT and API key auth built in. Implement one interface to swap in Keycloak, Okta, or any identity provider. Tenancy, RBAC, hooks, and audit stay completely unchanged.
      </FeatureRow>

      <FeatureRow icon="hooks" :reverse="true">
        <template #title>Four lifecycle hook types</template>
        <code>validate</code> blocks, <code>mutate</code> modifies, <code>trigger</code> fires async, <code>guard</code> runs pre-auth. You implement logic over HTTP while Aperture handles signing, retries, and timeouts.
      </FeatureRow>

      <FeatureRow icon="security">
        <template #title>RBAC + ABAC security model</template>
        Role-based permissions and SpEL attribute policies live in the manifest. Field encryption, rate limiting, optimistic locking, and a transactional audit trail are all included.
      </FeatureRow>

      <FeatureRow icon="cli" :reverse="true">
        <template #title>A CLI for your API, generated</template>
        A manifest-driven, kubectl-style CLI provides verb-first CRUD for every entity, declarative <code>apply</code>, config profiles, and shell completion. Ship it as a fat JAR or a ~30ms GraalVM native binary. Auth is pluggable too, with OIDC device-code login available out of the box.
      </FeatureRow>

      <!-- SVG overlay drawn after mount from measured icon positions -->
      <svg
        v-if="svgW > 0"
        class="trail-svg"
        :viewBox="`0 0 ${svgW} ${svgH}`"
        aria-hidden="true"
      >
        <path
          v-for="p in connectors"
          :key="p.key"
          :d="p.d"
          fill="none"
          stroke="var(--home-border)"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>

    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import FeatureRow from '../components/FeatureRow.vue'

const trailEl = ref<HTMLElement>()
const svgW    = ref(0)
const svgH    = ref(0)
const connectors = ref<{ key: number; d: string }[]>([])

function measure() {
  const trail = trailEl.value
  if (!trail) return
  const rect  = trail.getBoundingClientRect()
  svgW.value  = rect.width
  svgH.value  = rect.height

  const icons = trail.querySelectorAll<HTMLElement>('.fr-icon')
  const ps: { key: number; d: string }[] = []

  for (let i = 0; i < icons.length - 1; i++) {
    const a = icons[i].getBoundingClientRect()
    const b = icons[i + 1].getBoundingClientRect()
    const x1 = a.left + a.width  / 2 - rect.left
    const y1 = a.top  + a.height / 2 - rect.top   // centre of icon, not bottom
    const x2 = b.left + b.width  / 2 - rect.left
    const y2 = b.top  + b.height / 2 - rect.top
    ps.push({ key: i, d: elbowPath(x1, y1, x2, y2) })
  }
  connectors.value = ps
}

/** Squared-off Z-path connecting two icon centres with two 90° rounded corners. */
function elbowPath(x1: number, y1: number, x2: number, y2: number): string {
  const r   = 10
  const mid = (y1 + y2) / 2
  const dx  = x2 > x1 ? r : -r
  return [
    `M ${x1},${y1}`,
    `L ${x1},${mid - r}`,
    `Q ${x1},${mid} ${x1 + dx},${mid}`,
    `L ${x2 - dx},${mid}`,
    `Q ${x2},${mid} ${x2},${mid + r}`,
    `L ${x2},${y2}`,
  ].join(' ')
}

let ro: ResizeObserver | undefined

onMounted(() => {
  measure()
  ro = new ResizeObserver(measure)
  ro.observe(trailEl.value!)
})
onUnmounted(() => ro?.disconnect())
</script>

<style scoped>
.features {
  background: var(--home-surface);
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
  font-size: 15px; margin-bottom: 60px;
}

.trail {
  position: relative;
  max-width: 780px;
  margin: 0 auto;
  isolation: isolate;
}

.trail-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  overflow: visible;
  z-index: -1;
}
</style>
