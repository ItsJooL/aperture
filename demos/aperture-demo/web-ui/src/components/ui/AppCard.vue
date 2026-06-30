<template>
  <section class="card" :class="padded ? 'card-pad' : ''">
    <slot />
  </section>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ padded?: boolean }>(), { padded: true })
</script>
