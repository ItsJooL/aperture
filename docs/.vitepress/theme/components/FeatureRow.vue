<template>
  <div class="feature-row" :class="{ rev: reverse }">
    <div class="fr-icon">
      <CodegenIcon  v-if="icon === 'codegen'"  />
      <SchemaIcon   v-if="icon === 'schema'"   />
      <JsonApiIcon  v-if="icon === 'jsonapi'"  />
      <TenancyIcon  v-if="icon === 'tenancy'"  />
      <AuthIcon     v-if="icon === 'auth'"     />
      <HooksIcon    v-if="icon === 'hooks'"    />
      <SecurityIcon v-if="icon === 'security'" />
      <McpIcon      v-if="icon === 'mcp'"      />
      <GraphQLIcon  v-if="icon === 'graphql'"  />
      <CliIcon      v-if="icon === 'cli'"      />
    </div>
    <div class="fr-text">
      <h3><slot name="title" /></h3>
      <p><slot /></p>
    </div>
  </div>
</template>

<script setup lang="ts">
import CodegenIcon  from '../icons/CodegenIcon.vue'
import SchemaIcon   from '../icons/SchemaIcon.vue'
import JsonApiIcon  from '../icons/JsonApiIcon.vue'
import TenancyIcon  from '../icons/TenancyIcon.vue'
import AuthIcon     from '../icons/AuthIcon.vue'
import HooksIcon    from '../icons/HooksIcon.vue'
import SecurityIcon from '../icons/SecurityIcon.vue'
import McpIcon      from '../icons/McpIcon.vue'
import GraphQLIcon  from '../icons/GraphQLIcon.vue'
import CliIcon      from '../icons/CliIcon.vue'

defineProps<{ icon: string; reverse?: boolean }>()
</script>

<style scoped>
.feature-row {
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: 28px;
  align-items: center;
  padding: 20px 0;
}
.feature-row.rev { grid-template-columns: 1fr 80px; }
.feature-row.rev .fr-icon { order: 2; }
.feature-row.rev .fr-text { order: 1; text-align: right; }

.fr-icon {
  width: 80px; height: 80px; flex-shrink: 0;
  background: var(--home-surface-2);
  border: 1px solid var(--home-border);
  border-radius: 16px;
  display: flex; align-items: center; justify-content: center;
  color: var(--home-accent);
}
.fr-icon svg { width: 32px; height: 32px; }

h3 { font-size: 16px; font-weight: 700; margin-bottom: 6px; letter-spacing: -0.2px; color: var(--home-text); }
p  { color: var(--home-muted); font-size: 13.5px; line-height: 1.6; margin: 0; }
p :deep(code) {
  font-family: monospace; font-size: 12px;
  background: var(--home-surface-2); padding: 1px 5px; border-radius: 3px;
}
</style>
