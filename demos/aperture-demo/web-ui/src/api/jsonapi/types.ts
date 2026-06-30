export type ResourceIdentifier = {
  type: string
  id?: string
  lid?: string
}

export type JsonApiRelationship = {
  data?: ResourceIdentifier | ResourceIdentifier[] | null
  links?: Record<string, unknown>
  meta?: Record<string, unknown>
}

export type JsonApiResource<TAttributes = Record<string, unknown>, TRelationships = Record<string, JsonApiRelationship>> = {
  type: string
  id?: string
  attributes?: TAttributes
  relationships?: TRelationships
  links?: Record<string, unknown>
  meta?: Record<string, unknown>
}

export type JsonApiDocument<TAttributes = Record<string, unknown>> = {
  data: JsonApiResource<TAttributes> | JsonApiResource<TAttributes>[] | null
  included?: JsonApiResource[]
  links?: Record<string, unknown>
  meta?: Record<string, unknown>
  errors?: JsonApiError[]
}

export type JsonApiError = {
  id?: string
  status?: string
  code?: string
  title?: string
  detail?: string
  source?: {
    pointer?: string
    parameter?: string
    header?: string
  }
}

export type DomainResource<TAttributes = Record<string, unknown>> = TAttributes & {
  id: string
  type: string
  relationships?: Record<string, JsonApiRelationship>
}
