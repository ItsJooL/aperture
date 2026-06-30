import { describe, expect, it, beforeEach, afterEach } from 'vitest'

describe('getTenantFromHost', () => {
  // Override window.location.hostname for each test
  function setHost(hostname: string) {
    Object.defineProperty(window, 'location', {
      value: { hostname },
      writable: true,
      configurable: true,
    })
  }

  beforeEach(() => {
    setHost('localhost')
  })

  afterEach(() => {
    setHost('localhost')
  })

  it('returns null for plain localhost (root dev URL, no tenant)', async () => {
    setHost('localhost')
    const { getTenantFromHost } = await import('@/utils/tenant')
    expect(getTenantFromHost()).toBeNull()
  })

  it('extracts subdomain from *.localhost for local multi-tenant dev', async () => {
    setHost('acme.localhost')
    const { getTenantFromHost } = await import('@/utils/tenant')
    expect(getTenantFromHost()).toBe('acme')
  })

  it('extracts subdomain from a real hosted domain acme.aperture.io', async () => {
    setHost('acme.aperture.io')
    const { getTenantFromHost } = await import('@/utils/tenant')
    expect(getTenantFromHost()).toBe('acme')
  })

  it('extracts first segment of a deeply nested hostname', async () => {
    setHost('app.acme.aperture.io')
    const { getTenantFromHost } = await import('@/utils/tenant')
    expect(getTenantFromHost()).toBe('app')
  })

  it('returns null for a bare two-segment hostname with no subdomain', async () => {
    setHost('aperture.io')
    const { getTenantFromHost } = await import('@/utils/tenant')
    expect(getTenantFromHost()).toBeNull()
  })
})
