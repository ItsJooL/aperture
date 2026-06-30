<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Customer profile"
      :title="customer?.name || 'Customer'"
      subtitle="View contact details, update the profile, and start a new invoice from this account."
      :breadcrumbs="[{ label: 'Customers', to: '/customers' }, { label: customer?.name || 'Customer' }]"
    >
      <template #actions>
        <RouterLink v-if="auth.hasPermission('invoices:write')" :to="`/invoices/new?customerId=${customer?.id || ''}`" class="button button-primary">Create invoice</RouterLink>
        <AppButton v-if="auth.hasPermission('customers:write')" variant="outline" @click="editOpen = true" :disabled="!customer">Edit</AppButton>
        <AppButton v-if="auth.hasPermission('customers:archive') && customer && !customer.deletedAt" variant="danger" @click="archiveOpen = true">Archive</AppButton>
      </template>
    </PageHeader>

    <PageSkeleton v-if="query.isLoading.value" />
    <ErrorState v-else-if="query.isError.value || !customer" description="This customer could not be found."><RouterLink to="/customers" class="button button-outline">Back to customers</RouterLink></ErrorState>
    <template v-else>
      <div class="grid grid-3">
        <StatCard label="Status" :value="customer.deletedAt ? 'Archived' : 'Active'" detail="Customer availability" />
        <StatCard label="Email" :value="customer.email" detail="Primary contact" />
        <StatCard label="Phone" :value="customer.phone_number || 'Not set'" detail="Optional contact number" />
      </div>

      <section class="grid grid-2" style="margin-top:1rem;">
        <AppCard>
          <h2 class="card-title">Profile details</h2>
          <p class="card-description">The information used across invoices and account workflows.</p>
          <div class="detail-list" style="margin-top:1rem;">
            <div class="detail-row"><span class="muted">Name</span><strong>{{ customer.name }}</strong></div>
            <div class="detail-row"><span class="muted">Email</span><strong>{{ customer.email }}</strong></div>
            <div class="detail-row"><span class="muted">Phone</span><strong>{{ customer.phone_number || '—' }}</strong></div>
            <div class="detail-row"><span class="muted">Record</span><span class="mono">{{ customer.id }}</span></div>
          </div>
        </AppCard>
        <AppCard>
          <h2 class="card-title">Recommended next steps</h2>
          <p class="card-description">Actions that move the customer journey forward.</p>
          <div class="timeline" style="margin-top:1rem;">
            <div class="timeline-item"><div class="timeline-dot">1</div><div><strong>Create an invoice</strong><p class="muted">Preselect this customer in the invoice builder.</p></div></div>
            <div class="timeline-item"><div class="timeline-dot">2</div><div><strong>Review details before issuing</strong><p class="muted">Use the edit action to keep contact information accurate.</p></div></div>
            <div class="timeline-item"><div class="timeline-dot">3</div><div><strong>Archive when inactive</strong><p class="muted">Archived customers stay visible for historical records.</p></div></div>
          </div>
        </AppCard>
      </section>
    </template>

    <AppModal :open="editOpen" title="Edit customer" description="Update the customer details used across billing workflows." @close="editOpen = false">
      <CustomerForm v-model:error="formError" :initial-value="customer || undefined" submit-label="Update customer" show-cancel @submit="updateCustomer" @cancel="editOpen = false" />
    </AppModal>
    <ConfirmDialog :open="archiveOpen" title="Archive customer?" description="The customer will be hidden from normal active workflows but retained for history." confirm-label="Archive customer" tone="danger" @cancel="archiveOpen = false" @confirm="archiveCustomer" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppModal from '@/components/ui/AppModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageSkeleton from '@/components/ui/PageSkeleton.vue'
import StatCard from '@/components/ui/StatCard.vue'
import CustomerForm from '@/components/product/CustomerForm.vue'
import { customerService, type CustomerInput } from '@/api/services/customerService'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'
import { errorMessage } from '@/lib/formValidation'

const route = useRoute()
const app = useAppStore()
const auth = useAuthStore()
const queryClient = useQueryClient()
const id = computed(() => String(route.params.id))
const query = useQuery({ queryKey: ['customer', id], queryFn: () => customerService.get(id.value) })
const customer = computed(() => query.data.value)
const editOpen = ref(false)
const archiveOpen = ref(false)
const formError = ref<string | undefined>()
async function updateCustomer(input: CustomerInput) {
  if (!auth.hasPermission('customers:write')) return
  formError.value = undefined
  try {
    await customerService.update(id.value, input)
    editOpen.value = false
    app.toast('Customer updated', 'Profile changes have been saved.', 'success')
    await queryClient.invalidateQueries({ queryKey: ['customer'] })
    await queryClient.invalidateQueries({ queryKey: ['customers'] })
  } catch (error) {
    formError.value = errorMessage(error, 'Customer could not be updated.')
  }
}
async function archiveCustomer() {
  if (!auth.hasPermission('customers:archive')) return
  await customerService.archive(id.value)
  archiveOpen.value = false
  app.toast('Customer archived', 'The customer remains available for historical records.', 'success')
  await query.refetch()
  await queryClient.invalidateQueries({ queryKey: ['customers'] })
}
</script>
