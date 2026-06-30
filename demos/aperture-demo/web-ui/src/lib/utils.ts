import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function initials(value = 'Aperture') {
  return value
    .split(/\s|@|\./)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('') || 'AP'
}

export function currency(value?: number | string | null) {
  const numeric = Number(value ?? 0)
  return new Intl.NumberFormat('en-IE', { style: 'currency', currency: 'EUR' }).format(numeric)
}

export function percent(value: number) {
  return new Intl.NumberFormat('en-IE', { style: 'percent', maximumFractionDigits: 0 }).format(value)
}

export function dateShort(value?: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('en-IE', { day: '2-digit', month: 'short', year: 'numeric' }).format(new Date(value))
}

export function sentence(value: string) {
  return value.replaceAll('_', ' ').replace(/\b\w/g, (m) => m.toUpperCase())
}
