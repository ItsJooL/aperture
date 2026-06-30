<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Product detail"
      :title="product?.name || 'Product'"
      subtitle="Review pricing, availability and catalogue information before using this item in billing."
      :breadcrumbs="[{ label: 'Products', to: '/products' }, { label: product?.name || 'Product' }]"
    >
      <template #actions>
        <RouterLink v-if="auth.hasPermission('invoices:write')" to="/invoices/new" class="button button-primary">Use in invoice</RouterLink>
        <AppButton v-if="auth.hasPermission('products:write')" variant="outline" @click="editOpen = true" :disabled="!product">Edit</AppButton>
        <AppButton v-if="auth.hasPermission('products:archive') && product && product.active !== false" variant="danger" @click="deactivateOpen = true">Deactivate</AppButton>
        <AppButton v-else-if="auth.hasPermission('products:write') && product" variant="outline" @click="activateProduct">Reactivate</AppButton>
      </template>
    </PageHeader>

    <PageSkeleton v-if="query.isLoading.value" />
    <ErrorState v-else-if="query.isError.value || !product" description="This product could not be found."><RouterLink to="/products" class="button button-outline">Back to products</RouterLink></ErrorState>
    <template v-else>
      <div class="grid grid-3">
        <StatCard label="Unit price" :value="currency(product.unit_price)" detail="Default invoice price" />
        <StatCard label="Status" :value="product.active === false ? 'Inactive' : 'Active'" detail="Availability for new invoices" />
        <StatCard label="SKU" :value="product.sku" detail="Catalogue reference" />
      </div>
      <section class="grid grid-2" style="margin-top:1rem;">
        <AppCard>
          <h2 class="card-title">Catalogue details</h2>
          <p class="card-description">Information shown to the billing team when selecting invoice items.</p>
          <div class="detail-list" style="margin-top:1rem;">
            <div class="detail-row"><span class="muted">Category</span><strong>{{ product.category || 'General' }}</strong></div>
            <div class="detail-row"><span class="muted">Description</span><strong>{{ product.description || '—' }}</strong></div>
            <div class="detail-row"><span class="muted">Record</span><span class="mono">{{ product.id }}</span></div>
          </div>
        </AppCard>
        <AppCard>
          <h2 class="card-title">Usage guidance</h2>
          <p class="card-description">Keep products active only while they should be available to new invoices.</p>
          <div class="timeline" style="margin-top:1rem;">
            <div class="timeline-item"><div class="timeline-dot"><Check class="timeline-icon" aria-hidden="true" /></div><div><strong>Confirm pricing</strong><p class="muted">Update the unit price before adding it to customer invoices.</p></div></div>
            <div class="timeline-item"><div class="timeline-dot"><Archive class="timeline-icon" aria-hidden="true" /></div><div><strong>Deactivate retired items</strong><p class="muted">Inactive products stay visible for reporting but should not be selected for new billing.</p></div></div>
          </div>
        </AppCard>
      </section>
    </template>

    <AppModal :open="editOpen" title="Edit product" description="Update pricing or catalogue details." @close="editOpen = false">
      <ProductForm v-model:error="formError" :initial-value="product || undefined" submit-label="Update product" show-cancel @submit="updateProduct" @cancel="editOpen = false" />
    </AppModal>
    <ConfirmDialog :open="deactivateOpen" title="Deactivate product?" description="The product will remain in history but should not be used on new invoices." confirm-label="Deactivate product" tone="danger" @cancel="deactivateOpen = false" @confirm="deactivateProduct" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Archive, Check } from '@lucide/vue'
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
import ProductForm from '@/components/product/ProductForm.vue'
import { productService, type ProductInput } from '@/api/services/productService'
import { currency } from '@/lib/utils'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'
import { errorMessage } from '@/lib/formValidation'

const route = useRoute()
const app = useAppStore()
const auth = useAuthStore()
const queryClient = useQueryClient()
const id = computed(() => String(route.params.id))
const query = useQuery({ queryKey: ['product', id], queryFn: () => productService.get(id.value) })
const product = computed(() => query.data.value)
const editOpen = ref(false)
const deactivateOpen = ref(false)
const formError = ref<string | undefined>()
async function updateProduct(input: ProductInput) {
  if (!auth.hasPermission('products:write')) return
  formError.value = undefined
  try {
    await productService.update(id.value, input)
    editOpen.value = false
    app.toast('Product updated', 'Catalogue changes have been saved.', 'success')
    await query.refetch(); await queryClient.invalidateQueries({ queryKey: ['products'] })
  } catch (error) {
    formError.value = errorMessage(error, 'Product could not be updated.')
  }
}
async function deactivateProduct() {
  if (!auth.hasPermission('products:archive')) return
  await productService.deactivate(id.value)
  deactivateOpen.value = false
  app.toast('Product deactivated', 'It will be excluded from active catalogue workflows.', 'success')
  await query.refetch(); await queryClient.invalidateQueries({ queryKey: ['products'] })
}
async function activateProduct() {
  if (!auth.hasPermission('products:write')) return
  await productService.activate(id.value)
  app.toast('Product reactivated', 'It can be used in invoice workflows again.', 'success')
  await query.refetch(); await queryClient.invalidateQueries({ queryKey: ['products'] })
}
</script>
