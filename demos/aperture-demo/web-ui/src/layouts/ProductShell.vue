<template>
  <div class="product-shell">
    <aside class="shell-sidebar" aria-label="Workspace navigation">
      <RouterLink to="/dashboard" class="brand-block shell-brand">
        <div class="brand-mark">A</div>
        <div>
          <strong>Aperture</strong>
          <div class="muted">Billing workspace</div>
        </div>
      </RouterLink>

      <nav class="shell-nav">
        <RouterLink
          v-for="item in visibleNavItems"
          :key="item.to"
          :to="item.to"
          class="shell-nav-link"
          :class="{ 'shell-nav-link-active': route.path === item.to || route.path.startsWith(`${item.to}/`) }"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
    </aside>

    <div class="demo-floating-panel" aria-label="Demo switcher">
      <div class="demo-switcher">
        <label>
          <span>Persona</span>
          <select aria-label="Demo persona" :value="activePersonaId" :disabled="switching" @change="switchPersona(($event.target as HTMLSelectElement).value)">
            <option v-for="persona in demoPersonas" :key="persona.id" :value="persona.id">
              {{ persona.label }}
            </option>
          </select>
        </label>
        <label>
          <span>Tenant</span>
          <select aria-label="Demo tenant" :value="activeTenantName" :disabled="switching" @change="switchTenant(($event.target as HTMLSelectElement).value)">
            <option v-for="tenant in demoTenants" :key="tenant" :value="tenant">
              {{ tenant }}
            </option>
          </select>
        </label>
      </div>
      <div class="floating-account">
        <strong>{{ auth.user?.username || 'Signed in' }}</strong>
        <span>{{ roleSummary }}</span>
      </div>
      <AppButton variant="outline" size="sm" @click="signOut">Sign out</AppButton>
    </div>

    <main class="shell-main">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
import { defaultPersonaForTenant, demoPersonas, demoTenants, demoTenantIds, personaById, personaForUsername } from '@/data/demoPersonas'
import { extractRoleNames, type Permission } from '@/stores/permissions'
import { useAuthStore } from '@/stores/authStore'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const switching = ref(false)

const navItems: Array<{ label: string; to: string; permission?: Permission; admin?: boolean }> = [
  { label: 'Dashboard', to: '/dashboard', permission: 'workspace:view' },
  { label: 'Customers', to: '/customers', permission: 'customers:read' },
  { label: 'Products', to: '/products', permission: 'products:read' },
  { label: 'Invoices', to: '/invoices', permission: 'invoices:read' },
  { label: 'Payments', to: '/payments', permission: 'payments:read' },
  { label: 'Suppliers', to: '/suppliers', permission: 'suppliers:read' },
  { label: 'Admin', to: '/admin', permission: 'admin:view', admin: true },
  { label: 'Settings', to: '/settings' },
]

const visibleNavItems = computed(() => navItems.filter((item) => {
  if (!auth.tenantId && item.permission && item.permission !== 'admin:view') return false
  if (item.admin && !auth.canAccessAdmin) return false
  return !item.permission || auth.hasPermission(item.permission)
}))

const roleSummary = computed(() => extractRoleNames(auth.user).join(', ') || 'Workspace member')
const activePersona = computed(() => personaForUsername(auth.user?.username) ?? demoPersonas.find((persona) => persona.username === auth.user?.attributes?.username))
const activePersonaId = computed(() => activePersona.value?.id ?? '')
const activeTenantName = computed(() => {
  if (auth.currentTenantContext) {
    const entry = Object.entries(demoTenantIds).find(([k, v]) => v === auth.currentTenantContext)
    return entry ? entry[0] : 'Framework'
  }
  return activePersona.value?.tenant ?? (auth.tenantId ? 'Current tenant' : 'Framework')
})

async function switchTo(personaId: string) {
  const persona = personaById(personaId)
  if (!persona || switching.value) return
  switching.value = true
  try {
    await auth.logout()
    await auth.login(persona.username, persona.password)
    await router.push('/dashboard')
  } finally {
    switching.value = false
  }
}

async function switchPersona(personaId: string) {
  await switchTo(personaId)
}

async function switchTenant(tenant: string) {
  if (extractRoleNames(auth.user).includes('SuperAdmin')) {
    auth.setActiveTenantContext(demoTenantIds[tenant] ?? null)
    window.location.reload()
  } else {
    const persona = defaultPersonaForTenant(tenant)
    if (persona) await switchTo(persona.id)
  }
}

async function signOut() {
  await auth.logout()
  await router.push('/login')
}
</script>
