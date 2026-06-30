<template>
  <form class="stack" novalidate @submit.prevent="submit">
    <div class="form-grid">
      <AppInput v-model="form.name" label="Product name" placeholder="Managed platform" :error="errors.name" />
      <AppInput v-model="form.sku" label="SKU" placeholder="PLATFORM-001" :error="errors.sku" />
      <AppInput v-model="form.category" label="Category" placeholder="Subscription" :error="errors.category" />
      <AppInput v-model.number="form.unit_price" label="Unit price" type="number" inputmode="decimal" placeholder="1200" :error="errors.unit_price" />
      <AppInput v-model="form.description" label="Description" placeholder="Short customer-facing description" :error="errors.description" />
      <AppSelect v-model="activeValue" label="Status" :options="statusOptions" />
    </div>
    <InlineFormError :message="error" />
    <div class="form-actions">
      <AppButton v-if="showCancel" variant="outline" type="button" @click="$emit('cancel')">Cancel</AppButton>
      <AppButton type="submit">{{ submitLabel }}</AppButton>
    </div>
  </form>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import InlineFormError from '@/components/ui/InlineFormError.vue'
import type { ProductInput } from '@/api/services/productService'
import { firstError, positiveNumber, required, type FieldErrors } from '@/lib/formValidation'

const props = withDefaults(defineProps<{
  initialValue?: Partial<ProductInput>
  submitLabel?: string
  showCancel?: boolean
}>(), {
  submitLabel: 'Save product',
  showCancel: false,
})
const emit = defineEmits<{ submit: [value: ProductInput]; cancel: [] }>()
const form = reactive<ProductInput>({ name: '', sku: '', category: '', description: '', unit_price: 0, active: true })
const errors = reactive<FieldErrors<keyof ProductInput>>({})
const error = defineModel<string | undefined>('error', { default: undefined })
const statusOptions = [{ label: 'Active', value: 'true' }, { label: 'Inactive', value: 'false' }]
const activeValue = computed({
  get: () => String(Boolean(form.active)),
  set: (value: string) => { form.active = value === 'true' },
})

watch(() => props.initialValue, (value) => {
  Object.assign(form, { name: '', sku: '', category: '', description: '', unit_price: 0, active: true, ...(value ?? {}) })
  Object.keys(errors).forEach((key) => delete errors[key as keyof ProductInput])
}, { immediate: true, deep: true })

function validate() {
  Object.assign(errors, {
    name: required(form.name, 'Product name is required.'),
    sku: required(form.sku, 'SKU is required.'),
    category: undefined,
    description: undefined,
    unit_price: positiveNumber(form.unit_price, 'Unit price must be greater than zero.'),
  })
  error.value = firstError(errors)
  return !error.value
}

function submit() {
  if (!validate()) return
  error.value = undefined
  emit('submit', { ...form, name: form.name.trim(), sku: form.sku.trim(), unit_price: Number(form.unit_price) })
}
</script>
