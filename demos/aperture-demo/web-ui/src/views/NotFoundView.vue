<template>
  <section class="content-wrap">
    <EmptyState title="Page not found" description="The page you requested does not exist in this workspace.">
      <template #icon><FileQuestion class="empty-state-icon" aria-hidden="true" /></template>
      <RouterLink to="/dashboard" class="button button-primary">Back to dashboard</RouterLink>
    </EmptyState>
  </section>
</template>
<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { FileQuestion } from '@lucide/vue'
import EmptyState from '@/components/ui/EmptyState.vue'
</script>
