import { createResource, getResource, listResources, updateResource } from './resourceService'
import type { Customer } from '@/api/types/domain'

export type CustomerInput = Pick<Customer, 'name' | 'email'> & { phone_number?: string }

export const customerService = {
  list: (search?: string, pageSize = 20) => listResources<Customer>('customers', { search, include: ['invoices'], sort: ['name'], pageSize }),
  get: (id: string) => getResource<Customer>('customers', id, ['invoices']),
  create: (input: CustomerInput) => createResource<Customer>('customers', input),
  update: (id: string, input: Partial<CustomerInput>) => updateResource<Customer>('customers', id, input),
  archive: (id: string) => updateResource<Customer>('customers', id, { deletedAt: new Date().toISOString() }),
  restore: (id: string) => updateResource<Customer>('customers', id, { deletedAt: null }),
}
