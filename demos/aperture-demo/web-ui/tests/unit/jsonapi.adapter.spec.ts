import { describe, expect, it } from 'vitest'
import { collectionFromDocument, jsonApiCreateDocument, jsonApiUpdateDocument, resourceIdentifier, singleFromDocument } from '@/api/jsonapi/adapter'

describe('jsonapi adapter', () => {
  it('maps JSON:API resources to flat domain objects without dropping relationships', () => {
    const result = collectionFromDocument({
      data: [
        {
          type: 'customers',
          id: '1',
          attributes: { name: 'Acme' },
          relationships: { invoices: { data: [{ type: 'invoices', id: 'inv-1' }] } },
        },
      ],
    })

    expect(result[0]).toMatchObject({
      id: '1',
      type: 'customers',
      name: 'Acme',
      relationships: { invoices: { data: [{ type: 'invoices', id: 'inv-1' }] } },
    })
  })

  it('returns null when a single-resource response contains a collection', () => {
    expect(singleFromDocument({ data: [] })).toBeNull()
  })

  it('creates JSON:API documents for POST requests', () => {
    expect(jsonApiCreateDocument('customers', { name: 'Acme' })).toEqual({
      data: { type: 'customers', attributes: { name: 'Acme' }, relationships: undefined },
    })
  })

  it('creates JSON:API documents for PATCH requests with relationship identifiers', () => {
    expect(jsonApiUpdateDocument('invoices', 'inv-1', { status: 'issued' }, { customer: resourceIdentifier('customers', 'cust-1') })).toMatchObject({
      data: {
        type: 'invoices',
        id: 'inv-1',
        attributes: { status: 'issued' },
        relationships: { customer: { data: { type: 'customers', id: 'cust-1' } } },
      },
    })
  })
})
