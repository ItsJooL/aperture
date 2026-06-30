<template>
  <AppModal :open="open" :title="title" :description="description" @close="$emit('cancel')">
    <div class="stack">
      <div v-if="tone === 'danger'" class="notice notice-danger">
        This action can affect access or visible records. Please confirm before continuing.
      </div>
      <slot />
      <div class="flex" style="justify-content:flex-end; gap:.65rem;">
        <AppButton variant="outline" @click="$emit('cancel')">{{ cancelLabel }}</AppButton>
        <AppButton :variant="tone === 'danger' ? 'danger' : 'primary'" @click="$emit('confirm')">{{ confirmLabel }}</AppButton>
      </div>
    </div>
  </AppModal>
</template>

<script setup lang="ts">
import AppButton from './AppButton.vue'
import AppModal from './AppModal.vue'

withDefaults(defineProps<{
  open: boolean
  title: string
  description?: string
  confirmLabel?: string
  cancelLabel?: string
  tone?: 'default' | 'danger'
}>(), {
  confirmLabel: 'Confirm',
  cancelLabel: 'Cancel',
  tone: 'default',
})

defineEmits<{ cancel: []; confirm: [] }>()
</script>
