<template>
  <header class="page-header page-header-enhanced">
    <div>
      <Breadcrumbs v-if="breadcrumbs?.length" :items="breadcrumbs" />
      <p v-if="kicker" class="page-kicker">{{ kicker }}</p>
      <h1 class="page-title"><slot name="title">{{ title }}</slot></h1>
      <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="page-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
import Breadcrumbs from './Breadcrumbs.vue'

defineProps<{
  kicker?: string
  title?: string
  subtitle?: string
  breadcrumbs?: Array<{ label: string; to?: string }>
}>()
</script>
