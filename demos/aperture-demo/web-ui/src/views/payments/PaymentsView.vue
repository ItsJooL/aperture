<template>
  <section class="content-wrap">
    <PageHeader kicker="Payments" title="Payments" subtitle="Review recent collections and confirm that paid invoices are reflected in the workspace." />

    <div class="grid grid-3" style="margin-bottom:1rem;">
      <StatCard label="Collected" :value="currency(totalPaid)" detail="Payments in this view" />
      <StatCard label="Payments" :value="payments.length" detail="Payment records" />
      <StatCard label="Average" :value="currency(averagePayment)" detail="Per payment" />
    </div>

    <AppCard>
      <DataToolbar v-model:search="search" search-label="Search payments" search-placeholder="Amount or reference" :count="payments.length" noun="payments" />
      <ErrorState v-if="query.isError.value" description="Payments could not be loaded."><AppButton variant="outline" @click="query.refetch()">Retry</AppButton></ErrorState>
      <TableSkeleton v-else-if="query.isLoading.value" :columns="['Payment', 'Amount', 'Linked invoice']" />
      <div v-else class="table-wrap">
        <table class="responsive-table">
          <thead><tr><th>Payment</th><th>Amount</th><th>Linked invoice</th></tr></thead>
          <tbody>
            <tr v-for="payment in payments" :key="payment.id">
              <td data-label="Payment"><strong>#{{ payment.id }}</strong></td>
              <td data-label="Amount">{{ currency(payment.amount) }}</td>
              <td data-label="Linked invoice" class="mono">{{ relatedInvoice(payment) || '—' }}</td>
            </tr>
            <tr v-if="!payments.length"><td colspan="3"><EmptyState title="No payments found" description="Payments appear here once they are recorded against invoices." /></td></tr>
          </tbody>
        </table>
      </div>
    </AppCard>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import DataToolbar from '@/components/ui/DataToolbar.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { listResources } from '@/api/services/resourceService'
import type { Payment } from '@/api/types/domain'
import { currency } from '@/lib/utils'

const search = ref('')
const query = useQuery({ queryKey: ['payments', search], queryFn: () => listResources<Payment>('payments', { search: search.value, include: ['invoice'] }) })
const payments = computed(() => query.data.value?.items ?? [])
const totalPaid = computed(() => payments.value.reduce((sum, payment) => sum + Number(payment.amount ?? 0), 0))
const averagePayment = computed(() => payments.value.length ? totalPaid.value / payments.value.length : 0)
function relatedInvoice(payment: Payment) {
  const data = payment.relationships?.invoice?.data
  return data && !Array.isArray(data) ? data.id : ''
}
watch(search, () => query.refetch())
</script>
