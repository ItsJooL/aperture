<template>
  <div>
    <button class="global-search-button" @click="open = true">
      <Search class="nav-icon" />
      <span>Search workspace</span>
      <kbd>/</kbd>
    </button>
    <AppModal :open="open" title="Search workspace" description="Jump to common customer, product and billing tasks." @close="open = false">
      <div class="stack">
        <AppInput v-model="term" label="Search" placeholder="Try customer, invoice, product…" />
        <div class="command-list">
          <RouterLink
            v-for="item in filtered"
            :key="item.to"
            :to="item.to"
            class="command-item"
            @click="open = false"
          >
            <component :is="item.icon" class="nav-icon" />
            <div>
              <strong>{{ item.label }}</strong>
              <p class="muted">{{ item.description }}</p>
            </div>
          </RouterLink>
        </div>
      </div>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { CreditCard, FilePlus2, FileText, Package, Search, Settings, UsersRound } from '@lucide/vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppModal from '@/components/ui/AppModal.vue'

const open = ref(false)
const term = ref('')
const items = [
  { label: 'Customers', description: 'Find or create customer accounts', to: '/customers', icon: UsersRound },
  { label: 'Products', description: 'Manage catalogue and pricing', to: '/products', icon: Package },
  { label: 'Invoices', description: 'Review billing status and receivables', to: '/invoices', icon: FileText },
  { label: 'Create invoice', description: 'Build and review a new invoice', to: '/invoices/new', icon: FilePlus2 },
  { label: 'Payments', description: 'Check payments and recent collections', to: '/payments', icon: CreditCard },
  { label: 'Settings', description: 'Profile, security and appearance', to: '/settings', icon: Settings },
]
const filtered = computed(() => {
  const q = term.value.trim().toLowerCase()
  if (!q) return items
  return items.filter((item) => `${item.label} ${item.description}`.toLowerCase().includes(q))
})
function onKeydown(event: KeyboardEvent) {
  if (event.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes((event.target as HTMLElement)?.tagName)) {
    event.preventDefault()
    open.value = true
  }
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>
