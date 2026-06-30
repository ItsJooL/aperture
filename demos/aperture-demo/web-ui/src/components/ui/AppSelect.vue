<template>
  <label class="field" :for="selectId">
    <span v-if="label">{{ label }}</span>
    <select
      :id="selectId"
      class="select"
      :value="modelValue"
      :aria-invalid="Boolean(error)"
      :aria-describedby="describedBy"
      @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
    >
      <option v-if="placeholder" value="">{{ placeholder }}</option>
      <option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
    <span v-if="hint" :id="hintId" class="field-hint">{{ hint }}</span>
    <InlineFormError v-if="error" :id="errorId" :message="error" />
  </label>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import InlineFormError from './InlineFormError.vue'

const props = defineProps<{
  modelValue: string
  label?: string
  placeholder?: string
  hint?: string
  error?: string
  id?: string
  options: Array<{ label: string; value: string }>
}>()

defineEmits<{ 'update:modelValue': [value: string] }>()

const selectId = computed(() => props.id ?? `select-${Math.random().toString(36).slice(2, 9)}`)
const hintId = computed(() => `${selectId.value}-hint`)
const errorId = computed(() => `${selectId.value}-error`)
const describedBy = computed(() => [props.hint ? hintId.value : '', props.error ? errorId.value : ''].filter(Boolean).join(' ') || undefined)
</script>
