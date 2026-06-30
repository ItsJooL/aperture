import { createResource, getResource, listResources, updateResource } from './resourceService'
import type { Product, Supplier } from '@/api/types/domain'

export type ProductInput = Pick<Product, 'name' | 'sku' | 'unit_price'> & {
  description?: string
  category?: string
  active?: boolean
}

export const productService = {
  list: (search?: string, pageSize = 20) => listResources<Product>('products', { search, include: ['currency'], sort: ['name'], pageSize }),
  get: (id: string) => getResource<Product>('products', id, ['currency', 'lineItems']),
  create: (input: ProductInput) => createResource<Product>('products', input),
  update: (id: string, input: Partial<ProductInput>) => updateResource<Product>('products', id, input),
  deactivate: (id: string) => updateResource<Product>('products', id, { active: false }),
  activate: (id: string) => updateResource<Product>('products', id, { active: true }),
  listSuppliers: (search?: string) => listResources<Supplier>('suppliers', { search, sort: ['company_name'] }),
}
