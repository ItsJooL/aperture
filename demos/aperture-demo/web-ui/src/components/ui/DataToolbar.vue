<template>
  <div class="data-toolbar">
    <div class="data-toolbar-main">
      <AppInput
        v-if="searchable"
        :model-value="search"
        :label="searchLabel"
        :placeholder="searchPlaceholder"
        @update:model-value="$emit('update:search', $event)"
      />
      <slot name="filters" />
    </div>
    <div class="data-toolbar-side">
      <AppBadge variant="neutral">{{ countLabel }}</AppBadge>
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppBadge from './AppBadge.vue'
import AppInput from './AppInput.vue'

const props = withDefaults(defineProps<{
  search?: string
  searchable?: boolean
  searchLabel?: string
  searchPlaceholder?: string
  count?: number
  noun?: string
}>(), {
  search: '',
  searchable: true,
  searchLabel: 'Search',
  searchPlaceholder: 'Search',
  count: 0,
  noun: 'items',
})

defineEmits<{ 'update:search': [value: string] }>()

const countLabel = computed(() => `${props.count} ${props.noun}`)
</script>
