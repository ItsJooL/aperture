import { apiFetch, queryString } from '@/api/http/client'
import type { ApiKey, Invite, ServiceAccount, Tenant, User } from '@/api/types/domain'

type PagedResult<T> = {
  items?: T[]
  content?: T[]
  data?: T[]
  totalElements?: number
  totalPages?: number
  [key: string]: unknown
}

function unwrapPaged<T>(result: PagedResult<T>): T[] {
  return result.items ?? result.content ?? result.data ?? []
}

export const adminService = {
  async tenants() {
    const result = await apiFetch<PagedResult<Tenant>>(`/manage/tenants${queryString({ page: 0, size: 50 })}`)
    return unwrapPaged<Tenant>(result)
  },
  provisionTenant: (name: string) => apiFetch<Record<string, unknown>>('/manage/tenants', { method: 'POST', body: { name } }),
  tenant: (tenantId: string) => apiFetch<Tenant>(`/manage/tenants/${tenantId}`),
  updateTenantStatus: (tenantId: string, status: string) => apiFetch<Tenant>(`/manage/tenants/${tenantId}`, { method: 'PATCH', body: { status } }),
  async users(tenantId: string, search = '') {
    const result = await apiFetch<PagedResult<User>>(`/manage/tenants/${tenantId}/users${queryString({ search, page: 0, size: 50 })}`)
    return unwrapPaged<User>(result)
  },
  createUser: (tenantId: string, username: string) => apiFetch<Record<string, unknown>>(`/manage/tenants/${tenantId}/users`, {
    method: 'POST',
    body: { username },
  }),
  replaceUserRoles: (tenantId: string, userId: string, roleNames: string[]) => apiFetch<User>(`/manage/tenants/${tenantId}/users/${userId}/roles`, {
    method: 'PUT',
    body: { roleNames },
  }),
  invites: (tenantId: string) => apiFetch<Invite[]>(`/manage/tenants/${tenantId}/invites`),
  revokeInvite: (tenantId: string, inviteId: string) => apiFetch<void>(`/manage/tenants/${tenantId}/invites/${inviteId}`, { method: 'DELETE' }),
  createInvite: (tenantId: string, email: string, roleNames: string[], profile?: Record<string, unknown>, securityAttributes?: Record<string, unknown>) => apiFetch<Record<string, unknown>>(`/manage/tenants/${tenantId}/invites`, {
    method: 'POST',
    body: { email, roleNames, profile, securityAttributes },
  }),
  serviceAccounts: (tenantId: string) => apiFetch<ServiceAccount[]>(`/manage/tenants/${tenantId}/service-accounts`),
  disableServiceAccount: (tenantId: string, accountId: string) => apiFetch<ServiceAccount>(`/manage/tenants/${tenantId}/service-accounts/${accountId}/disable`, { method: 'POST' }),
  createServiceAccount: (tenantId: string, name: string) => apiFetch<Record<string, unknown>>(`/manage/tenants/${tenantId}/service-accounts`, {
    method: 'POST',
    body: { name },
  }),
  apiKeys: (tenantId: string) => apiFetch<ApiKey[]>(`/manage/tenants/${tenantId}/personal-api-keys`),
  revokeApiKey: (tenantId: string, keyId: string) => apiFetch<ApiKey>(`/manage/tenants/${tenantId}/personal-api-keys/${keyId}/revoke`, { method: 'POST' }),
  migrationStatus: () => apiFetch<Record<string, number>>('/manage/migrations/status'),
  sunsetImpact: (version: number) => apiFetch<Record<string, unknown>>(`/manage/sunset-impact${queryString({ version })}`),
}
