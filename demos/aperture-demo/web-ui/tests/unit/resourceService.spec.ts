import { afterEach, describe, expect, it, vi } from 'vitest'
import { customerService } from '@/api/services/customerService'

describe('resource service list filters', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('uses the real API RSQL filter parameter for customer search', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/vnd.api+json' }),
      json: async () => ({ data: [] }),
    } as Response)

    await customerService.list('North Wind')

    const url = String(fetchMock.mock.calls[0][0])
    const searchParams = new URL(url).searchParams
    expect(url).toContain('/api/v3/customers?')
    expect(searchParams.get('filter')).toBe("name=='*North Wind*'")
    expect(url).not.toContain('filter%5Bcustomers.name%5D%5Binfix%5D')
  })
})
