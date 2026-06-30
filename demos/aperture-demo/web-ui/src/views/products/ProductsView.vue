<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Products"
      title="Product catalogue"
      subtitle="Maintain the services, subscriptions and one-off items that can be added to invoices."
    >
      <template #actions><AppButton v-if="auth.hasPermission('products:write')" @click="open = true">New product</AppButton></template>
    </PageHeader>

    <AppCard>
      <DataToolbar v-model:search="search" search-label="Search products" search-placeholder="Name, SKU or category" :count="products.length" noun="products" />
      <ErrorState v-if="query.isError.value" description="Products could not be loaded."><AppButton variant="outline" @click="query.refetch()">Retry</AppButton></ErrorState>
      <TableSkeleton v-else-if="query.isLoading.value" :columns="['Product', 'SKU', 'Category', 'Price', 'Status']" />
      <div v-else class="table-wrap">
        <table class="responsive-table">
          <thead><tr><th>Product</th><th>SKU</th><th>Category</th><th>Price</th><th>Status</th></tr></thead>
          <tbody>
            <tr v-for="product in products" :key="product.id" class="clickable" @click="$router.push(`/products/${product.id}`)">
              <td data-label="Product"><strong>{{ product.name }}</strong><div class="muted">{{ product.description || 'No description' }}</div></td>
              <td data-label="SKU" class="mono">{{ product.sku }}</td>
              <td data-label="Category">{{ product.category || 'General' }}</td>
              <td data-label="Price">{{ currency(product.unit_price) }}</td>
              <td data-label="Status"><StatusBadge :status="product.active === false ? 'inactive' : 'active'" /></td>
            </tr>
            <tr v-if="!products.length"><td colspan="5"><EmptyState title="No products found" description="Create a product before building customer invoices."><AppButton @click="open = true">Create product</AppButton></EmptyState></td></tr>
          </tbody>
        </table>
      </div>
    </AppCard>

    <AppModal :open="open" title="Create product" description="Add an item customers can be billed for." @close="open = false">
      <ProductForm v-model:error="formError" show-cancel @submit="createProduct" @cancel="open = false" />
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
import ProductForm from '@/components/product/ProductForm.vue'
import { productService, type ProductInput } from '@/api/services/productService'
import { currency } from '@/lib/utils'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'
import { errorMessage } from '@/lib/formValidation'

const app = useAppStore()
const auth = useAuthStore()
const client = useQueryClient()
const search = ref('')
const open = ref(false)
const formError = ref<string | undefined>()
const query = useQuery({ queryKey: ['products', search], queryFn: () => productService.list(search.value) })
const products = computed(() => query.data.value?.items ?? [])
watch(search, () => query.refetch())
async function createProduct(input: ProductInput) {
  if (!auth.hasPermission('products:write')) return
  formError.value = undefined
  try {
    await productService.create(input)
    open.value = false
    app.toast('Product created', `${input.name} can now be added to invoices.`, 'success')
    await client.invalidateQueries({ queryKey: ['products'] })
    await query.refetch()
  } catch (error) {
    formError.value = errorMessage(error, 'Product could not be saved.')
  }
}
</script>
