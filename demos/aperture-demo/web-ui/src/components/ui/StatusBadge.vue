<template>
  <AppBadge :variant="variant">
    <span class="status-dot"></span>
    {{ label }}
  </AppBadge>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppBadge from './AppBadge.vue'

const props = defineProps<{ status?: string }>()
const label = computed(() => props.status ? props.status.replaceAll('_', ' ') : 'unknown')
const variant = computed(() => {
  const status = props.status?.toLowerCase()
  if (['active', 'paid', 'issued', 'accepted', 'complete'].includes(status ?? '')) return 'success'
  if (['draft', 'pending', 'invited'].includes(status ?? '')) return 'warning'
  if (['disabled', 'deleted', 'overdue', 'failed'].includes(status ?? '')) return 'danger'
  return 'neutral'
})
</script>
