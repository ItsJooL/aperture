<template>
  <Teleport to="body">
    <div v-if="open" class="dialog-backdrop" @click.self="$emit('close')" @keydown.esc="$emit('close')">
      <section
        ref="panel"
        class="dialog-panel"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="description ? descriptionId : undefined"
        tabindex="-1"
      >
        <div class="card-pad flex-between modal-header">
          <div>
            <h2 :id="titleId" class="card-title">{{ title }}</h2>
            <p v-if="description" :id="descriptionId" class="card-description">{{ description }}</p>
          </div>
          <button class="button button-ghost button-sm icon-button" @click="$emit('close')" aria-label="Close dialog"><X class="nav-icon" aria-hidden="true" /></button>
        </div>
        <div class="card-pad">
          <slot />
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { X } from '@lucide/vue'

const props = defineProps<{ open: boolean; title: string; description?: string }>()
defineEmits<{ close: [] }>()
const panel = ref<HTMLElement | null>(null)
const titleId = computed(() => `dialog-title-${props.title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`)
const descriptionId = computed(() => `${titleId.value}-description`)
watch(() => props.open, async (open) => {
  if (!open) return
  await nextTick()
  panel.value?.focus()
})
</script>
