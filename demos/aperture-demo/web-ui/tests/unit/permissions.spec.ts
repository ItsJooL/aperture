import { describe, expect, it } from 'vitest'
import { hasPermission, isAdminUser, permissionsForUser } from '@/stores/permissions'

describe('role permission model', () => {
  it('grants tenant admins product, billing and administration actions', () => {
    const user = { id: '1', username: 'admin', tenantId: 'tenant', status: 'ACTIVE', roleNames: ['TenantAdmin'] }
    expect(isAdminUser(user)).toBe(true)
    expect(hasPermission(user, 'admin:users:write')).toBe(true)
    expect(hasPermission(user, 'invoices:write')).toBe(true)
    expect(hasPermission(user, 'products:archive')).toBe(true)
    expect(hasPermission(user, 'suppliers:write')).toBe(true)
    expect(hasPermission(user, 'admin:tenants:write')).toBe(false)
  })

  it('grants framework super admins system tenant administration', () => {
    const user = { id: '4', username: 'superadmin', tenantId: '', status: 'ACTIVE', roles: ['SuperAdmin'] }
    expect(isAdminUser(user)).toBe(true)
    expect(hasPermission(user, 'admin:view')).toBe(true)
    expect(hasPermission(user, 'admin:tenants:write')).toBe(true)
  })

  it('keeps viewers in a read-only workspace', () => {
    const user = { id: '2', username: 'viewer', tenantId: 'tenant', status: 'ACTIVE', roleNames: ['Viewer'] }
    expect(isAdminUser(user)).toBe(false)
    expect(hasPermission(user, 'customers:read')).toBe(true)
    expect(hasPermission(user, 'customers:write')).toBe(false)
    expect(hasPermission(user, 'suppliers:write')).toBe(false)
    expect(hasPermission(user, 'admin:view')).toBe(false)
  })

  it('returns unique permissions when several roles overlap', () => {
    const user = { id: '3', username: 'finance', tenantId: 'tenant', status: 'ACTIVE', roleNames: ['Viewer', 'Accountant'] }
    const permissions = permissionsForUser(user)
    expect(permissions.filter((permission) => permission === 'customers:read')).toHaveLength(1)
    expect(permissions).toContain('products:write')
    expect(permissions).toContain('payments:write')
  })
})
