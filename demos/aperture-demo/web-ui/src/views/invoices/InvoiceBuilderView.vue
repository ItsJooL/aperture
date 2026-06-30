<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Invoice builder"
      title="Create invoice"
      subtitle="A guided flow for choosing a customer, adding billable items, reviewing totals and creating the invoice."
      :breadcrumbs="[{ label: 'Invoices', to: '/invoices' }, { label: 'Create invoice' }]"
    />

    <div class="wizard-steps" aria-label="Invoice creation steps">
      <button v-for="(label, index) in steps" :key="label" class="wizard-step" :class="{ 'wizard-step-active': step === index }" @click="goToStep(index)" :aria-selected="step === index">
        <span class="wizard-step-number">{{ index + 1 }}</span>{{ label }}
      </button>
    </div>

    <ErrorState v-if="customersQuery.isError.value || productsQuery.isError.value" description="Customers or products could not be loaded.">
      <AppButton variant="outline" @click="refetchLookups">Retry</AppButton>
    </ErrorState>
    <PageSkeleton v-else-if="customersQuery.isLoading.value || productsQuery.isLoading.value" />

    <section v-else class="invoice-builder">
      <AppCard>
        <template v-if="step === 0">
          <h2 class="card-title">Choose customer</h2>
          <p class="card-description">Select the account that should receive this invoice.</p>
          <div class="stack" style="margin-top:1rem;">
            <AppSelect v-model="customerId" label="Customer" placeholder="Select customer" :options="customerOptions" />
            <EmptyState v-if="!customerOptions.length" title="No customers available" description="Create a customer before building an invoice."><RouterLink to="/customers" class="button button-primary">Manage customers</RouterLink></EmptyState>
          </div>
        </template>

        <template v-else-if="step === 1">
          <div class="flex-between">
            <div><h2 class="card-title">Add line items</h2><p class="card-description">Choose products, adjust quantities and confirm pricing.</p></div>
            <AppButton variant="outline" @click="addLine">Add item</AppButton>
          </div>
          <div class="stack" style="margin-top:1rem;">
            <div v-for="(line, index) in lines" :key="line.localId" class="line-item-card">
              <div class="flex-between"><strong>Item {{ index + 1 }}</strong><AppButton v-if="lines.length > 1" variant="ghost" @click="removeLine(index)">Remove</AppButton></div>
              <AppSelect v-model="line.productId" label="Product" placeholder="Select product" :options="productOptions" @update:model-value="productChanged(line)" />
              <div class="form-grid">
                <AppInput v-model="line.description" label="Description" placeholder="What is being billed?" />
                <AppInput v-model.number="line.quantity" label="Quantity" type="number" placeholder="1" />
                <AppInput v-model.number="line.unit_price" label="Unit price" type="number" placeholder="0" />
                <div class="field"><span>Line total</span><strong class="stat-value" style="font-size:1.5rem;">{{ currency(lineTotal(line)) }}</strong></div>
              </div>
            </div>
          </div>
        </template>

        <template v-else-if="step === 2">
          <h2 class="card-title">Review invoice</h2>
          <p class="card-description">Confirm customer, items and status before creating it.</p>
          <div class="stack" style="margin-top:1rem;">
            <AppSelect v-model="status" label="Initial status" :options="statusOptions" />
            <div class="detail-list">
              <div class="detail-row"><span class="muted">Customer</span><strong>{{ selectedCustomer?.name || 'Not selected' }}</strong></div>
              <div class="detail-row"><span class="muted">Line items</span><strong>{{ validLines.length }}</strong></div>
              <div class="detail-row"><span class="muted">Invoice total</span><strong>{{ currency(total) }}</strong></div>
            </div>
          </div>
        </template>

        <template v-else>
          <h2 class="card-title">Ready to create</h2>
          <p class="card-description">The invoice will be created and available from the invoice list.</p>
          <div class="notice" style="margin-top:1rem;">{{ selectedCustomer?.name }} will have an invoice for {{ currency(total) }}.</div>
        </template>

        <InlineFormError :message="validationError" />
        <div class="flex-between" style="margin-top:1.25rem;">
          <AppButton variant="outline" :disabled="step === 0 || saving" @click="step--">Back</AppButton>
          <AppButton v-if="step < steps.length - 1" :disabled="saving" @click="nextStep">Continue</AppButton>
          <AppButton v-else :disabled="saving" @click="createInvoice">{{ saving ? 'Creating…' : 'Create invoice' }}</AppButton>
        </div>
      </AppCard>

      <AppCard>
        <h2 class="card-title">Invoice preview</h2>
        <p class="card-description">A customer-facing summary of what will be created.</p>
        <div class="detail-list" style="margin-top:1rem;">
          <div class="detail-row"><span class="muted">Customer</span><strong>{{ selectedCustomer?.name || 'Choose customer' }}</strong></div>
          <div v-for="line in validLines" :key="line.localId" class="detail-row"><span>{{ line.description }}</span><strong>{{ currency(lineTotal(line)) }}</strong></div>
          <div class="detail-row"><span class="muted">Total</span><strong style="font-size:1.35rem;">{{ currency(total) }}</strong></div>
        </div>
      </AppCard>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import InlineFormError from '@/components/ui/InlineFormError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageSkeleton from '@/components/ui/PageSkeleton.vue'
import { customerService } from '@/api/services/customerService'
import { invoiceService } from '@/api/services/invoiceService'
import { productService } from '@/api/services/productService'
import { currency } from '@/lib/utils'
import { useAppStore } from '@/stores/appStore'

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const queryClient = useQueryClient()
const steps = ['Customer', 'Items', 'Review', 'Create']
const step = ref(0)
const customersQuery = useQuery({ queryKey: ['customers'], queryFn: () => customerService.list(undefined, 200) })
const productsQuery = useQuery({ queryKey: ['products'], queryFn: () => productService.list(undefined, 200) })
const customerId = ref(String(route.query.customerId || ''))
const status = ref('draft')
const saving = ref(false)
const validationError = ref<string | undefined>()
const lines = reactive([{ localId: crypto.randomUUID(), productId: '', description: '', quantity: 1, unit_price: 0 }])
const statusOptions = [{ label: 'Draft', value: 'draft' }, { label: 'Issue immediately', value: 'issued' }]
const customers = computed(() => customersQuery.data.value?.items ?? [])
const products = computed(() => (productsQuery.data.value?.items ?? []).filter((product) => product.active !== false))
const customerOptions = computed(() => customers.value.map((customer) => ({ label: `${customer.name} · ${customer.email}`, value: customer.id })))
const productOptions = computed(() => products.value.map((product) => ({ label: `${product.name} · ${currency(product.unit_price)}`, value: product.id })))
const selectedCustomer = computed(() => customers.value.find((customer) => customer.id === customerId.value))
const validLines = computed(() => lines.filter((line) => line.description.trim() && Number(line.quantity) > 0 && Number(line.unit_price) > 0))
const total = computed(() => validLines.value.reduce((sum, line) => sum + lineTotal(line), 0))
function lineTotal(line: { quantity: number | string; unit_price: number | string }) { return Number(line.quantity || 0) * Number(line.unit_price || 0) }
function addLine() { lines.push({ localId: crypto.randomUUID(), productId: '', description: '', quantity: 1, unit_price: 0 }) }
function removeLine(index: number) { lines.splice(index, 1) }
function productChanged(line: typeof lines[number]) {
  const product = products.value.find((candidate) => candidate.id === line.productId)
  if (!product) return
  line.description = product.name
  line.unit_price = Number(product.unit_price)
}
function validateCurrentStep() {
  validationError.value = undefined
  if (step.value === 0 && !customerId.value) validationError.value = 'Choose a customer to continue.'
  if (step.value === 1 && !validLines.value.length) validationError.value = 'Add at least one valid line item.'
  if (step.value >= 2 && total.value <= 0) validationError.value = 'Invoice total must be greater than zero.'
  return !validationError.value
}
function nextStep() { if (validateCurrentStep()) step.value++ }
function goToStep(index: number) { if (index <= step.value || validateCurrentStep()) step.value = index }
function refetchLookups() { customersQuery.refetch(); productsQuery.refetch() }
async function createInvoice() {
  if (!validateCurrentStep()) return
  saving.value = true
  try {
    const invoice = await invoiceService.create({ customerId: customerId.value, status: status.value, lines: validLines.value.map(({ productId, description, quantity, unit_price }) => ({ productId, description, quantity: Number(quantity), unit_price: Number(unit_price) })) })
    app.toast('Invoice created', `${currency(total.value)} invoice is ready.`, 'success')
    await queryClient.invalidateQueries({ queryKey: ['invoices'] })
    router.push(invoice?.id ? `/invoices/${invoice.id}` : '/invoices')
  } catch (error) {
    app.toast('Invoice could not be created', error instanceof Error ? error.message : 'Try again.', 'error')
  } finally {
    saving.value = false
  }
}
</script>
