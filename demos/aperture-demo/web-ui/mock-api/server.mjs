import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'

const PORT = Number(process.env.MOCK_API_PORT ?? 8081)
const HOST = process.env.MOCK_API_HOST ?? '127.0.0.1'

const demoUser = {
  id: 'user-001',
  username: 'admin@aperture.local',
  tenantId: 'tenant-demo',
  status: 'ACTIVE',
  superAdmin: true,
  roleNames: ['TenantAdmin'],
}

const demoViewerUser = {
  id: 'user-002',
  username: 'viewer@aperture.local',
  tenantId: 'tenant-demo',
  status: 'ACTIVE',
  superAdmin: false,
  roleNames: ['Viewer'],
}

function createData() {
  return {
    users: [structuredClone(demoUser), structuredClone(demoViewerUser)],
    customers: [
      { id: 'cust-001', type: 'customers', name: 'Northstar Finance', email: 'billing@northstar.example', phone_number: '+353 1 555 0100', version: 3, apertureTenantId: 'tenant-demo' },
      { id: 'cust-002', type: 'customers', name: 'Harbour Health', email: 'accounts@harbour.example', phone_number: '+353 1 555 0188', version: 2, apertureTenantId: 'tenant-demo' },
      { id: 'cust-003', type: 'customers', name: 'Moss & Co Retail', email: 'finance@moss.example', phone_number: '+44 20 5555 0122', version: 1, apertureTenantId: 'tenant-demo' },
    ],
    products: [
      { id: 'prod-001', type: 'products', name: 'Integration Starter Plan', sku: 'INT-STARTER', description: 'Entry package for managed integration flows.', category: 'Subscription', unit_price: 249, active: true, version: 2, apertureTenantId: 'tenant-demo' },
      { id: 'prod-002', type: 'products', name: 'Premium API Gateway', sku: 'API-GW-PREM', description: 'Advanced gateway package with tenant isolation.', category: 'Platform', unit_price: 799, active: true, version: 4, apertureTenantId: 'tenant-demo' },
      { id: 'prod-003', type: 'products', name: 'Implementation Workshop', sku: 'SERV-WORKSHOP', description: 'Remote workshop for onboarding and design.', category: 'Services', unit_price: 1200, active: true, version: 1, apertureTenantId: 'tenant-demo' },
    ],
    invoices: [
      { id: 'inv-1001', type: 'invoices', amount: 1847, status: 'issued', apertureTenantId: 'tenant-demo' },
      { id: 'inv-1002', type: 'invoices', amount: 799, status: 'paid', apertureTenantId: 'tenant-demo' },
      { id: 'inv-1003', type: 'invoices', amount: 1200, status: 'draft', apertureTenantId: 'tenant-demo' },
      { id: 'inv-1004', type: 'invoices', amount: 249, status: 'overdue', apertureTenantId: 'tenant-demo' },
    ],
    lineitems: [],
    payments: [
      { id: 'pay-001', type: 'payments', amount: 799 },
      { id: 'pay-002', type: 'payments', amount: 249 },
    ],
    suppliers: [
      { id: 'sup-001', type: 'suppliers', company_name: 'Sage Supply Co', apertureTenantId: 'tenant-demo' },
      { id: 'sup-002', type: 'suppliers', company_name: 'Stone Cloud Services', apertureTenantId: 'tenant-demo' },
    ],
    currencies: [
      { id: 'EUR', type: 'currencies', code: 'EUR' },
      { id: 'USD', type: 'currencies', code: 'USD' },
    ],
    invites: [
      { inviteId: 'inv-tenant-001', tenantId: 'tenant-demo', status: 'PENDING', expiresAt: new Date(Date.now() + 86400000 * 6).toISOString(), roleNames: ['Viewer'] },
    ],
    serviceAccounts: [
      { id: 'svc-001', tenantId: 'tenant-demo', clientId: 'aperture-demo-worker', status: 'ACTIVE', expiresAt: new Date(Date.now() + 86400000 * 90).toISOString() },
    ],
    apiKeys: [
      { id: 'key-demo-001', tenantId: 'tenant-demo', serviceAccountId: 'svc-001', status: 'ACTIVE', createdAt: new Date().toISOString(), lastUsedAt: new Date().toISOString() },
    ],
  }
}

let db = createData()

function send(res, status, payload, contentType = 'application/json') {
  res.writeHead(status, {
    'Content-Type': contentType,
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,PATCH,PUT,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization,Content-Type,Accept',
  })
  if (payload === undefined || payload === null) {
    res.end()
    return
  }
  res.end(typeof payload === 'string' ? payload : JSON.stringify(payload))
}

function notFound(res) {
  send(res, 404, { message: 'Mock endpoint not found' })
}

async function readJson(req) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  if (!chunks.length) return {}
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    return {}
  }
}

function jsonApiHeaders(res, status = 200) {
  res.writeHead(status, {
    'Content-Type': 'application/vnd.api+json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,PATCH,PUT,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization,Content-Type,Accept',
  })
}

function toJsonApi(item) {
  const { id, type, relationships, ...attributes } = item
  return { id, type, attributes, relationships }
}

function sendJsonApi(res, payload, status = 200) {
  jsonApiHeaders(res, status)
  res.end(JSON.stringify(payload))
}

function sendJsonApiCollection(res, items) {
  sendJsonApi(res, { data: items.map(toJsonApi), meta: { page: { totalRecords: items.length } } })
}

function sendJsonApiSingle(res, item) {
  if (!item) {
    sendJsonApi(res, { errors: [{ title: 'Not found', detail: 'Record not found' }] }, 404)
    return
  }
  sendJsonApi(res, { data: toJsonApi(item) })
}

function resourceCollections() {
  return {
    customers: db.customers,
    products: db.products,
    invoices: db.invoices,
    lineitems: db.lineitems,
    payments: db.payments,
    suppliers: db.suppliers,
    currencies: db.currencies,
  }
}

function filterCollection(collection, url) {
  const search = [...url.searchParams.entries()]
    .filter(([key]) => !['sort', 'include'].includes(key) && !key.startsWith('page['))
    .map(([, value]) => value)
    .join(' ')
    .toLowerCase()
  if (!search) return collection
  return collection.filter((item) => JSON.stringify(item).toLowerCase().includes(search))
}

async function handleAuth(req, res, url) {
  if (req.method === 'POST' && url.pathname === '/auth/login') {
    const body = await readJson(req)
    const user = String(body.username ?? '').toLowerCase().includes('viewer') ? demoViewerUser : demoUser
    send(res, 200, { accessToken: 'demo-access-token', refreshToken: 'demo-refresh-token', user })
    return true
  }
  if (req.method === 'POST' && url.pathname === '/auth/accept-invite') {
    send(res, 200, { accessToken: 'demo-access-token', refreshToken: 'demo-refresh-token', user: demoViewerUser })
    return true
  }
  if (req.method === 'POST' && url.pathname === '/auth/logout') {
    send(res, 200, { ok: true })
    return true
  }
  if (req.method === 'GET' && url.pathname === '/auth/me') {
    send(res, 200, demoUser)
    return true
  }
  if (req.method === 'POST' && url.pathname === '/auth/change-password') {
    send(res, 200, { ok: true })
    return true
  }
  return false
}

async function handleAdmin(req, res, url) {
  if (req.method === 'GET' && url.pathname === '/manage/tenants') {
    send(res, 200, { items: [{ id: 'tenant-demo', name: 'Aperture Demo', status: 'ACTIVE' }], totalElements: 1 })
    return true
  }
  if (req.method === 'POST' && url.pathname === '/manage/tenants') {
    const body = await readJson(req)
    send(res, 200, { id: `tenant-${Date.now()}`, name: body.name ?? 'New tenant', status: 'ACTIVE' })
    return true
  }

  const usersMatch = url.pathname.match(/^\/manage\/tenants\/([^/]+)\/users$/)
  if (usersMatch && req.method === 'GET') {
    send(res, 200, { items: db.users, totalElements: db.users.length })
    return true
  }
  if (usersMatch && req.method === 'POST') {
    const body = await readJson(req)
    const user = { id: `user-${Date.now()}`, username: body.username ?? 'new-user@example.com', tenantId: usersMatch[1], status: 'ACTIVE', superAdmin: false, roleNames: ['Viewer'] }
    db.users.unshift(user)
    send(res, 200, user)
    return true
  }

  const rolesMatch = url.pathname.match(/^\/manage\/tenants\/([^/]+)\/users\/([^/]+)\/roles$/)
  if (rolesMatch && req.method === 'PUT') {
    const body = await readJson(req)
    const user = db.users.find((candidate) => candidate.id === rolesMatch[2])
    if (!user) send(res, 404, { message: 'User not found' })
    else {
      user.roleNames = body.roleNames ?? []
      user.superAdmin = user.roleNames.some((role) => /admin/i.test(role))
      send(res, 200, user)
    }
    return true
  }

  const invitesMatch = url.pathname.match(/^\/manage\/tenants\/([^/]+)\/invites$/)
  if (invitesMatch && req.method === 'GET') {
    send(res, 200, db.invites)
    return true
  }
  if (invitesMatch && req.method === 'POST') {
    const body = await readJson(req)
    db.invites.unshift({ inviteId: `invite-${Date.now()}`, tenantId: invitesMatch[1], status: 'PENDING', roleNames: body.roleNames ?? ['Viewer'] })
    send(res, 200, db.invites[0])
    return true
  }

  const revokeInviteMatch = url.pathname.match(/^\/manage\/tenants\/([^/]+)\/invites\/([^/]+)$/)
  if (revokeInviteMatch && req.method === 'DELETE') {
    const index = db.invites.findIndex((invite) => invite.inviteId === revokeInviteMatch[2])
    if (index >= 0) db.invites.splice(index, 1)
    send(res, 200, { ok: true })
    return true
  }

  const serviceAccountsMatch = url.pathname.match(/^\/manage\/tenants\/([^/]+)\/service-accounts$/)
  if (serviceAccountsMatch && req.method === 'GET') {
    send(res, 200, db.serviceAccounts)
    return true
  }
  if (serviceAccountsMatch && req.method === 'POST') {
    db.serviceAccounts.unshift({ id: `svc-${Date.now()}`, tenantId: serviceAccountsMatch[1], clientId: 'new-service-account', status: 'ACTIVE' })
    send(res, 200, db.serviceAccounts[0])
    return true
  }

  const disableServiceAccountMatch = url.pathname.match(/^\/manage\/tenants\/([^/]+)\/service-accounts\/([^/]+)\/disable$/)
  if (disableServiceAccountMatch && req.method === 'POST') {
    const account = db.serviceAccounts.find((candidate) => candidate.id === disableServiceAccountMatch[2])
    if (!account) send(res, 404, { message: 'Service account not found' })
    else { account.status = 'DISABLED'; send(res, 200, account) }
    return true
  }

  const apiKeysMatch = url.pathname.match(/^\/manage\/tenants\/([^/]+)\/personal-api-keys$/)
  if (apiKeysMatch && req.method === 'GET') {
    send(res, 200, db.apiKeys)
    return true
  }

  const disableApiKeyMatch = url.pathname.match(/^\/manage\/tenants\/([^/]+)\/personal-api-keys\/([^/]+)\/revoke$/)
  if (disableApiKeyMatch && req.method === 'POST') {
    const key = db.apiKeys.find((candidate) => candidate.id === disableApiKeyMatch[2])
    if (!key) send(res, 404, { message: 'API key not found' })
    else { key.status = 'DISABLED'; send(res, 200, key) }
    return true
  }

  if (req.method === 'GET' && url.pathname === '/manage/migrations/status') {
    send(res, 200, { v1: 0, v2: 0, v3: 24 })
    return true
  }
  if (req.method === 'GET' && url.pathname === '/manage/sunset-impact') {
    send(res, 200, { v1: { affectedRecords: 0 }, v2: { affectedRecords: 0 } })
    return true
  }
  return false
}

async function handleJsonApi(req, res, url) {
  const match = url.pathname.match(/^\/api\/v3\/([^/]+)(?:\/([^/]+))?$/)
  if (!match) return false

  const [, resource, id] = match
  const collections = resourceCollections()
  const collection = collections[resource]
  if (!collection) return false

  if (req.method === 'GET' && !id) {
    sendJsonApiCollection(res, filterCollection(collection, url))
    return true
  }
  if (req.method === 'GET' && id) {
    sendJsonApiSingle(res, collection.find((item) => item.id === id))
    return true
  }
  if (req.method === 'POST' && !id) {
    const body = await readJson(req)
    const item = {
      id: `${resource.slice(0, 4)}-${randomUUID().slice(0, 8)}`,
      type: resource,
      ...(body.data?.attributes ?? {}),
      relationships: body.data?.relationships,
    }
    collection.unshift(item)
    sendJsonApiSingle(res, item)
    return true
  }
  if (req.method === 'PATCH' && id) {
    const body = await readJson(req)
    const item = collection.find((candidate) => candidate.id === id)
    if (!item) sendJsonApi(res, { errors: [{ title: 'Not found' }] }, 404)
    else {
      Object.assign(item, body.data?.attributes ?? {})
      if (body.data?.relationships) item.relationships = body.data.relationships
      send(res, 204, null, 'application/vnd.api+json')
    }
    return true
  }
  if (req.method === 'DELETE' && id) {
    const index = collection.findIndex((item) => item.id === id)
    if (index >= 0) collection.splice(index, 1)
    send(res, 204, null, 'application/vnd.api+json')
    return true
  }
  return false
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? `${HOST}:${PORT}`}`)
  if (req.method === 'OPTIONS') {
    send(res, 204, null)
    return
  }
  if (req.method === 'GET' && url.pathname === '/health') {
    send(res, 200, { ok: true, service: 'aperture-demo-mock-api' })
    return
  }
  if (req.method === 'POST' && url.pathname === '/__mock/reset') {
    db = createData()
    send(res, 200, { ok: true })
    return
  }

  try {
    if (await handleAuth(req, res, url)) return
    if (await handleAdmin(req, res, url)) return
    if (await handleJsonApi(req, res, url)) return
    notFound(res)
  } catch (error) {
    send(res, 500, { message: error instanceof Error ? error.message : 'Mock server error' })
  }
})

server.listen(PORT, HOST, () => {
  console.log(`Aperture mock API listening on http://${HOST}:${PORT}`)
})
