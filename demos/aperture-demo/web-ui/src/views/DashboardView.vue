<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Workspace overview"
      :title="`Good ${daypart}, ${auth.user?.username?.split('@')[0] || 'there'}.`"
      subtitle="A calm daily overview for customer growth, active catalogue items, open invoices and recent collections."
    >
      <template #actions>
        <RouterLink to="/customers" class="button button-outline">Find customer</RouterLink>
        <RouterLink to="/invoices/new" class="button button-primary">Create invoice</RouterLink>
      </template>
    </PageHeader>

    <PageSkeleton v-if="isLoading" />
    <AppCard v-else-if="isTenantlessAdmin">
      <h2 class="card-title">Platform administration</h2>
      <p class="card-description">
        This account is not tied to a tenant workspace. Use administration to review tenants and system access.
      </p>
      <RouterLink to="/admin" class="button button-primary" style="margin-top:1rem;">Open administration</RouterLink>
    </AppCard>
    <ErrorState v-else-if="hasError" description="The workspace summary could not be loaded. Check your connection and try again.">
      <AppButton variant="outline" @click="refetchAll">Retry</AppButton>
    </ErrorState>
    <template v-else>
      <div class="grid grid-4">
        <StatCard label="Revenue" :value="currency(totalRevenue)" detail="Paid and issued invoices" />
        <StatCard label="Receivables" :value="currency(outstanding)" detail="Draft, issued and overdue" />
        <StatCard label="Customers" :value="customers.length" detail="Active customer records" />
        <StatCard label="Products" :value="products.length" detail="Catalogue items" />
      </div>

      <section style="margin-top:1.25rem;">
        <QuickActions />
      </section>

      <section class="grid grid-2" style="margin-top:1.25rem;">
        <AppCard>
          <div class="flex-between">
            <div><h2 class="card-title">Recent invoices</h2><p class="card-description">A quick view of work that may need attention.</p></div>
            <RouterLink to="/invoices" class="button button-outline">View all</RouterLink>
          </div>
          <TableSkeleton v-if="invoicesQuery.isLoading.value" :columns="['Invoice', 'Status', 'Amount']" />
          <div v-else class="table-wrap" style="margin-top:1rem;">
            <table class="responsive-table">
              <thead><tr><th>Invoice</th><th>Status</th><th>Amount</th></tr></thead>
              <tbody>
                <tr v-for="invoice in invoices" :key="invoice.id" class="clickable" @click="$router.push(`/invoices/${invoice.id}`)">
                  <td data-label="Invoice"><strong>#{{ invoice.id }}</strong></td>
                  <td data-label="Status"><StatusBadge :status="invoice.status" /></td>
                  <td data-label="Amount">{{ currency(invoice.amount) }}</td>
                </tr>
                <tr v-if="!invoices.length"><td colspan="3"><EmptyState title="No invoices yet" description="Create an invoice once you have a customer and billable product ready." /></td></tr>
              </tbody>
            </table>
          </div>
        </AppCard>

        <AppCard>
          <h2 class="card-title">Today’s focus</h2>
          <p class="card-description">Useful next actions based on the current workspace data.</p>
          <div class="timeline" style="margin-top:1rem;">
            <div class="timeline-item"><div class="timeline-dot">1</div><div><strong>Follow up open invoices</strong><p class="muted">{{ openInvoiceCount }} invoices are still draft, issued or overdue.</p></div></div>
            <div class="timeline-item"><div class="timeline-dot">2</div><div><strong>Review catalogue health</strong><p class="muted">{{ inactiveProductCount }} inactive products are hidden from new billing workflows.</p></div></div>
            <div class="timeline-item"><div class="timeline-dot">3</div><div><strong>Keep customer details current</strong><p class="muted">Use customer profiles to update contact details before issuing invoices.</p></div></div>
          </div>
        </AppCard>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageSkeleton from '@/components/ui/PageSkeleton.vue'
import QuickActions from '@/components/product/QuickActions.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { customerService } from '@/api/services/customerService'
import { invoiceService } from '@/api/services/invoiceService'
import { productService } from '@/api/services/productService'
import { currency } from '@/lib/utils'
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
const emptyList = { items: [], meta: {}, links: {} }
const isTenantlessAdmin = computed(() => auth.canAccessAdmin && !auth.tenantId)
const customersQuery = useQuery({ queryKey: ['customers'], queryFn: () => auth.tenantId ? customerService.list() : Promise.resolve(emptyList) })
const productsQuery = useQuery({ queryKey: ['products'], queryFn: () => auth.tenantId ? productService.list() : Promise.resolve(emptyList) })
const invoicesQuery = useQuery({ queryKey: ['invoices'], queryFn: () => auth.tenantId ? invoiceService.list() : Promise.resolve(emptyList) })
const customers = computed(() => customersQuery.data.value?.items ?? [])
const products = computed(() => productsQuery.data.value?.items ?? [])
const invoices = computed(() => invoicesQuery.data.value?.items.slice(0, 6) ?? [])
const allInvoices = computed(() => invoicesQuery.data.value?.items ?? [])
const totalRevenue = computed(() => allInvoices.value.filter((invoice) => ['paid', 'issued'].includes(String(invoice.status))).reduce((sum, invoice) => sum + Number(invoice.amount ?? 0), 0))
const outstanding = computed(() => allInvoices.value.filter((invoice) => ['draft', 'issued', 'overdue'].includes(String(invoice.status))).reduce((sum, invoice) => sum + Number(invoice.amount ?? 0), 0))
const openInvoiceCount = computed(() => allInvoices.value.filter((invoice) => ['draft', 'issued', 'overdue'].includes(String(invoice.status))).length)
const inactiveProductCount = computed(() => products.value.filter((product) => product.active === false).length)
const isLoading = computed(() => customersQuery.isLoading.value || productsQuery.isLoading.value || invoicesQuery.isLoading.value)
const hasError = computed(() => customersQuery.isError.value || productsQuery.isError.value || invoicesQuery.isError.value)
const daypart = computed(() => new Date().getHours() < 12 ? 'morning' : new Date().getHours() < 18 ? 'afternoon' : 'evening')
function refetchAll() {
  customersQuery.refetch(); productsQuery.refetch(); invoicesQuery.refetch()
}
</script>
