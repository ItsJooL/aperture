<template>
  <div class="empty-state">
    <slot name="icon">
      <Info class="empty-state-icon" aria-hidden="true" />
    </slot>
    <strong>{{ title }}</strong>
    <p style="margin: .35rem auto 1rem; max-width: 34rem;">{{ description }}</p>
    <slot />
  </div>
</template>

<script setup lang="ts">
import { Info } from '@lucide/vue'

defineProps<{ title: string; description: string }>()
</script>
