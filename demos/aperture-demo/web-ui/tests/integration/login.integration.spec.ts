import { authService } from '@/api/services/authService'
import { expect, test } from 'vitest'

/**
 * Integration test that hits the real mock‑API server.
 * Make sure the mock API is running (`npm run dev:mock-api`) before
 * executing this test.
 */
test('login against mock API returns tenant info', async () => {
  const result = await authService.login('admin@acme.com', 'password')
  expect(result).toHaveProperty('accessToken')
  expect(result.user?.tenantId).toBe('tenant-demo')
})
