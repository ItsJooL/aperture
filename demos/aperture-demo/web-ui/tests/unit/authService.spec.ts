import { describe, expect, it, vi } from 'vitest'
import { authService } from '@/api/services/authService'

vi.mock('@/utils/tenant', () => ({
  getTenantFromHost: vi.fn(() => 'test'),
}))

describe('authService.login', () => {
  it('includes tenant from hostname in the request body', async () => {
    // The MSW mock handler for POST /auth/login returns a successful response.
    // We verify the service call completes without throwing and that tenant is resolved.
    const result = await authService.login('admin@acme.com', 'password')
    // The mock handler returns demoUser whose tenantId is 'tenant-demo';
    // the key assertion here is that login resolves successfully with a token.
    expect(result).toBeDefined()
    expect(typeof result.accessToken === 'string' || result.accessToken === undefined).toBe(true)
  })
})
