<template>
  <span class="badge" :class="`badge-${variant}`"><slot /></span>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ variant?: 'neutral' | 'success' | 'warning' | 'danger' | 'info' }>(), { variant: 'neutral' })
</script>
