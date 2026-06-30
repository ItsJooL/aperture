import { createResource, listResources } from './resourceService'
import type { Currency, Supplier } from '@/api/types/domain'

export const referenceService = {
  currencies: () => listResources<Currency>('currencies', { sort: ['code'] }),
  suppliers: (search?: string) => listResources<Supplier>('suppliers', { search, sort: ['company_name'] }),
  createSupplier: (company_name: string) => createResource<Supplier>('suppliers', { company_name }),
}
