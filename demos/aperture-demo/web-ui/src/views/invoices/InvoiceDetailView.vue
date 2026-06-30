<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Invoice detail"
      :title="invoice ? `Invoice #${invoice.id}` : 'Invoice'"
      subtitle="Review the invoice, move it through the billing lifecycle, and record payments."
      :breadcrumbs="[{ label: 'Invoices', to: '/invoices' }, { label: invoice ? `#${invoice.id}` : 'Invoice' }]"
    >
      <template #actions>
        <AppButton v-if="invoice?.status === 'draft'" @click="updateStatus('issued')">Issue invoice</AppButton>
        <AppButton v-if="invoice && invoice.status !== 'paid'" variant="outline" @click="paymentOpen = true">Record payment</AppButton>
        <AppButton v-if="invoice?.status !== 'paid'" variant="outline" @click="updateStatus('paid')">Mark paid</AppButton>
      </template>
    </PageHeader>

    <PageSkeleton v-if="query.isLoading.value" />
    <ErrorState v-else-if="query.isError.value || !invoice" description="This invoice could not be found."><RouterLink to="/invoices" class="button button-outline">Back to invoices</RouterLink></ErrorState>
    <template v-else>
      <div class="grid grid-3">
        <StatCard label="Amount" :value="currency(invoice.amount)" detail="Invoice total" />
        <StatCard label="Status" :value="String(invoice.status)" detail="Billing lifecycle" />
        <StatCard label="Record" :value="invoice.id" detail="Reference number" />
      </div>

      <section style="margin-top:1rem;">
        <InvoiceStatusStepper :status="invoice.status" />
      </section>

      <section class="grid grid-2" style="margin-top:1rem;">
        <AppCard>
          <div class="flex-between"><div><h2 class="card-title">Invoice summary</h2><p class="card-description">The current billing state and available actions.</p></div><StatusBadge :status="invoice.status" /></div>
          <div class="detail-list" style="margin-top:1rem;">
            <div class="detail-row"><span class="muted">Amount</span><strong>{{ currency(invoice.amount) }}</strong></div>
            <div class="detail-row"><span class="muted">Status</span><strong>{{ invoice.status }}</strong></div>
            <div class="detail-row"><span class="muted">Customer relationship</span><span class="mono">{{ customerRelationship || 'Not linked' }}</span></div>
          </div>
        </AppCard>
        <AppCard>
          <h2 class="card-title">Lifecycle guidance</h2>
          <p class="card-description">Use these actions to keep finance and customer-facing teams aligned.</p>
          <div class="timeline" style="margin-top:1rem;">
            <div class="timeline-item"><div class="timeline-dot">1</div><div><strong>Draft</strong><p class="muted">Review details before sending it to the customer.</p></div></div>
            <div class="timeline-item"><div class="timeline-dot">2</div><div><strong>Issued</strong><p class="muted">Track as receivable until payment is recorded.</p></div></div>
            <div class="timeline-item"><div class="timeline-dot">3</div><div><strong>Paid</strong><p class="muted">Mark complete after payment has been received.</p></div></div>
          </div>
        </AppCard>
      </section>
    </template>

    <AppModal :open="paymentOpen" title="Record payment" description="Add a payment against this invoice." @close="paymentOpen = false">
      <form class="stack" @submit.prevent="recordPayment">
        <AppInput v-model.number="paymentAmount" label="Payment amount" type="number" />
        <InlineFormError :message="paymentError" />
        <div class="flex" style="justify-content:flex-end; gap:.65rem;">
          <AppButton variant="outline" type="button" @click="paymentOpen = false">Cancel</AppButton>
          <AppButton type="submit">Record payment</AppButton>
        </div>
      </form>
    </AppModal>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppModal from '@/components/ui/AppModal.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import InlineFormError from '@/components/ui/InlineFormError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageSkeleton from '@/components/ui/PageSkeleton.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import InvoiceStatusStepper from '@/components/product/InvoiceStatusStepper.vue'
import { invoiceService } from '@/api/services/invoiceService'
import { currency } from '@/lib/utils'
import { useAppStore } from '@/stores/appStore'

const route = useRoute()
const app = useAppStore()
const client = useQueryClient()
const id = computed(() => String(route.params.id))
const query = useQuery({ queryKey: ['invoice', id], queryFn: () => invoiceService.get(id.value) })
const invoice = computed(() => query.data.value)
const paymentOpen = ref(false)
const paymentAmount = ref(0)
const paymentError = ref<string | undefined>()
const customerRelationship = computed(() => {
  const data = invoice.value?.relationships?.customer?.data
  if (!data || Array.isArray(data)) return ''
  return data.id
})
async function updateStatus(status: string) {
  await invoiceService.updateStatus(id.value, status)
  app.toast('Invoice updated', `Status changed to ${status}.`, 'success')
  await query.refetch(); await client.invalidateQueries({ queryKey: ['invoices'] })
}
async function recordPayment() {
  paymentError.value = undefined
  if (Number(paymentAmount.value) <= 0) {
    paymentError.value = 'Payment amount must be greater than zero.'
    return
  }
  await invoiceService.recordPayment(id.value, Number(paymentAmount.value))
  await invoiceService.updateStatus(id.value, 'paid')
  paymentOpen.value = false
  app.toast('Payment recorded', 'The invoice has been marked as paid.', 'success')
  await query.refetch(); await client.invalidateQueries({ queryKey: ['invoices'] })
}
</script>
