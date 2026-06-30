<template>
  <section class="content-wrap">
    <PageHeader kicker="Suppliers" title="Suppliers" subtitle="Keep supplier records available for procurement and operational context.">
      <template #actions>
        <AppButton v-if="auth.hasPermission('suppliers:write')" @click="open = true">New supplier</AppButton>
      </template>
    </PageHeader>
    <AppCard>
      <DataToolbar v-model:search="search" search-label="Search suppliers" search-placeholder="Company name" :count="suppliers.length" noun="suppliers" />
      <ErrorState v-if="query.isError.value" description="Suppliers could not be loaded."><AppButton variant="outline" @click="query.refetch()">Retry</AppButton></ErrorState>
      <TableSkeleton v-else-if="query.isLoading.value" :columns="['Supplier', 'Workspace']" />
      <div v-else class="table-wrap">
        <table class="responsive-table">
          <thead><tr><th>Supplier</th><th>Workspace</th></tr></thead>
          <tbody>
            <tr v-for="supplier in suppliers" :key="supplier.id"><td data-label="Supplier"><strong>{{ supplier.company_name }}</strong></td><td data-label="Workspace" class="mono">{{ supplier.apertureTenantId || '—' }}</td></tr>
            <tr v-if="!suppliers.length">
              <td colspan="2">
                <EmptyState title="No suppliers found" description="Create supplier records so procurement context is available across the demo.">
                  <AppButton v-if="auth.hasPermission('suppliers:write')" @click="open = true">Create supplier</AppButton>
                </EmptyState>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </AppCard>

    <AppModal :open="open" title="Create supplier" description="Add a vendor record for this tenant." @close="open = false">
      <form class="stack" novalidate @submit.prevent="createSupplier">
        <AppInput v-model="companyName" label="Company name" placeholder="Northwind Office Supplies" :error="formError" />
        <InlineFormError :message="formError" />
        <div class="form-actions">
          <AppButton variant="outline" type="button" @click="open = false">Cancel</AppButton>
          <AppButton type="submit">Save supplier</AppButton>
        </div>
      </form>
    </AppModal>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppModal from '@/components/ui/AppModal.vue'
import DataToolbar from '@/components/ui/DataToolbar.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import InlineFormError from '@/components/ui/InlineFormError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { referenceService } from '@/api/services/referenceService'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'
import { errorMessage, required } from '@/lib/formValidation'

const app = useAppStore()
const auth = useAuthStore()
const client = useQueryClient()
const search = ref('')
const open = ref(false)
const companyName = ref('')
const formError = ref<string | undefined>()
const query = useQuery({ queryKey: ['suppliers', search], queryFn: () => referenceService.suppliers(search.value) })
const suppliers = computed(() => query.data.value?.items ?? [])
watch(search, () => query.refetch())

async function createSupplier() {
  if (!auth.hasPermission('suppliers:write')) return
  const name = companyName.value.trim()
  const validationError = required(name, 'Company name is required.')
  if (validationError) {
    formError.value = validationError
    return
  }
  formError.value = undefined
  try {
    await referenceService.createSupplier(name)
    open.value = false
    companyName.value = ''
    app.toast('Supplier created', `${name} is available in this tenant.`, 'success')
    await client.invalidateQueries({ queryKey: ['suppliers'] })
    await query.refetch()
  } catch (error) {
    formError.value = errorMessage(error, 'Supplier could not be saved.')
  }
}
</script>
