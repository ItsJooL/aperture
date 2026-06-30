export type DemoPersona = {
  id: string
  label: string
  tenant: string
  role: string
  username: string
  password: string
  description: string
  defaultForTenant?: boolean
}

export const demoPersonas: DemoPersona[] = [
  {
    id: 'acme-accountant',
    label: 'Acme accountant',
    tenant: 'Acme Corp',
    role: 'Accountant',
    username: 'accountant@acme.com',
    password: 'Accountant123!',
    description: 'Create customers, products, invoices and payments with finance ABAC access.',
    defaultForTenant: true,
  },
  {
    id: 'acme-admin',
    label: 'Acme tenant admin',
    tenant: 'Acme Corp',
    role: 'TenantAdmin',
    username: 'admin@acme.com',
    password: 'AcmeAdmin123!',
    description: 'Manage tenant users, invites and service access.',
  },
  {
    id: 'acme-viewer',
    label: 'Acme viewer',
    tenant: 'Acme Corp',
    role: 'Viewer',
    username: 'viewer@acme.com',
    password: 'Viewer123!',
    description: 'Read-only workspace access.',
  },
  {
    id: 'techstart-admin',
    label: 'TechStart admin',
    tenant: 'TechStart Inc',
    role: 'TenantAdmin',
    username: 'admin@techstart.com',
    password: 'TechAdmin123!',
    description: 'Administer the TechStart tenant and inspect isolated data.',
    defaultForTenant: true,
  },
  {
    id: 'superadmin',
    label: 'Platform super admin',
    tenant: 'Platform',
    role: 'SuperAdmin',
    username: 'superadmin@framework.local',
    password: 'changeme-local-only',
    description: 'Review tenants and system-level administration.',
    defaultForTenant: true,
  },
]

export const demoTenants = Array.from(new Set(demoPersonas.map((persona) => persona.tenant)))

export const demoTenantIds: Record<string, string> = {
  'Acme Corp': '00000000-0000-0000-0000-000000000001',
  'TechStart Inc': '00000000-0000-0000-0000-000000000002',
  'Platform': '',
}
export function personaById(id: string) {
  return demoPersonas.find((persona) => persona.id === id)
}

export function defaultPersonaForTenant(tenant: string) {
  return demoPersonas.find((persona) => persona.tenant === tenant && persona.defaultForTenant) ?? demoPersonas.find((persona) => persona.tenant === tenant)
}

export function personaForUsername(username?: string | null) {
  return demoPersonas.find((persona) => persona.username === username)
}
