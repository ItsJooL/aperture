import { describe, expect, it } from 'vitest'
import { assertProductionApiBaseUrl, isMockApiBaseUrl } from '@/config/runtime'

describe('runtime configuration safety', () => {
  it('detects the local mock API URL', () => {
    expect(isMockApiBaseUrl('http://localhost:8081')).toBe(true)
    expect(isMockApiBaseUrl('http://127.0.0.1:8081')).toBe(true)
    expect(isMockApiBaseUrl('http://localhost:8080')).toBe(false)
  })

  it('blocks mock API URLs for production builds', () => {
    expect(() => assertProductionApiBaseUrl('http://localhost:8081', true)).toThrow(/Mock API base URL/)
    expect(() => assertProductionApiBaseUrl('http://localhost:8081', false)).not.toThrow()
    expect(() => assertProductionApiBaseUrl('https://api.example.com', true)).not.toThrow()
  })
})
