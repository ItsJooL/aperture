<template>
  <RouterView />
  <div class="toast-stack" aria-live="polite">
    <div v-for="toast in app.toasts" :key="toast.id" class="toast-card" :class="`toast-${toast.kind}`">
      <strong>{{ toast.title }}</strong>
      <p v-if="toast.message">{{ toast.message }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useAppStore } from '@/stores/appStore'

const app = useAppStore()
</script>
