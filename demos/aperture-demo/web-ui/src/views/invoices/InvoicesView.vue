<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Billing"
      title="Invoices"
      subtitle="Track drafts, issued invoices, overdue balances and paid work in one place."
    >
      <template #actions><RouterLink to="/invoices/new" class="button button-primary">Create invoice</RouterLink></template>
    </PageHeader>

    <div class="grid grid-4" style="margin-bottom:1rem;">
      <StatCard label="Open" :value="openCount" detail="Draft, issued or overdue" />
      <StatCard label="Paid" :value="paidCount" detail="Completed invoices" />
      <StatCard label="Outstanding" :value="currency(outstanding)" detail="Needs attention" />
      <StatCard label="Total" :value="currency(total)" detail="All listed invoices" />
    </div>

    <AppCard>
      <DataToolbar v-model:search="search" search-label="Search invoices" search-placeholder="Status or invoice number" :count="invoices.length" noun="invoices">
        <template #filters>
          <AppSelect v-model="statusFilter" label="Status" :options="statusOptions" />
        </template>
      </DataToolbar>
      <ErrorState v-if="query.isError.value" description="Invoices could not be loaded."><AppButton variant="outline" @click="query.refetch()">Retry</AppButton></ErrorState>
      <TableSkeleton v-else-if="query.isLoading.value" :columns="['Invoice', 'Status', 'Amount', 'Action']" />
      <div v-else class="table-wrap">
        <table class="responsive-table">
          <thead><tr><th>Invoice</th><th>Status</th><th>Amount</th><th>Action</th></tr></thead>
          <tbody>
            <tr v-for="invoice in filteredInvoices" :key="invoice.id" class="clickable" @click="$router.push(`/invoices/${invoice.id}`)">
              <td data-label="Invoice"><strong>#{{ invoice.id }}</strong><div class="muted">Customer billing record</div></td>
              <td data-label="Status"><StatusBadge :status="invoice.status" /></td>
              <td data-label="Amount">{{ currency(invoice.amount) }}</td>
              <td data-label="Action"><span class="button button-outline" style="pointer-events:none;">Open</span></td>
            </tr>
            <tr v-if="!filteredInvoices.length"><td colspan="4"><EmptyState title="No invoices match" description="Change filters or create a new invoice."><RouterLink to="/invoices/new" class="button button-primary">Create invoice</RouterLink></EmptyState></td></tr>
          </tbody>
        </table>
      </div>
    </AppCard>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import DataToolbar from '@/components/ui/DataToolbar.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { invoiceService } from '@/api/services/invoiceService'
import { currency } from '@/lib/utils'

const search = ref('')
const statusFilter = ref('all')
const statusOptions = [
  { label: 'All statuses', value: 'all' },
  { label: 'Draft', value: 'draft' },
  { label: 'Issued', value: 'issued' },
  { label: 'Paid', value: 'paid' },
  { label: 'Overdue', value: 'overdue' },
]
const query = useQuery({ queryKey: ['invoices', search], queryFn: () => invoiceService.list(search.value) })
const invoices = computed(() => query.data.value?.items ?? [])
const filteredInvoices = computed(() => statusFilter.value === 'all' ? invoices.value : invoices.value.filter((invoice) => invoice.status === statusFilter.value))
const openCount = computed(() => invoices.value.filter((invoice) => ['draft', 'issued', 'overdue'].includes(String(invoice.status))).length)
const paidCount = computed(() => invoices.value.filter((invoice) => invoice.status === 'paid').length)
const outstanding = computed(() => invoices.value.filter((invoice) => ['draft', 'issued', 'overdue'].includes(String(invoice.status))).reduce((sum, invoice) => sum + Number(invoice.amount ?? 0), 0))
const total = computed(() => invoices.value.reduce((sum, invoice) => sum + Number(invoice.amount ?? 0), 0))
watch(search, () => query.refetch())
</script>
