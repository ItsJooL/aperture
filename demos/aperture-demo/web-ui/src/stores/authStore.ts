import { defineStore } from 'pinia'
import type { User } from '@/api/types/domain'
import type { Permission } from '@/stores/permissions'
import { hasPermission, isAdminUser } from '@/stores/permissions'
import { authService } from '@/api/services/authService'

type Session = {
  accessToken: string
  refreshToken?: string
  user?: User
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: localStorage.getItem('aperture.accessToken') ?? null as string | null,
    refreshToken: localStorage.getItem('aperture.refreshToken') ?? null as string | null,
    user: null as User | null,
    activeTenantContext: localStorage.getItem('aperture.activeTenantContext') ?? null as string | null,
  }),

  getters: {
    isAuthenticated: (state) => !!state.accessToken,
    canAccessAdmin: (state) => isAdminUser(state.user),
    tenantId: (state) => state.user?.tenantId ?? '',
    currentTenantContext: (state) => state.activeTenantContext,
  },

  actions: {
    async login(username: string, password: string) {
      const res = await authService.login(username, password)
      const token = res.accessToken ?? res.token
      if (!token) throw new Error('Login failed')
      this.accessToken = token
      localStorage.setItem('aperture.accessToken', token)
      localStorage.setItem('aperture.username', username)
      if (res.refreshToken) {
        this.refreshToken = res.refreshToken
        localStorage.setItem('aperture.refreshToken', res.refreshToken)
      }
      this.user = await authService.me()
      this.applyKnownUsername(username)
    },

    async ensureCurrentUser() {
      if (!this.accessToken || this.user) return
      try {
        this.user = await authService.me()
        this.applyKnownUsername(localStorage.getItem('aperture.username') ?? undefined)
      } catch {
        this.clearSession()
      }
    },

    async logout() {
      try {
        if (this.refreshToken) await authService.logout(this.refreshToken)
      } finally {
        this.clearSession()
      }
    },

    async changePassword(currentPassword: string, newPassword: string) {
      await authService.changePassword(currentPassword, newPassword)
    },

    async acceptInvite(token: string, username: string, password: string) {
      const res = await authService.acceptInvite(token, username, password)
      const accessToken = res.accessToken ?? res.token
      if (!accessToken) throw new Error('Accept invite failed')
      this.accessToken = accessToken
      localStorage.setItem('aperture.accessToken', accessToken)
      localStorage.setItem('aperture.username', username)
      if (res.refreshToken) {
        this.refreshToken = res.refreshToken
        localStorage.setItem('aperture.refreshToken', res.refreshToken)
      }
      this.user = await authService.me()
      this.applyKnownUsername(username)
    },

    setSession(session: Session) {
      this.accessToken = session.accessToken
      this.refreshToken = session.refreshToken ?? null
      this.user = session.user ?? null
      localStorage.setItem('aperture.accessToken', session.accessToken)
      if (session.refreshToken) {
        localStorage.setItem('aperture.refreshToken', session.refreshToken)
      }
      if (session.user?.username) localStorage.setItem('aperture.username', session.user.username)
    },

    clearSession() {
      this.accessToken = null
      this.refreshToken = null
      this.user = null
      localStorage.removeItem('aperture.accessToken')
      localStorage.removeItem('aperture.refreshToken')
      localStorage.removeItem('aperture.username')
    },

    applyKnownUsername(username?: string) {
      if (this.user && !this.user.username && username) this.user.username = username
    },

    setActiveTenantContext(tenantId: string | null) {
      this.activeTenantContext = tenantId
      if (tenantId) {
        localStorage.setItem('aperture.activeTenantContext', tenantId)
      } else {
        localStorage.removeItem('aperture.activeTenantContext')
      }
    },

    hasPermission(permission: Permission) {
      return hasPermission(this.user, permission)
    },
  },
})
