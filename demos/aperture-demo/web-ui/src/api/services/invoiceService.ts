import { apiFetch } from '@/api/http/client'
import { resourceIdentifier, singleFromDocument } from '@/api/jsonapi/adapter'
import { createResource, getResource, listResources, updateResource } from './resourceService'
import type { JsonApiDocument, ResourceIdentifier } from '@/api/jsonapi/types'
import type { Invoice, LineItem, Payment } from '@/api/types/domain'

export type InvoiceLineInput = {
  productId?: string
  billable?: ResourceIdentifier | null
  description: string
  quantity: number
  unit_price: number
}

export type InvoiceInput = {
  customerId: string
  status: string
  lines: InvoiceLineInput[]
}

export const invoiceService = {
  list: (search?: string) => listResources<Invoice>('invoices', { search, include: ['customer', 'lineItems', 'payments'], sort: ['-id'], paginate: false }),
  get: (id: string) => getResource<Invoice>('invoices', id, ['customer', 'lineItems', 'payments']),
  listLineItems: () => listResources<LineItem>('lineitems', { include: ['invoice', 'product'] }),
  async create(input: InvoiceInput) {
    const amount = input.lines.reduce((total, line) => total + Number(line.quantity || 0) * Number(line.unit_price || 0), 0)
    const invoiceLid = `invoice-${crypto.randomUUID()}`
    const response = await runAtomicOperation({
      'atomic:operations': [
        {
          op: 'add',
          data: {
            type: 'invoices',
            lid: invoiceLid,
            attributes: { amount, status: input.status.toUpperCase() },
            relationships: { customer: { data: resourceIdentifier('customers', input.customerId) } },
          },
        },
        ...input.lines.map((line) => {
          const product = line.productId ? resourceIdentifier('products', line.productId) : line.billable?.type === 'products' ? line.billable : null
          const billable = line.billable ?? product
          return {
            op: 'add',
            data: {
              type: 'lineitems',
              lid: `line-${crypto.randomUUID()}`,
              attributes: {
                description: line.description,
                quantity: line.quantity,
                unit_price: line.unit_price,
                price: line.quantity * line.unit_price,
              },
              relationships: {
                invoice: { data: { type: 'invoices', lid: invoiceLid } },
                ...(product ? { product: { data: product } } : {}),
                ...(billable ? { billable: { data: billable } } : {}),
              },
            },
          }
        }),
      ],
    })
    const document = (response as { 'atomic:results'?: JsonApiDocument<Invoice>[] })['atomic:results']?.[0]
    return document ? singleFromDocument<Invoice>(document) : null
  },
  updateStatus: (id: string, status: string) => updateResource<Invoice>('invoices', id, { status }),
  recordPayment: (invoiceId: string, amount: number) => createResource<Payment>('payments', { amount }, { invoice: resourceIdentifier('invoices', invoiceId) }),
}

export async function runAtomicOperation(payload: unknown) {
  return apiFetch('/api/v3/operations', {
    method: 'POST',
    jsonApi: true,
    body: payload,
    headers: {
      Accept: 'application/vnd.api+json; ext="https://jsonapi.org/ext/atomic"',
      'Content-Type': 'application/vnd.api+json; ext="https://jsonapi.org/ext/atomic"',
    },
  })
}
