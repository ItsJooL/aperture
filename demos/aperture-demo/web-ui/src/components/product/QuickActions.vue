<template>
  <div class="grid grid-3">
    <RouterLink v-for="action in actions" :key="action.to" :to="action.to" class="card card-pad workflow-card">
      <div>
        <span class="badge badge-neutral">{{ action.kicker }}</span>
        <h3 style="margin:.9rem 0 .35rem;">{{ action.title }}</h3>
        <p class="card-description">{{ action.description }}</p>
      </div>
      <strong class="inline-action">Open <ArrowRight class="nav-icon" aria-hidden="true" /></strong>
    </RouterLink>
  </div>
</template>

<script setup lang="ts">
import { ArrowRight } from '@lucide/vue'
const actions = [
  { to: '/customers', kicker: 'CRM', title: 'Manage customers', description: 'Search accounts, open profiles and add billing contacts.' },
  { to: '/products', kicker: 'Catalogue', title: 'Review products', description: 'Keep products, SKUs and prices aligned before invoicing.' },
  { to: '/invoices/new', kicker: 'Billing', title: 'Create invoice', description: 'Select a customer, add product lines and issue a new invoice.' },
]
</script>
