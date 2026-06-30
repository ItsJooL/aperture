export type ApiClientOptions = {
  method?: string
  body?: unknown
  headers?: Record<string, string>
  jsonApi?: boolean
  authenticated?: boolean
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly payload: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

import { API_BASE_URL } from '@/config/runtime'

export { API_BASE_URL }

function buildUrl(path: string) {
  if (/^https?:\/\//.test(path)) return path
  return `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`
}

function resolveErrorMessage(payload: unknown, fallback: string) {
  if (payload && typeof payload === 'object') {
    const asRecord = payload as Record<string, unknown>
    if (Array.isArray(asRecord.errors)) {
      return asRecord.errors
        .map((error) => {
          if (!error || typeof error !== 'object') return undefined
          const entry = error as { title?: string; detail?: string }
          return entry.detail ?? entry.title
        })
        .filter(Boolean)
        .join(', ')
    }
    if (typeof asRecord.message === 'string') return asRecord.message
    if (typeof asRecord.detail === 'string') return asRecord.detail
  }
  return fallback
}

export async function apiFetch<T>(path: string, options: ApiClientOptions = {}): Promise<T> {
  const headers = new Headers(options.headers)
  const token = localStorage.getItem('aperture.accessToken')

  if (options.authenticated !== false && token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  
  const tenantContext = localStorage.getItem('aperture.activeTenantContext')
  if (tenantContext) {
    headers.set('X-Aperture-Tenant-Context', tenantContext)
  }

  if (options.jsonApi) {
    if (!headers.has('Accept')) headers.set('Accept', 'application/vnd.api+json')
    if (options.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/vnd.api+json')
    }
  } else {
    if (!headers.has('Accept')) headers.set('Accept', 'application/json')
    if (options.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
  }

  const response = await fetch(buildUrl(path), {
    method: options.method ?? 'GET',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })

  if (response.status === 204) return undefined as T

  const contentType = response.headers.get('content-type') ?? ''
  const payload = contentType.includes('json') ? await response.json() : await response.text()

  if (!response.ok) {
    throw new ApiError(resolveErrorMessage(payload, response.statusText), response.status, payload)
  }

  return payload as T
}

export function queryString(params: Record<string, string | number | boolean | undefined | null | string[]>) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    if (Array.isArray(value)) {
      if (value.length) search.set(key, value.join(','))
      return
    }
    search.set(key, String(value))
  })
  const query = search.toString()
  return query ? `?${query}` : ''
}
