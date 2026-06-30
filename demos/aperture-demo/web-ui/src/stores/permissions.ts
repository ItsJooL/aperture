import type { User } from '@/api/types/domain'

export type Permission =
  | 'workspace:view'
  | 'customers:read'
  | 'customers:write'
  | 'customers:archive'
  | 'products:read'
  | 'products:write'
  | 'products:archive'
  | 'invoices:read'
  | 'invoices:write'
  | 'payments:read'
  | 'payments:write'
  | 'suppliers:read'
  | 'suppliers:write'
  | 'admin:view'
  | 'admin:users:write'
  | 'admin:invites:write'
  | 'admin:service-access:write'
  | 'admin:tenants:write'

const adminRoles = new Set(['SuperAdmin', 'TenantAdmin', 'Admin', 'Administrator', 'ROLE_ADMIN', 'ROLE_TENANT_ADMIN'])

const rolePermissions: Record<string, Permission[]> = {
  SuperAdmin: [
    'workspace:view',
    'customers:read',
    'customers:write',
    'customers:archive',
    'products:read',
    'products:write',
    'products:archive',
    'invoices:read',
    'invoices:write',
    'payments:read',
    'payments:write',
    'suppliers:read',
    'suppliers:write',
    'admin:view',
    'admin:users:write',
    'admin:invites:write',
    'admin:service-access:write',
    'admin:tenants:write',
  ],
  Viewer: ['workspace:view', 'customers:read', 'products:read', 'invoices:read', 'payments:read', 'suppliers:read'],
  Accountant: [
    'workspace:view',
    'customers:read',
    'customers:write',
    'products:read',
    'products:write',
    'invoices:read',
    'invoices:write',
    'payments:read',
    'payments:write',
    'suppliers:read',
  ],
  Operations: ['workspace:view', 'customers:read', 'products:read', 'products:write', 'invoices:read', 'suppliers:read'],
  TenantAdmin: [
    'workspace:view',
    'customers:read',
    'customers:write',
    'customers:archive',
    'products:read',
    'products:write',
    'products:archive',
    'invoices:read',
    'invoices:write',
    'payments:read',
    'payments:write',
    'suppliers:read',
    'suppliers:write',
    'admin:view',
    'admin:users:write',
    'admin:invites:write',
    'admin:service-access:write',
  ],
}

export const permissionLabels: Record<Permission, string> = {
  'workspace:view': 'View workspace',
  'customers:read': 'View customers',
  'customers:write': 'Manage customers',
  'customers:archive': 'Archive customers',
  'products:read': 'View products',
  'products:write': 'Manage products',
  'products:archive': 'Deactivate products',
  'invoices:read': 'View invoices',
  'invoices:write': 'Manage invoices',
  'payments:read': 'View payments',
  'payments:write': 'Record payments',
  'suppliers:read': 'View suppliers',
  'suppliers:write': 'Manage suppliers',
  'admin:view': 'Open administration',
  'admin:users:write': 'Manage users and roles',
  'admin:invites:write': 'Manage invitations',
  'admin:service-access:write': 'Manage service access',
  'admin:tenants:write': 'Manage tenants',
}

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
}

export function extractRoleNames(user: User | null): string[] {
  if (!user) return []
  const attributes = user.attributes ?? {}
  const directRoles = asStringArray(user.roleNames ?? user.roles)
  const attributeRoles = asStringArray(attributes.roleNames ?? attributes.roles)
  const attributeRole = typeof attributes.role === 'string' ? [attributes.role] : []
  return Array.from(new Set([...directRoles, ...attributeRoles, ...attributeRole]))
}

export function isAdminUser(user: User | null) {
  if (!user) return false
  if (user.superAdmin) return true
  return extractRoleNames(user).some((role) => adminRoles.has(role))
}

export function permissionsForUser(user: User | null): Permission[] {
  if (!user) return []
  if (user.superAdmin) return Array.from(new Set(Object.values(rolePermissions).flat()))
  const permissions = extractRoleNames(user).flatMap((role) => rolePermissions[role] ?? [])
  return Array.from(new Set(permissions))
}

export function hasPermission(user: User | null, permission: Permission) {
  return permissionsForUser(user).includes(permission)
}

export function hasEveryPermission(user: User | null, permissions: Permission[]) {
  return permissions.every((permission) => hasPermission(user, permission))
}
