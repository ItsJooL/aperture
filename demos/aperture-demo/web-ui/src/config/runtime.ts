const DEFAULT_API_BASE_URL = 'http://localhost:8080'

export function isMockApiBaseUrl(apiBaseUrl: string) {
  return /^https?:\/\/(localhost|127\.0\.0\.1):8081\b/i.test(apiBaseUrl)
}

export function assertProductionApiBaseUrl(apiBaseUrl: string, isProduction: boolean) {
  if (isProduction && isMockApiBaseUrl(apiBaseUrl)) {
    throw new Error('Mock API base URL is not allowed in production builds.')
  }
}

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL

assertProductionApiBaseUrl(API_BASE_URL, import.meta.env.PROD)
