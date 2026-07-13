export const oasSummary = {
  "title": "Aperture Demo API",
  "version": "1.0.0",
  "server": "http://localhost:8080",
  "pathCount": 152,
  "operationCount": 365,
  "schemaCount": 46,
  "jsonApiOperationCount": 285,
  "authOperationCount": 8,
  "manageOperationCount": 21,
  "atomicOperationCount": 3,
  "resources": [
    {
      "name": "customers",
      "schema": "v3_customers",
      "attributes": [
        "apertureTenantId",
        "deletedAt",
        "email",
        "name",
        "phone_number",
        "version"
      ],
      "relationships": [
        "invoices"
      ]
    },
    {
      "name": "products",
      "schema": "v3_products",
      "attributes": [
        "active",
        "apertureTenantId",
        "category",
        "deletedAt",
        "description",
        "name",
        "sku",
        "unit_price",
        "version"
      ],
      "relationships": [
        "currency",
        "lineItems"
      ]
    },
    {
      "name": "invoices",
      "schema": "v3_invoices",
      "attributes": [
        "amount",
        "apertureTenantId",
        "status"
      ],
      "relationships": [
        "customer",
        "lineItems",
        "payments"
      ]
    },
    {
      "name": "lineitems",
      "schema": "v3_lineitems",
      "attributes": [
        "apertureTenantId",
        "description",
        "price",
        "quantity",
        "unit_price"
      ],
      "relationships": [
        "invoice",
        "product"
      ]
    },
    {
      "name": "payments",
      "schema": "v3_payments",
      "attributes": [
        "amount",
        "apertureTenantId"
      ],
      "relationships": [
        "invoice"
      ]
    },
    {
      "name": "suppliers",
      "schema": "v3_suppliers",
      "attributes": [
        "apertureTenantId",
        "company_name"
      ],
      "relationships": []
    },
    {
      "name": "countries",
      "schema": "v3_countries",
      "attributes": [
        "code",
        "name"
      ],
      "relationships": []
    },
    {
      "name": "currencies",
      "schema": "v3_currencies",
      "attributes": [
        "code"
      ],
      "relationships": []
    }
  ],
  "productFlows": [
    "Sign in to a tenant workspace",
    "Review revenue, receivables and operational health",
    "Manage customers and customer profiles",
    "Manage products, currencies and supplier records",
    "Create invoices from customers and billable line items",
    "Record payments and monitor outstanding balances",
    "Invite users, assign roles, manage service accounts and API keys"
  ]
} as const
