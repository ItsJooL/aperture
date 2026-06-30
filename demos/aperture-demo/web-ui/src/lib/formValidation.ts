import { ApiError } from '@/api/http/client'

export type FieldErrors<T extends string = string> = Partial<Record<T, string>>

export function required(value: unknown, message = 'This field is required.') {
  return value === undefined || value === null || String(value).trim() === '' ? message : undefined
}

export function email(value: string, message = 'Enter a valid email address.') {
  return /^\S+@\S+\.\S+$/.test(value.trim()) ? undefined : message
}

export function positiveNumber(value: unknown, message = 'Enter a number greater than zero.') {
  return Number(value) > 0 ? undefined : message
}

export function firstError(errors: FieldErrors) {
  return Object.values(errors).find(Boolean)
}

export function fieldNameFromPointer(pointer?: string) {
  if (!pointer) return undefined
  const segments = pointer.split('/').filter(Boolean)
  return segments.at(-1)
}

export function mapApiFieldErrors<T extends string = string>(error: unknown): FieldErrors<T> {
  if (!(error instanceof ApiError) || !error.payload || typeof error.payload !== 'object') return {}
  const payload = error.payload as { errors?: Array<{ detail?: string; title?: string; source?: { pointer?: string; parameter?: string } }> }
  if (!Array.isArray(payload.errors)) return {}
  return payload.errors.reduce<FieldErrors<T>>((acc, entry) => {
    const field = (entry.source?.parameter ?? fieldNameFromPointer(entry.source?.pointer)) as T | undefined
    if (field) acc[field] = entry.detail ?? entry.title ?? 'Check this value.'
    return acc
  }, {})
}

export function errorMessage(error: unknown, fallback = 'Something went wrong. Please try again.') {
  if (error instanceof Error && error.message) return error.message
  return fallback
}
