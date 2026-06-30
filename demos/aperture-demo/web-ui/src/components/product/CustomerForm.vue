<template>
  <form class="stack" novalidate @submit.prevent="submit">
    <div class="form-grid">
      <AppInput v-model="form.name" label="Full name" placeholder="Example Customer" autocomplete="name" :error="errors.name" />
      <AppInput v-model="form.email" label="Email" placeholder="customer@example.com" inputmode="email" autocomplete="email" :error="errors.email" />
      <AppInput v-model="form.phone_number" label="Phone" placeholder="+353 1 555 0100" inputmode="tel" autocomplete="tel" :error="errors.phone_number" />
    </div>
    <InlineFormError :message="error" />
    <div class="form-actions">
      <AppButton v-if="showCancel" variant="outline" type="button" @click="$emit('cancel')">Cancel</AppButton>
      <AppButton type="submit">{{ submitLabel }}</AppButton>
    </div>
  </form>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import InlineFormError from '@/components/ui/InlineFormError.vue'
import type { CustomerInput } from '@/api/services/customerService'
import { email, firstError, required, type FieldErrors } from '@/lib/formValidation'

const props = withDefaults(defineProps<{
  initialValue?: Partial<CustomerInput>
  submitLabel?: string
  showCancel?: boolean
}>(), {
  submitLabel: 'Save customer',
  showCancel: false,
})
const emit = defineEmits<{ submit: [value: CustomerInput]; cancel: [] }>()
const form = reactive<CustomerInput>({ name: '', email: '', phone_number: '' })
const errors = reactive<FieldErrors<keyof CustomerInput>>({})
const error = defineModel<string | undefined>('error', { default: undefined })

watch(() => props.initialValue, (value) => {
  Object.assign(form, { name: '', email: '', phone_number: '', ...(value ?? {}) })
  Object.keys(errors).forEach((key) => delete errors[key as keyof CustomerInput])
}, { immediate: true, deep: true })

function validate() {
  Object.assign(errors, { name: required(form.name, 'Customer name is required.'), email: required(form.email) ?? email(form.email), phone_number: undefined })
  error.value = firstError(errors)
  return !error.value
}

function submit() {
  if (!validate()) return
  error.value = undefined
  emit('submit', { ...form, name: form.name.trim(), email: form.email.trim(), phone_number: form.phone_number?.trim() })
}
</script>
