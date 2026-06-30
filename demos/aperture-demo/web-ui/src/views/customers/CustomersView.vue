<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Customers"
      title="Customer accounts"
      subtitle="Manage customer profiles, keep contact details current, and start billing from a trusted account record."
    >
      <template #actions><AppButton v-if="auth.hasPermission('customers:write')" @click="open = true">New customer</AppButton></template>
    </PageHeader>

    <AppCard>
      <DataToolbar v-model:search="search" search-label="Search customers" search-placeholder="Name, email or phone" :count="customers.length" noun="customers" />
      <ErrorState v-if="query.isError.value" description="Customers could not be loaded."><AppButton variant="outline" @click="query.refetch()">Retry</AppButton></ErrorState>
      <TableSkeleton v-else-if="query.isLoading.value" :columns="['Customer', 'Email', 'Phone', 'Status']" />
      <div v-else class="table-wrap">
        <table class="responsive-table">
          <thead><tr><th>Customer</th><th>Email</th><th>Phone</th><th>Status</th></tr></thead>
          <tbody>
            <tr v-for="customer in customers" :key="customer.id" class="clickable" @click="$router.push(`/customers/${customer.id}`)">
              <td data-label="Customer"><div class="flex" style="gap:.7rem; align-items:center;"><div class="avatar">{{ initials(customer.name) }}</div><strong>{{ customer.name }}</strong></div></td>
              <td data-label="Email">{{ customer.email }}</td>
              <td data-label="Phone">{{ customer.phone_number || '—' }}</td>
              <td data-label="Status"><StatusBadge :status="customer.deletedAt ? 'archived' : 'active'" /></td>
            </tr>
            <tr v-if="!customers.length"><td colspan="4"><EmptyState title="No customers found" description="Create a customer to start sending invoices and tracking account history."><AppButton @click="open = true">Create customer</AppButton></EmptyState></td></tr>
          </tbody>
        </table>
      </div>
    </AppCard>

    <AppModal :open="open" title="Create customer" description="Add contact details that will be reused in billing workflows." @close="open = false">
      <CustomerForm v-model:error="formError" @submit="createCustomer" @cancel="open = false" show-cancel />
    </AppModal>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppModal from '@/components/ui/AppModal.vue'
import DataToolbar from '@/components/ui/DataToolbar.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import CustomerForm from '@/components/product/CustomerForm.vue'
import { customerService, type CustomerInput } from '@/api/services/customerService'
import { initials } from '@/lib/utils'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'
import { errorMessage } from '@/lib/formValidation'

const app = useAppStore()
const auth = useAuthStore()
const client = useQueryClient()
const search = ref('')
const open = ref(false)
const formError = ref<string | undefined>()
const query = useQuery({ queryKey: ['customers', search], queryFn: () => customerService.list(search.value) })
const customers = computed(() => query.data.value?.items ?? [])
watch(search, () => query.refetch())
async function createCustomer(input: CustomerInput) {
  if (!auth.hasPermission('customers:write')) return
  formError.value = undefined
  try {
    await customerService.create(input)
    open.value = false
    app.toast('Customer created', `${input.name} is ready for billing.`, 'success')
    await client.invalidateQueries({ queryKey: ['customers'] })
    await query.refetch()
  } catch (error) {
    formError.value = errorMessage(error, 'Customer could not be saved.')
  }
}
</script>
