/**
 * MSW (Mock Service Worker) request handlers for unit and integration tests.
 *
 * URL patterns mirror what the real Aperture API server exposes:
 *   /auth/*        — authentication endpoints
 *   /manage/tenants/*     — admin / tenant management endpoints
 *   /api/v3/:resource        — JSON:API CRUD endpoints
 */

import { http, HttpResponse } from 'msw'
import {
  getCustomers, setCustomers,
  getProducts, setProducts,
  getServicePackages,
  getInvoices, setInvoices,
  getLineItems, setLineItems,
  getInvites, setInvites,
  getServiceAccounts, setServiceAccounts,
  getApiKeys, setApiKeys,
} from './data'

// ── Helpers ──────────────────────────────────────────────────────────────────

const BASE = 'http://localhost:8080'

function url(path: string) {
  return `${BASE}${path}`
}

/** Wrap an array of domain objects in a minimal JSON:API collection document. */
function jsonApiCollection<T extends { id: string; type: string }>(items: T[]) {
  return {
    data: items.map((item) => {
      const { id, type, relationships, ...rest } = item as T & { relationships?: unknown }
      return {
        type,
        id,
        attributes: rest,
        relationships,
      }
    }),
    meta: { totalPages: 1, totalElements: items.length },
    links: {},
  }
}

/** Wrap a single domain object in a minimal JSON:API single-resource document. */
function jsonApiSingle<T extends { id: string; type: string }>(item: T) {
  const { id, type, relationships, ...rest } = item as T & { relationships?: unknown }
  return {
    data: {
      type,
      id,
      attributes: rest,
      relationships,
    },
  }
}

/** Extract attributes + id from a JSON:API POST/PATCH body. */
async function parseJsonApiBody(request: Request) {
  const body = await request.json() as { data?: { id?: string; attributes?: Record<string, unknown> } }
  return { id: body.data?.id, attributes: body.data?.attributes ?? {} }
}

function nanoid() {
  return Math.random().toString(36).slice(2, 10)
}

// ── Auth handlers ─────────────────────────────────────────────────────────────

const demoUser = {
  id: 'user-viewer',
  username: 'viewer@aperture.local',
  tenantId: 'tenant-demo',
  status: 'ACTIVE',
  roleNames: ['Viewer'],
}

const authHandlers = [
  http.post(url('/auth/login'), async ({ request }) => {
    const body = await request.json() as { username?: string; password?: string }
    if (body?.username === 'admin@acme.com' || body?.username === 'viewer@aperture.local') {
      return HttpResponse.json({
        accessToken: 'demo-access-token',
        refreshToken: 'demo-refresh-token',
        user: demoUser,
      })
    }
    return HttpResponse.json({ message: 'Invalid credentials' }, { status: 401 })
  }),

  http.post(url('/auth/logout'), () => new HttpResponse(null, { status: 204 })),

  http.get(url('/auth/me'), () => HttpResponse.json(demoUser)),
]

// ── Admin / tenant handlers ───────────────────────────────────────────────────

const adminHandlers = [
  // Invites list
  http.get(url('/manage/tenants/:tenantId/invites'), ({ params }) => {
    const tenantInvites = getInvites().filter((invite) => invite.tenantId === params.tenantId)
    return HttpResponse.json(tenantInvites)
  }),

  // Revoke invite
  http.delete(url('/manage/tenants/:tenantId/invites/:inviteId'), ({ params }) => {
    setInvites(getInvites().filter((invite) => invite.inviteId !== params.inviteId))
    return new HttpResponse(null, { status: 204 })
  }),

  // Service accounts list
  http.get(url('/manage/tenants/:tenantId/service-accounts'), ({ params }) => {
    return HttpResponse.json(getServiceAccounts().filter((account) => account.tenantId === params.tenantId))
  }),

  // Disable service account
  http.post(url('/manage/tenants/:tenantId/service-accounts/:accountId/disable'), ({ params }) => {
    const accounts = getServiceAccounts().map((account) =>
      account.id === params.accountId ? { ...account, status: 'DISABLED' } : account,
    )
    setServiceAccounts(accounts)
    const updated = accounts.find((account) => account.id === params.accountId)
    return HttpResponse.json(updated)
  }),

  // Personal API keys list (admin)
  http.get(url('/manage/tenants/:tenantId/personal-api-keys'), ({ params }) => {
    return HttpResponse.json(getApiKeys().filter((key) => key.tenantId === params.tenantId))
  }),

  // Revoke personal API key (admin)
  http.post(url('/manage/tenants/:tenantId/personal-api-keys/:keyId/revoke'), ({ params }) => {
    const keys = getApiKeys().map((key) =>
      key.id === params.keyId ? { ...key, status: 'DISABLED' } : key,
    )
    setApiKeys(keys)
    const updated = keys.find((key) => key.id === params.keyId)
    return HttpResponse.json(updated)
  }),
]

// ── JSON:API resource handlers ────────────────────────────────────────────────

// Helper to match resource name regardless of query params
function resourcePath(resource: string) {
  return url(`/api/v3/${resource}`)
}

const resourceHandlers = [
  // ── Atomic Operations ──────────────────────────────────────────────────────

  http.post(url('/api/v3/operations'), async ({ request }) => {
    const body = await request.json() as { 'atomic:operations'?: Array<{ op: string; data?: Record<string, unknown> }> }
    const operations = body['atomic:operations'] ?? []
    const atomicResults: JsonApiDocument<Invoice | LineItem>[] = []
    const lidToId: Record<string, string> = {} // Map logical IDs to real IDs

    for (const op of operations) {
      if (op.op === 'add' && op.data) {
        const data = op.data as any
        const type = data.type

        if (type === 'invoices') {
          const invoiceId = `invo-${nanoid()}`
          if (data.lid) lidToId[data.lid] = invoiceId
          const invoice: Invoice = {
            id: invoiceId,
            type: 'invoices',
            amount: data.attributes?.amount ?? 0,
            status: String(data.attributes?.status ?? 'draft').toLowerCase(),
          }
          setInvoices([...getInvoices(), invoice])
          atomicResults.push({
            data: {
              type: 'invoices',
              id: invoice.id,
              attributes: { amount: invoice.amount, status: invoice.status },
            },
          } as JsonApiDocument<Invoice>)
        } else if (type === 'lineitems') {
          const lineItemId = `line-${nanoid()}`
          if (data.lid) lidToId[data.lid] = lineItemId
          const lineItem: LineItem = {
            id: lineItemId,
            type: 'lineitems',
            description: data.attributes?.description ?? '',
            quantity: data.attributes?.quantity ?? 0,
            unit_price: data.attributes?.unit_price ?? 0,
            price: data.attributes?.price ?? 0,
            relationships: data.relationships,
          }
          setLineItems([...getLineItems(), lineItem])
          atomicResults.push({
            data: {
              type: 'lineitems',
              id: lineItem.id,
              attributes: {
                description: lineItem.description,
                quantity: lineItem.quantity,
                unit_price: lineItem.unit_price,
                price: lineItem.price,
              },
            },
          } as JsonApiDocument<LineItem>)
        }
      }
    }

    return HttpResponse.json({
      'atomic:results': atomicResults,
    }, {
      status: 201,
      headers: { 'Content-Type': 'application/vnd.api+json; ext="https://jsonapi.org/ext/atomic"' },
    })
  }),

  // ── Customers ──────────────────────────────────────────────────────────────

  http.get(resourcePath('customers'), ({ request }) => {
    const filterParam = new URL(request.url).searchParams.get('filter') ?? ''
    let searchTerm = ''
    // Parse RSQL filter: name=='*SearchTerm*'
    const match = filterParam.match(/name=='?\*([^'*]*)\*'?/)
    if (match) {
      searchTerm = match[1].toLowerCase()
    }
    const filtered = getCustomers().filter((customer) =>
      !customer.deletedAt && (!searchTerm || customer.name.toLowerCase().includes(searchTerm)),
    )
    return HttpResponse.json(jsonApiCollection(filtered), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  http.get(url('/api/v3/customers/:id'), ({ params }) => {
    const customer = getCustomers().find((customer) => customer.id === params.id)
    if (!customer) return new HttpResponse(null, { status: 404 })
    return HttpResponse.json(jsonApiSingle(customer), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  http.post(resourcePath('customers'), async ({ request }) => {
    const { attributes } = await parseJsonApiBody(request)
    const created = { id: `cust-${nanoid()}`, type: 'customers', name: String(attributes.name ?? ''), email: String(attributes.email ?? '') }
    setCustomers([...getCustomers(), created])
    return HttpResponse.json(jsonApiSingle(created), {
      status: 201,
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  http.patch(url('/api/v3/customers/:id'), async ({ params, request }) => {
    const { attributes } = await parseJsonApiBody(request)
    const updated = getCustomers().map((customer) =>
      customer.id === params.id ? { ...customer, ...attributes } : customer,
    )
    setCustomers(updated)
    const customer = updated.find((customer) => customer.id === params.id)
    if (!customer) return new HttpResponse(null, { status: 404 })
    return HttpResponse.json(jsonApiSingle(customer), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  // ── Products ───────────────────────────────────────────────────────────────

  http.get(resourcePath('products'), ({ request }) => {
    const searchParam = new URL(request.url).searchParams.get('filter[products.name][infix]') ?? ''
    const filtered = getProducts().filter((product) =>
      !searchParam || product.name.toLowerCase().includes(searchParam.toLowerCase()),
    )
    return HttpResponse.json(jsonApiCollection(filtered), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  http.get(url('/api/v3/products/:id'), ({ params }) => {
    const product = getProducts().find((product) => product.id === params.id)
    if (!product) return new HttpResponse(null, { status: 404 })
    return HttpResponse.json(jsonApiSingle(product), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  http.patch(url('/api/v3/products/:id'), async ({ params, request }) => {
    const { attributes } = await parseJsonApiBody(request)
    const updated = getProducts().map((product) =>
      product.id === params.id ? { ...product, ...attributes } : product,
    )
    setProducts(updated)
    const product = updated.find((product) => product.id === params.id)
    if (!product) return new HttpResponse(null, { status: 404 })
    return HttpResponse.json(jsonApiSingle(product), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  // ── Service Packages ──────────────────────────────────────────────────────

  http.get(resourcePath('servicepackages'), ({ request }) => {
    const searchParam = new URL(request.url).searchParams.get('filter[servicepackages.name][infix]') ?? ''
    const filtered = getServicePackages().filter((servicePackage) =>
      !searchParam || servicePackage.name.toLowerCase().includes(searchParam.toLowerCase()),
    )
    return HttpResponse.json(jsonApiCollection(filtered), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  // ── Invoices ───────────────────────────────────────────────────────────────

  http.get(resourcePath('invoices'), () => {
    return HttpResponse.json(jsonApiCollection(getInvoices()), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  http.post(resourcePath('invoices'), async ({ request }) => {
    const { attributes } = await parseJsonApiBody(request)
    const created: Invoice = {
      id: `invo-${nanoid()}`,
      type: 'invoices',
      amount: Number(attributes.amount ?? 0),
      status: String(attributes.status ?? 'draft'),
    }
    setInvoices([...getInvoices(), created])
    return HttpResponse.json(jsonApiSingle(created), {
      status: 201,
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  // ── Line Items ─────────────────────────────────────────────────────────────

  http.get(resourcePath('lineitems'), () => {
    return HttpResponse.json(jsonApiCollection(getLineItems()), {
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),

  http.post(resourcePath('lineitems'), async ({ request }) => {
    const { attributes } = await parseJsonApiBody(request)
    const created: LineItem = {
      id: `line-${nanoid()}`,
      type: 'lineitems',
      description: String(attributes.description ?? ''),
      quantity: Number(attributes.quantity ?? 0),
      unit_price: Number(attributes.unit_price ?? 0),
      price: Number(attributes.price ?? 0),
    }
    setLineItems([...getLineItems(), created])
    return HttpResponse.json(jsonApiSingle(created), {
      status: 201,
      headers: { 'Content-Type': 'application/vnd.api+json' },
    })
  }),
]

// ── GraphQL ────────────────────────────────────────────────────────────────────
//
// Elide's GraphQL responses use Relay-style connections (`{ edges: { node } }`) and are not
// JSON:API documents. This mocks the single query the Insights view relies on
// (see api-collection/11-graphql/01-query-invoices-with-customer-and-lineitems.bru).

function toConnection<T>(nodes: T[]) {
  return { edges: nodes.map((node) => ({ node })) }
}

const graphqlHandlers = [
  http.post(url('/graphql/v3'), async ({ request }) => {
    const body = await request.json() as { query?: string }
    const query = body.query ?? ''

    if (!query.includes('invoices')) {
      return HttpResponse.json({ data: null, errors: [{ message: `Unsupported mock GraphQL query: ${query}` }] })
    }

    const customer = getCustomers()[0]
    const items = getLineItems()

    const invoiceNodes = getInvoices().map((invoice) => ({
      id: invoice.id,
      amount: invoice.amount,
      status: invoice.status.toUpperCase(),
      customer: toConnection(customer ? [{ name: customer.name, email: customer.email }] : []),
      lineItems: toConnection(items.map((item) => ({ description: item.description, quantity: item.quantity, unit_price: item.unit_price }))),
    }))

    return HttpResponse.json({ data: { invoices: toConnection(invoiceNodes) } })
  }),
]

import type { JsonApiDocument } from '@/api/jsonapi/types'
import type { Invoice, LineItem } from '@/api/types/domain'

export const handlers = [
  ...authHandlers,
  ...adminHandlers,
  ...resourceHandlers,
  ...graphqlHandlers,
]
