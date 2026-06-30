export function getTenantFromHost(): string | null {
  const host = window.location.hostname

  // Root dev URLs — no tenant
  if (host === 'localhost' || host === '127.0.0.1') return null

  const parts = host.split('.')

  // *.localhost (e.g. acme.localhost) — extract subdomain for local multi-tenant dev
  if (parts.length === 2 && parts[1] === 'localhost') return parts[0]

  // Any hostname with 3+ segments: extract the first as the tenant subdomain.
  // Covers: acme.aperture.io, acme.demo.aperture.io, acme.localhost.example.com etc.
  if (parts.length >= 3) return parts[0]

  // Single-segment or two-segment hostnames without .localhost suffix — no tenant subdomain
  return null
}
