---
title: Auth & Identity
description: JWT, API keys, service accounts, the invite flow, and swapping auth providers.
---

# Auth & Identity

Aperture ships with a complete built-in auth system covering interactive users, machine-to-machine service accounts, and API keys. All of it is swappable through the `CredentialValidator` SPI.

## Built-in auth endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/login` | Username + password → JWT + refresh token |
| `POST` | `/auth/refresh` | Rotate refresh token → new JWT |
| `POST` | `/auth/logout` | Revoke refresh token family |
| `GET` | `/auth/me` | Current user info |
| `PATCH` | `/auth/me` | Update own profile (user-editable metadata only) |
| `POST` | `/auth/change-password` | Change password (required when `forcePasswordChange: true`) |
| `POST` | `/auth/token` | Service account client_id + client_secret → access token |

## JWT login

```bash
POST /auth/login
Content-Type: application/json

{
  "username": "accountant@acme.com",
  "password": "Accountant123!"
}
```

Response:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

Use the token as `Authorization: Bearer <accessToken>` on all API requests.

## Token claims

The JWT carries everything Aperture needs to enforce tenancy, RBAC, and ABAC:

| Claim | Type | Description |
|---|---|---|
| `sub` | string | User ID (UUID) |
| `tenantId` | string | Tenant the user belongs to (absent for SuperAdmin) |
| `roles` | string[] | Domain roles assigned to the user (e.g., `["Accountant"]`) |
| `profile` | object | User-editable profile metadata (display name, picture, etc.) |
| `securityAttributes` | object | Admin-assigned security claims used in ABAC (department, region, etc.) |
| `accountKind` | string | `USER`, `SERVICE_ACCOUNT`, or `PERSONAL_API_KEY` |
| `isSuperAdmin` | boolean | True if this is the platform super-administrator |
| `isTenantAdmin` | boolean | True if this user is a tenant administrator |
| `scope` | string[] | Present only when `FORCE_CHANGE` is active (password change required) |

Security attributes are admin-assigned key-value pairs stored in a JSONB column. ABAC policies compare against them using SpEL expressions (`#user.securityAttributes['department'] == 'finance'`). Ordinary users cannot modify security attributes — only the profile field is user-editable.

**Profile vs. Security Attributes:**
- **`profile`** — free-form user-editable metadata (display name, preferences, timezone, etc.). Users can update their own profile via `PATCH /auth/me`.
- **`securityAttributes`** — admin-controlled security claims (department, region, clearance level, etc.) used for ABAC policies. Ordinary users **cannot** modify security attributes.

## Token refresh

```bash
POST /auth/refresh
Content-Type: application/json

{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }
```

Returns a new `accessToken` + rotated `refreshToken`. The old refresh token is revoked. Logout revokes the entire token family.

## Forced password change

When a user account is created by an admin or seeder with a temporary password, the login response contains `"forcePasswordChange": true` and the token has a limited scope that blocks all API calls except `/auth/change-password`. The user must change their password before accessing anything else.

## Service accounts

Service accounts are machine-to-machine identities. They authenticate with a `client_id` + `client_secret` pair instead of a username and password:

```bash
POST /auth/token
Content-Type: application/json

{
  "clientId": "my-service-client",
  "clientSecret": "secret-shown-once"
}
```

The `client_secret` is shown only once at creation time and stored as a hash. Service accounts carry a `tenantId` and roles just like user accounts but are flagged as `isServiceAccount: true` in the principal.

## API keys

API keys are an alternative credential for machine or integration use. They are passed via the `X-API-Key` header:

```bash
curl http://localhost:8080/api/v1/invoices \
  -H "X-API-Key: ak_..."
```

Key values are shown only once at creation. Storage is a hash. Listing keys shows their ID, name, status, and expiry — never the key value itself.

## Personal API keys

Users can issue their own API keys (delegated from their own identity) via:

```bash
POST /auth/me/api-keys
Authorization: Bearer <user-token>
Content-Type: application/json

{
  "name": "CI pipeline key",
  "domainRoles": ["Accountant"],      # subset of caller's roles
  "securityAttributes": { "department": "finance" },  # subset of caller's attributes
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

Keys must be a **delegation** of the calling user's identity — they cannot carry roles or attributes the user doesn't currently hold. The system enforces this at creation time, and again on each use (fail-closed: if the user loses a role or attribute, existing keys that carried that role/attribute are immediately rejected).

List own keys: `GET /auth/me/api-keys`  
Disable own key: `POST /auth/me/api-keys/{keyId}/disable`

**Tenant settings** control whether personal API keys are enabled and whether non-expiring keys are allowed:

```
GET  /manage/tenants/{id}/settings/personal-api-keys   — read settings (TenantAdmin or SuperAdmin)
PUT  /manage/tenants/{id}/settings/personal-api-keys   — update settings (TenantAdmin or SuperAdmin)
GET  /manage/settings/personal-api-keys                — global defaults (SuperAdmin only)
PUT  /manage/settings/personal-api-keys                — update global defaults (SuperAdmin only)
```

Tenant settings override global defaults. Both default to `enabled: true`, `allowNonExpiring: false`.

Admin revocation (tenant-wide): `POST /manage/tenants/{id}/personal-api-keys/{keyId}/revoke`

## Identity administration

`SuperAdmin` and `TenantAdmin` are **platform authorities**, not domain roles. They are stored as boolean flags on the principal (`superAdmin`, `tenantAdmin`) and in dedicated database tables (`aperture_tenant_admins`), not in the domain roles catalog. They cannot be declared in `RoleDefinition` manifests or assigned as RBAC roles.

Tenant management requires the `SuperAdmin` authority. User management within a tenant requires `SuperAdmin` or `TenantAdmin` for the same tenant.

| Method | Path | Required authority |
|---|---|---|
| `POST` | `/manage/tenants` | `SuperAdmin` |
| `GET` | `/manage/tenants` | `SuperAdmin` |
| `GET` | `/manage/tenants/{id}` | `SuperAdmin` |
| `PATCH` | `/manage/tenants/{id}` | `SuperAdmin` |
| `DELETE` | `/manage/tenants/{id}` | `SuperAdmin` |
| `POST` | `/manage/tenants/{id}/users` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/users` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `PATCH` | `/manage/tenants/{id}/users/{uid}` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `DELETE` | `/manage/tenants/{id}/users/{uid}` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `PUT` | `/manage/tenants/{id}/users/{uid}/roles` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/tenant-admins/{uid}` | `SuperAdmin` — grants `TenantAdmin` authority |
| `DELETE` | `/manage/tenants/{id}/tenant-admins/{uid}` | `SuperAdmin` — revokes `TenantAdmin` authority |
| `POST` | `/manage/tenants/{id}/invites` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/invites` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `DELETE` | `/manage/tenants/{id}/invites/{inviteId}` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/service-accounts` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/service-accounts` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/service-accounts/{accountId}/disable` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/service-accounts/{accountId}/rotate-secret` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/personal-api-keys` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/personal-api-keys/{keyId}/revoke` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/settings/personal-api-keys` | `SuperAdmin` |
| `PUT` | `/manage/settings/personal-api-keys` | `SuperAdmin` |

## The invite flow

1. Admin calls `POST /manage/tenants/{id}/invites` with the invitee's email, intended username, and roles
2. Aperture generates a secure, expiring invite token
3. The invitee redeems the token via `POST /auth/accept-invite` with the token, their chosen username, and new password
4. The account is created with the assigned roles; the invite token is consumed

## The `CredentialValidator` SPI — swapping auth providers

The `CredentialValidator` interface is the only boundary between Aperture and the identity system:

```java
public interface CredentialValidator {
    ValidationResult validate(HttpServletRequest req);
}
```

`validate` receives the full HTTP request and returns either a `ValidationResult` with an `AperturePrincipal` (success) or a rejection. The `AperturePrincipal` carries everything Aperture needs downstream.

```java
public record AperturePrincipal(
    String userId,
    String tenantId,
    Set<String> roles,            // domain roles only — no platform authority strings
    PrincipalKind kind,           // USER, SERVICE_ACCOUNT, or PERSONAL_API_KEY
    Map<String, Object> profile,
    Map<String, Object> securityAttributes,
    Set<String> scopes,           // e.g. {"FORCE_CHANGE"}
    boolean superAdmin,
    boolean tenantAdmin
) implements java.security.Principal {}
```

**To swap auth providers:** declare a Spring bean that implements `CredentialValidator`. The built-in `SimpleCredentialValidator` is annotated `@ConditionalOnMissingBean(CredentialValidator.class)` — declaring your own bean disables the entire JWT/API-key infrastructure in `aperture-simple-auth`.

Everything else in Aperture — tenancy, RBAC, ABAC, hooks, audit — reads from the `AperturePrincipal` and is unaffected by the auth provider swap.

See the [Keycloak Integration example](/examples/keycloak) for a complete worked implementation.
