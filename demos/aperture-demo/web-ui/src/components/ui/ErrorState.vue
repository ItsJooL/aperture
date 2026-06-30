<template>
  <div class="empty-state error-state" role="alert">
    <AlertTriangle class="empty-state-icon" aria-hidden="true" />
    <strong>{{ title }}</strong>
    <p style="margin: .35rem auto 1rem; max-width: 34rem;">{{ description }}</p>
    <slot />
  </div>
</template>

<script setup lang="ts">
import { AlertTriangle } from '@lucide/vue'
withDefaults(defineProps<{ title?: string; description?: string }>(), {
  title: 'Something went wrong',
  description: 'Refresh the page or try again in a moment.',
})
</script>
