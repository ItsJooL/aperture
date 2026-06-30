import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, queryString } from '@/api/http/client'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('queryString', () => {
  it('serializes JSON:API filter, include and pagination parameters', () => {
    expect(queryString({
      include: ['customer', 'lineItems'],
      'page[number]': 2,
      'page[size]': 25,
      'filter[customers.name][infix]': 'north',
      empty: '',
      missing: undefined,
    })).toBe('?include=customer%2ClineItems&page%5Bnumber%5D=2&page%5Bsize%5D=25&filter%5Bcustomers.name%5D%5Binfix%5D=north')
  })
})

describe('apiFetch', () => {
  it('preserves explicit JSON:API atomic accept headers', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }))

    await apiFetch('/api/v3/operations', {
      method: 'POST',
      jsonApi: true,
      headers: {
        Accept: 'application/vnd.api+json; ext="https://jsonapi.org/ext/atomic"',
        'Content-Type': 'application/vnd.api+json; ext="https://jsonapi.org/ext/atomic"',
      },
      body: { 'atomic:operations': [] },
    })

    const headers = fetchMock.mock.calls[0][1]?.headers as Headers
    expect(headers.get('Accept')).toBe('application/vnd.api+json; ext="https://jsonapi.org/ext/atomic"')
    expect(headers.get('Content-Type')).toBe('application/vnd.api+json; ext="https://jsonapi.org/ext/atomic"')
  })
})
