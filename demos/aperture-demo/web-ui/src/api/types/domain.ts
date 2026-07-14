import type { JsonApiRelationship } from '@/api/jsonapi/types'

export type Customer = {
  id: string
  type: string
  name: string
  email: string
  phone_number?: string
  version?: number
  deletedAt?: string | null
  apertureTenantId?: string
  relationships?: Record<string, JsonApiRelationship>
}

export type Product = {
  id: string
  type: string
  name: string
  sku: string
  description?: string
  category?: string
  unit_price: number
  active?: boolean
  version?: number
  apertureTenantId?: string
  relationships?: Record<string, JsonApiRelationship>
}

export type ServicePackage = {
  id: string
  type: string
  name: string
  sku: string
  description?: string
  unit_price: number
  active?: boolean
  version?: number
  apertureTenantId?: string
  relationships?: Record<string, JsonApiRelationship>
}

export type SubscriptionPlan = {
  id: string
  type: string
  name: string
  sku: string
  description?: string
  unit_price: number
  billing_interval: 'MONTHLY' | 'QUARTERLY' | 'ANNUAL'
  active?: boolean
  version?: number
  apertureTenantId?: string
  relationships?: Record<string, JsonApiRelationship>
}

export type Invoice = {
  id: string
  type: string
  amount: number
  status: 'draft' | 'issued' | 'paid' | 'overdue' | string
  apertureTenantId?: string
  relationships?: Record<string, JsonApiRelationship>
}

export type LineItem = {
  id: string
  type: string
  description: string
  quantity: number
  unit_price: number
  price: number
  relationships?: Record<string, JsonApiRelationship>
}

export type Payment = {
  id: string
  type: string
  amount: number
  relationships?: Record<string, JsonApiRelationship>
}

export type Supplier = {
  id: string
  type: string
  company_name: string
  apertureTenantId?: string
}

export type Currency = {
  id: string
  type: string
  code: string
}

export type Tenant = {
  id: string
  name: string
  status: string
}

export type User = {
  id: string
  username: string
  tenantId: string
  status: string
  superAdmin?: boolean
  roleNames?: string[]
  roles?: string[]
  attributes?: Record<string, unknown>
  profile?: Record<string, unknown>
  securityAttributes?: Record<string, unknown>
}

export type Invite = {
  inviteId: string
  tenantId: string
  status: string
  expiresAt?: string
  acceptedAt?: string
  roleNames?: string[]
}

export type ServiceAccount = {
  id: string
  tenantId: string
  clientId: string
  status: string
  expiresAt?: string
}

export type ApiKey = {
  id: string
  tenantId: string
  serviceAccountId: string
  status: string
  createdAt?: string
  expiresAt?: string
  lastUsedAt?: string
}
