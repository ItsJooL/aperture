import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from '@/router'
import { useAuthStore } from '@/stores/authStore'

describe('router guards', () => {
  it('redirects unauthenticated users to login', async () => {
    setActivePinia(createPinia())
    await router.push('/customers')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('blocks non-admin users from administration routes', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.setSession({ accessToken: 'token', user: { id: 'viewer', username: 'viewer', tenantId: 'tenant-demo', status: 'ACTIVE', roleNames: ['Viewer'] } })

    await router.push('/admin')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('blocks read-only users from write-only invoice creation', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.setSession({ accessToken: 'token', user: { id: 'viewer', username: 'viewer', tenantId: 'tenant-demo', status: 'ACTIVE', roleNames: ['Viewer'] } })

    await router.push('/invoices/new')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

})
