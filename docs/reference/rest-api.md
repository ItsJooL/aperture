---
title: REST API
description: JSON:API entity endpoints, filtering, pagination, and all framework endpoints.
---

# REST API

## Entity endpoints

Every entity generates a standard set of JSON:API endpoints:

| Method | Path | Operation |
|---|---|---|
| `GET` | `/api/v{n}/{entities}` | List with filtering, sorting, pagination |
| `POST` | `/api/v{n}/{entities}` | Create |
| `GET` | `/api/v{n}/{entities}/{id}` | Get by ID |
| `PATCH` | `/api/v{n}/{entities}/{id}` | Update |
| `DELETE` | `/api/v{n}/{entities}/{id}` | Delete |
| `GET` | `/api/v{n}/{entities}/{id}/relationships/{rel}` | Get relationship |
| `POST` | `/api/v{n}/{entities}/{id}/relationships/{rel}` | Add to relationship |
| `PATCH` | `/api/v{n}/{entities}/{id}/relationships/{rel}` | Replace relationship |
| `DELETE` | `/api/v{n}/{entities}/{id}/relationships/{rel}` | Remove from relationship |

`{entities}` is the plural form of the entity name (camelCase, e.g. `invoices`, `lineItems`). The `v{n}` segment corresponds to a declared API version.

All requests to entity endpoints must use:
- `Authorization: Bearer <token>` or `X-API-Key: <key>`
- `Content-Type: application/vnd.api+json` for write requests

---

## JSON:API features

### Filtering (RSQL)

Use the `filter` query parameter with RSQL syntax:

```
GET /api/v1/invoices?filter=status==PAID
GET /api/v1/invoices?filter=amount=gt=1000
GET /api/v1/invoices?filter=status==DRAFT,status==ISSUED
GET /api/v1/invoices?filter=status==PAID;amount=gt=500
```

| Operator | Meaning |
|---|---|
| `==` | equals |
| `!=` | not equals |
| `=gt=` | greater than |
| `=ge=` | greater than or equal |
| `=lt=` | less than |
| `=le=` | less than or equal |
| `=in=` | in list: `status=in=(PAID,ISSUED)` |
| `=out=` | not in list |
| `=like=` | SQL LIKE pattern: `name=like=Acme%` |
| `;` | AND |
| `,` | OR |

### Sorting

```
GET /api/v1/invoices?sort=amount          # ascending
GET /api/v1/invoices?sort=-amount         # descending
GET /api/v1/invoices?sort=-status,amount  # multi-field
```

### Pagination

Page-number based pagination:

```
GET /api/v1/invoices?page[number]=2&page[size]=10
```

The response `meta.pagination` object contains:

```json
{
  "meta": {
    "pagination": {
      "number": 2,
      "size": 10,
      "totalPages": 5,
      "totalRecords": 48
    }
  }
}
```

Default page size is 20. Maximum is 100.

### Sparse fieldsets

Request only specific attributes to reduce payload size:

```
GET /api/v1/invoices?fields[invoice]=amount,status
```

### Compound documents (include)

Include related resources in a single response:

```
GET /api/v1/invoices?include=customer
GET /api/v1/invoices?include=customer,lineItems
```

Related resources appear in the `included` array. Relationships in `data[].relationships` contain `data.type` and `data.id` pointers.

### One-of relationships

A `type: oneof` field is still represented as a normal JSON:API relationship. The relationship
name is the field name, and `data.type` is the concrete member resource type, not the `OneOf`
manifest name:

```json
{
  "data": {
    "type": "lineitems",
    "relationships": {
      "billable": {
        "data": { "type": "servicepackages", "id": "..." }
      }
    }
  }
}
```

Generated OpenAPI includes a relationship-data schema for each one-of field. For example,
`LineItemBillableRelationshipData` lists the allowed `type` enum values such as `products` and
`servicepackages`. Writes that use a resource type outside that enum are rejected with `400`
before the relationship is applied.

### Atomic operations

Multiple mutations in a single all-or-nothing request using the `application/vnd.api+json;ext="https://jsonapi.org/ext/atomic"` content type:

```json
{
  "atomic:operations": [
    {
      "op": "add",
      "data": { "type": "invoice", "attributes": { "amount": 100, "status": "DRAFT" }, "relationships": { "customer": { "data": { "type": "customer", "id": "1" } } } }
    },
    {
      "op": "update",
      "ref": { "type": "customer", "id": "1" },
      "data": { "type": "customer", "attributes": { "name": "Updated Name" } }
    }
  ]
}
```

All operations succeed or all are rolled back.

---

## Request and response shapes

### Create request

```
POST /api/v1/invoices
Content-Type: application/vnd.api+json

{
  "data": {
    "type": "invoice",
    "attributes": {
      "amount": 4999.00,
      "status": "DRAFT"
    },
    "relationships": {
      "customer": {
        "data": { "type": "customer", "id": "1" }
      }
    }
  }
}
```

### Response

```json
{
  "data": {
    "type": "invoice",
    "id": "a1b2c3d4-...",
    "attributes": {
      "amount": 4999.00,
      "status": "DRAFT"
    },
    "relationships": {
      "customer": {
        "data": { "type": "customer", "id": "1" }
      },
      "lineItems": {
        "data": []
      }
    }
  }
}
```

### Error response

```json
{
  "errors": [
    {
      "status": "400",
      "title": "Bad Request",
      "detail": "ValidateInvoice hook rejected the request"
    }
  ]
}
```

---

## Optimistic locking headers

When `optimisticLocking: true` on an entity:

- Every response includes `ETag: "0"` (the current version number)
- Mutations (`PATCH`, `PUT`, `DELETE`) must include `If-Match: "0"`
- Stale version → `412 Precondition Failed`
- Missing header → `428 Precondition Required`

---

## Management endpoints

### Auth (`/auth`)

| Method | Path | Auth required | Description |
|---|---|---|---|
| `POST` | `/auth/login` | No | Username/password → JWT + refresh token |
| `POST` | `/auth/refresh` | No | Rotate refresh token |
| `POST` | `/auth/logout` | No | Revoke refresh token family |
| `GET` | `/auth/me` | Yes | Current user info |
| `PATCH` | `/auth/me` | Yes | Update own profile (user-editable keys only) |
| `POST` | `/auth/change-password` | Yes | Change password |
| `POST` | `/auth/token` | No | Service account token |
| `POST` | `/auth/me/api-keys` | Yes | Create a personal API key (delegated from caller) |
| `GET` | `/auth/me/api-keys` | Yes | List own personal API keys |
| `POST` | `/auth/me/api-keys/{keyId}/disable` | Yes | Disable own personal API key |
| `POST` | `/auth/accept-invite` | No | Redeem an invite token and create the account |

### Tenant management (`/manage/tenants`) — POOL mode only

| Method | Path | Required role |
|---|---|---|
| `POST` | `/manage/tenants` | `SuperAdmin` |
| `GET` | `/manage/tenants` | `SuperAdmin` |
| `GET` | `/manage/tenants/{id}` | `SuperAdmin` |
| `PATCH` | `/manage/tenants/{id}` | `SuperAdmin` |
| `DELETE` | `/manage/tenants/{id}` | `SuperAdmin` |
| `POST` | `/manage/tenants/{id}/users` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/users` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `DELETE` | `/manage/tenants/{id}/users/{uid}` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `PATCH` | `/manage/tenants/{id}/users/{uid}` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `PUT` | `/manage/tenants/{id}/users/{uid}/roles` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/tenant-admins/{uid}` | `SuperAdmin` |
| `DELETE` | `/manage/tenants/{id}/tenant-admins/{uid}` | `SuperAdmin` |
| `POST` | `/manage/tenants/{id}/invites` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/invites` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `DELETE` | `/manage/tenants/{id}/invites/{inviteId}` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/service-accounts` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/service-accounts` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/service-accounts/{accountId}/disable` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/service-accounts/{accountId}/rotate-secret` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/personal-api-keys` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `POST` | `/manage/tenants/{id}/personal-api-keys/{keyId}/revoke` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/tenants/{id}/settings/personal-api-keys` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `PUT` | `/manage/tenants/{id}/settings/personal-api-keys` | `SuperAdmin` or same-tenant `TenantAdmin` |
| `GET` | `/manage/settings/personal-api-keys` | `SuperAdmin` |
| `PUT` | `/manage/settings/personal-api-keys` | `SuperAdmin` |

### Other framework endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/manage/audit` | Query audit log (`SuperAdmin` or `TenantAdmin`) |
| `GET` | `/actuator/health` | Spring Boot health check |
| `GET` | `/actuator/metrics` | Metrics (if exposed) |
| `GET` | `/manage/migrations/status` | Migration status |
