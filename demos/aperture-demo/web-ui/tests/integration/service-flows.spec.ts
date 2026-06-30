import { describe, expect, it } from 'vitest'
import { authService } from '@/api/services/authService'
import { customerService } from '@/api/services/customerService'
import { invoiceService } from '@/api/services/invoiceService'

describe('service integration flows with mocked HTTP API', () => {
  it('logs in through the real auth service against MSW', async () => {
    const result = await authService.login('viewer@aperture.local', 'aperture-demo')
    expect(result.accessToken).toBe('demo-access-token')
    expect(result.user?.roleNames).toContain('Viewer')
  })

  it('lists and creates customers through JSON:API services', async () => {
    const initial = await customerService.list('Northstar')
    expect(initial.items).toHaveLength(1)
    expect(initial.items[0].name).toBe('Northstar Finance')

    const created = await customerService.create({ name: 'Fern Analytics', email: 'finance@fern.example' })
    expect(created).toMatchObject({ type: 'customers', name: 'Fern Analytics' })

    const afterCreate = await customerService.list('Fern')
    expect(afterCreate.items.map((customer) => customer.name)).toContain('Fern Analytics')
  })

  it('creates an invoice and its line item through the guided workflow service', async () => {
    const invoice = await invoiceService.create({
      customerId: 'cust-001',
      status: 'draft',
      lines: [{ productId: 'prod-001', description: 'Integration Starter Plan', quantity: 2, unit_price: 249 }],
    })

    expect(invoice).toMatchObject({ type: 'invoices', amount: 498, status: 'draft' })

    const lineItems = await invoiceService.listLineItems()
    expect(lineItems.items).toEqual(expect.arrayContaining([expect.objectContaining({ description: 'Integration Starter Plan', price: 498 })]))
  })
})
