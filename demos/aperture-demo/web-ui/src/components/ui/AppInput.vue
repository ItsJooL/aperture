<template>
  <label class="field" :for="inputId">
    <span v-if="label">{{ label }}</span>
    <input
      :id="inputId"
      class="input"
      :type="type"
      :inputmode="inputmode"
      :placeholder="placeholder"
      :value="modelValue ?? ''"
      :autocomplete="autocomplete"
      :aria-invalid="Boolean(error)"
      :aria-describedby="describedBy"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <span v-if="hint" :id="hintId" class="field-hint">{{ hint }}</span>
    <InlineFormError v-if="error" :id="errorId" :message="error" />
  </label>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import InlineFormError from './InlineFormError.vue'

const props = withDefaults(defineProps<{
  modelValue?: string | number | null
  label?: string
  placeholder?: string
  hint?: string
  error?: string
  type?: string
  inputmode?: 'none' | 'text' | 'decimal' | 'numeric' | 'tel' | 'search' | 'email' | 'url'
  autocomplete?: string
  id?: string
}>(), { type: 'text', modelValue: '' })

defineEmits<{ 'update:modelValue': [value: string] }>()

const inputId = computed(() => props.id ?? `input-${Math.random().toString(36).slice(2, 9)}`)
const hintId = computed(() => `${inputId.value}-hint`)
const errorId = computed(() => `${inputId.value}-error`)
const describedBy = computed(() => [props.hint ? hintId.value : '', props.error ? errorId.value : ''].filter(Boolean).join(' ') || undefined)
</script>
