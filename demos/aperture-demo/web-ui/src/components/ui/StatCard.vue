<template>
  <section class="card card-pad">
    <div class="flex-between">
      <span class="badge badge-neutral">{{ label }}</span>
      <slot name="icon" />
    </div>
    <div class="stat-value">{{ value }}</div>
    <div class="stat-label">{{ detail }}</div>
  </section>
</template>

<script setup lang="ts">
defineProps<{ label: string; value: string | number; detail: string }>()
</script>
