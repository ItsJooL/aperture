import { createResource, getResource, listResources, updateResource } from './resourceService'
import type { Product, ServicePackage, SubscriptionPlan, Supplier } from '@/api/types/domain'

export type ProductInput = Pick<Product, 'name' | 'sku' | 'unit_price'> & {
  description?: string
  category?: string
  active?: boolean
}

export type ServicePackageInput = Pick<ServicePackage, 'name' | 'sku' | 'unit_price'> & {
  description?: string
  active?: boolean
}

export type SubscriptionPlanInput = Pick<SubscriptionPlan, 'name' | 'sku' | 'unit_price' | 'billing_interval'> & {
  description?: string
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

export const servicePackageService = {
  list: (search?: string, pageSize = 20) => listResources<ServicePackage>('servicepackages', { search, sort: ['name'], pageSize }),
  create: (input: ServicePackageInput) => createResource<ServicePackage>('servicepackages', input),
  update: (id: string, input: Partial<ServicePackageInput>) => updateResource<ServicePackage>('servicepackages', id, input),
}

export const subscriptionPlanService = {
  list: (search?: string, pageSize = 20) => listResources<SubscriptionPlan>('subscriptionplans', { search, sort: ['name'], pageSize }),
  create: (input: SubscriptionPlanInput) => createResource<SubscriptionPlan>('subscriptionplans', input),
  update: (id: string, input: Partial<SubscriptionPlanInput>) => updateResource<SubscriptionPlan>('subscriptionplans', id, input),
}
