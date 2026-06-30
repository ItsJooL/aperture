<template>
  <component
    :is="as"
    class="button"
    :class="[`button-${variant}`, `button-${size}`, { 'button-full': fullWidth }]"
    :type="as === 'button' ? type : undefined"
    :disabled="disabled || loading"
    :aria-busy="loading ? 'true' : undefined"
    :aria-disabled="disabled || loading ? 'true' : undefined"
  >
    <LoaderCircle v-if="loading" class="button-icon spin" aria-hidden="true" />
    <slot />
  </component>
</template>

<script setup lang="ts">
import { LoaderCircle } from '@lucide/vue'

withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'outline' | 'danger'
  size?: 'sm' | 'md' | 'lg'
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
  loading?: boolean
  fullWidth?: boolean
  as?: string
}>(), {
  variant: 'primary',
  size: 'md',
  type: 'button',
  as: 'button',
})
</script>
