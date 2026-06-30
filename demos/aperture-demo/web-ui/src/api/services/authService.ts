import { apiFetch } from '@/api/http/client'
import { getTenantFromHost } from '@/utils/tenant'
import type { User } from '@/api/types/domain'

type LoginResponse = {
  accessToken?: string
  refreshToken?: string
  token?: string
  user?: User
  [key: string]: unknown
}

export const authService = {
  login: (username: string, password: string) => apiFetch<LoginResponse>('/auth/login', {
    method: 'POST',
    authenticated: false,
    body: { username, password, tenant: getTenantFromHost() },
  }),
  acceptInvite: (token: string, username: string, password: string) => apiFetch<LoginResponse>('/auth/accept-invite', {
    method: 'POST',
    authenticated: false,
    body: { token, username, password },
  }),
  refresh: (refreshToken: string) => apiFetch<LoginResponse>('/auth/refresh', {
    method: 'POST',
    authenticated: false,
    body: { refreshToken },
  }),
  logout: (refreshToken: string) => apiFetch<Record<string, unknown>>('/auth/logout', {
    method: 'POST',
    body: { refreshToken },
  }),
  me: () => apiFetch<User>('/auth/me'),
  changePassword: (currentPassword: string, newPassword: string) => apiFetch<Record<string, unknown>>('/auth/change-password', {
    method: 'POST',
    body: { currentPassword, newPassword },
  }),
}
