/**
 * In-memory data store for MSW mock handlers.
 * resetMockData() is called by tests/setup.ts after each test to restore defaults.
 */

import type { Customer, Invite, ApiKey, ServiceAccount, Product, ServicePackage, Invoice, LineItem } from '@/api/types/domain'

// ── Customers ────────────────────────────────────────────────────────────────

let customers: Customer[] = []
let products: Product[] = []
let servicePackages: ServicePackage[] = []
let invoices: Invoice[] = []
let lineItems: LineItem[] = []
let invites: Invite[] = []
let serviceAccounts: ServiceAccount[] = []
let apiKeys: ApiKey[] = []

function seed() {
  customers = [
    { id: 'cust-001', type: 'customers', name: 'Northstar Finance', email: 'billing@northstar.example' },
    { id: 'cust-002', type: 'customers', name: 'Harbour Health', email: 'accounts@harbour.example' },
  ]

  products = [
    { id: 'prod-001', type: 'products', name: 'Integration Starter', sku: 'INT-001', unit_price: 249, active: true },
    { id: 'prod-002', type: 'products', name: 'Enterprise Suite', sku: 'ENT-001', unit_price: 999, active: true },
  ]

  servicePackages = [
    { id: 'svcpack-001', type: 'servicepackages', name: 'Priority onboarding', sku: 'SVC-ONBOARD', unit_price: 750, active: true },
    { id: 'svcpack-002', type: 'servicepackages', name: 'Quarterly architecture review', sku: 'SVC-ARCH', unit_price: 1200, active: true },
  ]

  invoices = [
    { id: 'invo-001', type: 'invoices', amount: 249, status: 'draft' },
  ]

  lineItems = [
    { id: 'line-001', type: 'lineitems', description: 'Integration Starter Plan', quantity: 1, unit_price: 249, price: 249 },
  ]

  invites = [
    { inviteId: 'invite-001', tenantId: 'tenant-demo', status: 'PENDING', roleNames: ['Viewer'] },
    { inviteId: 'invite-002', tenantId: 'tenant-demo', status: 'PENDING', roleNames: ['TenantAdmin'] },
  ]

  serviceAccounts = [
    { id: 'svc-001', tenantId: 'tenant-demo', clientId: 'client-svc-001', status: 'ACTIVE' },
  ]

  apiKeys = [
    { id: 'key-001', tenantId: 'tenant-demo', serviceAccountId: 'svc-001', status: 'ACTIVE' },
  ]
}

seed()

export function resetMockData() {
  seed()
}

// ── Accessors & mutators ──────────────────────────────────────────────────────

export function getCustomers() { return customers }
export function setCustomers(next: Customer[]) { customers = next }

export function getProducts() { return products }
export function setProducts(next: Product[]) { products = next }

export function getServicePackages() { return servicePackages }
export function setServicePackages(next: ServicePackage[]) { servicePackages = next }

export function getInvoices() { return invoices }
export function setInvoices(next: Invoice[]) { invoices = next }

export function getLineItems() { return lineItems }
export function setLineItems(next: LineItem[]) { lineItems = next }

export function getInvites() { return invites }
export function setInvites(next: Invite[]) { invites = next }

export function getServiceAccounts() { return serviceAccounts }
export function setServiceAccounts(next: ServiceAccount[]) { serviceAccounts = next }

export function getApiKeys() { return apiKeys }
export function setApiKeys(next: ApiKey[]) { apiKeys = next }
