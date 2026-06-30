import { afterAll, afterEach, beforeAll, vi } from 'vitest'
import { server } from './mocks/server'
import { resetMockData } from './mocks/data'

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
  window.scrollTo = vi.fn()
})

afterEach(() => {
  server.resetHandlers()
  resetMockData()
  localStorage.clear()
})

afterAll(() => server.close())
