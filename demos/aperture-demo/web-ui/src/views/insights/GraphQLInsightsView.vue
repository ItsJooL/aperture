<template>
  <section class="content-wrap">
    <PageHeader
      kicker="GraphQL"
      title="Billing insights"
      subtitle="Invoices, their customer, and every line item — pulled back in a single GraphQL round trip to Elide's Relay-style API."
      :breadcrumbs="[{ label: 'Insights' }]"
    >
      <template #actions>
        <AppButton variant="outline" :loading="query.isLoading.value" @click="query.refetch()">Refresh</AppButton>
      </template>
    </PageHeader>

    <div class="grid grid-3" style="margin-bottom:1rem;">
      <StatCard label="Invoices" :value="invoices.length" detail="Fetched in one GraphQL request" />
      <StatCard label="Billed" :value="currency(totalAmount)" detail="Across every invoice" />
      <StatCard label="Line items" :value="totalLineItems" detail="Nested inside the same response" />
    </div>

    <AppCard>
      <div class="flex-between">
        <div>
          <h2 class="card-title">Invoices with nested customer &amp; line items</h2>
          <p class="card-description">
            Expand a row to see the customer and line items returned attached to that invoice
            node — the JSON:API equivalent needs multiple requests or an <code class="mono">include=</code> query per invoice.
          </p>
        </div>
        <span class="badge badge-info mono">POST /graphql/v3</span>
      </div>

      <ErrorState v-if="query.isError.value" title="GraphQL request failed" :description="errorMessage" style="margin-top:1rem;">
        <AppButton variant="outline" @click="query.refetch()">Retry</AppButton>
      </ErrorState>
      <TableSkeleton v-else-if="query.isLoading.value" :columns="['Invoice', 'Customer', 'Status', 'Amount', '']" />
      <div v-else class="table-wrap" style="margin-top:1rem;">
        <table class="responsive-table">
          <thead>
            <tr><th>Invoice</th><th>Customer</th><th>Status</th><th>Amount</th><th></th></tr>
          </thead>
          <tbody>
            <template v-for="invoice in invoices" :key="invoice.id">
              <tr class="clickable" @click="toggle(invoice.id)">
                <td data-label="Invoice">
                  <strong>#{{ invoice.id }}</strong>
                  <div class="muted">{{ invoice.lineItems.length }} line item{{ invoice.lineItems.length === 1 ? '' : 's' }}</div>
                </td>
                <td data-label="Customer">
                  {{ invoice.customerName }}
                  <div class="muted">{{ invoice.customerEmail || 'No email on file' }}</div>
                </td>
                <td data-label="Status"><StatusBadge :status="invoice.status" /></td>
                <td data-label="Amount">{{ currency(invoice.amount) }}</td>
                <td data-label="">
                  <ChevronDown v-if="expanded.has(invoice.id)" class="chevron-icon" aria-hidden="true" />
                  <ChevronRight v-else class="chevron-icon" aria-hidden="true" />
                </td>
              </tr>
              <tr v-if="expanded.has(invoice.id)" class="insight-detail-row">
                <td colspan="5">
                  <div class="insight-nested">
                    <p class="muted" style="margin-bottom:.5rem;">Nested inside this invoice's GraphQL response — no follow-up request.</p>
                    <table class="responsive-table insight-nested-table">
                      <thead><tr><th>Line item</th><th>Qty</th><th>Unit price</th><th>Line total</th></tr></thead>
                      <tbody>
                        <tr v-for="(item, index) in invoice.lineItems" :key="index">
                          <td data-label="Line item">{{ item.description }}</td>
                          <td data-label="Qty">{{ item.quantity }}</td>
                          <td data-label="Unit price">{{ currency(item.unitPrice) }}</td>
                          <td data-label="Line total">{{ currency(item.lineTotal) }}</td>
                        </tr>
                        <tr v-if="!invoice.lineItems.length"><td colspan="4" class="muted">No line items on this invoice.</td></tr>
                      </tbody>
                    </table>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!invoices.length"><td colspan="5"><EmptyState title="No invoices yet" description="Create an invoice to see it appear here via GraphQL." /></td></tr>
          </tbody>
        </table>
      </div>
    </AppCard>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { ChevronDown, ChevronRight } from '@lucide/vue'
import { useQuery } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { insightsService } from '@/api/services/insightsService'
import { GraphQLRequestError } from '@/api/graphql/client'
import { currency } from '@/lib/utils'

const query = useQuery({ queryKey: ['insights', 'invoices'], queryFn: () => insightsService.invoiceInsights() })
const invoices = computed(() => query.data.value ?? [])
const totalAmount = computed(() => invoices.value.reduce((sum, invoice) => sum + invoice.amount, 0))
const totalLineItems = computed(() => invoices.value.reduce((sum, invoice) => sum + invoice.lineItems.length, 0))
const errorMessage = computed(() => {
  const error = query.error.value
  if (error instanceof GraphQLRequestError) return error.message
  if (error instanceof Error) return error.message
  return 'This GraphQL query could not be completed.'
})

const expanded = reactive(new Set<string>())
function toggle(id: string) {
  if (expanded.has(id)) expanded.delete(id)
  else expanded.add(id)
}
</script>
