import { afterEach, describe, expect, it, vi } from 'vitest'
import { edgeNodes, graphqlRequest, GraphQLRequestError } from '@/api/graphql/client'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

describe('graphqlRequest', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('posts the query to the versioned GraphQL endpoint and returns the data payload', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ data: { invoices: { edges: [] } } }),
    )

    const data = await graphqlRequest<{ invoices: unknown }>('query Invoices { invoices { edges { node { id } } } }')

    expect(data).toEqual({ invoices: { edges: [] } })
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/graphql/v3')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toMatchObject({ query: expect.stringContaining('invoices') })
  })

  it('throws a GraphQLRequestError for the GraphQL-shaped error response (HTTP 200, data: null, errors[])', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ data: null, errors: [{ message: 'Exception while fetching data (/invoices) : Bad request' }] }),
    )

    await expect(graphqlRequest('query Invoices { invoices { edges { node { id } } } }')).rejects.toMatchObject({
      name: 'GraphQLRequestError',
      message: 'Exception while fetching data (/invoices) : Bad request',
    })
  })

  it('surfaces GraphQL errors carried on a non-2xx transport failure', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ data: null, errors: [{ message: 'Unauthorized' }] }, 400),
    )

    let caught: unknown
    try {
      await graphqlRequest('query Invoices { invoices { edges { node { id } } } }')
    } catch (error) {
      caught = error
    }

    expect(caught).toBeInstanceOf(GraphQLRequestError)
    expect((caught as GraphQLRequestError).message).toBe('Unauthorized')
  })
})

describe('edgeNodes', () => {
  it('flattens a Relay-style connection into a plain array of nodes', () => {
    const connection = { edges: [{ node: { name: 'Acme' } }, { node: { name: 'Fern' } }] }
    expect(edgeNodes(connection)).toEqual([{ name: 'Acme' }, { name: 'Fern' }])
  })

  it('returns an empty array for a missing or empty connection', () => {
    expect(edgeNodes(undefined)).toEqual([])
    expect(edgeNodes(null)).toEqual([])
    expect(edgeNodes({ edges: [] })).toEqual([])
  })
})
