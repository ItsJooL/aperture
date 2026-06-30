import type { DomainResource, JsonApiDocument, JsonApiResource, ResourceIdentifier } from './types'

export function toDomain<T extends object>(resource: JsonApiResource<T>): DomainResource<T> {
  return {
    id: resource.id ?? '',
    type: resource.type,
    ...(resource.attributes ?? ({} as T)),
    relationships: resource.relationships,
  }
}

export function collectionFromDocument<T extends object>(document: JsonApiDocument<T>): DomainResource<T>[] {
  if (!Array.isArray(document.data)) return []
  return document.data.map((resource) => toDomain(resource))
}

export function singleFromDocument<T extends object>(document: JsonApiDocument<T>): DomainResource<T> | null {
  if (!document.data || Array.isArray(document.data)) return null
  return toDomain(document.data)
}

export function jsonApiCreateDocument<T extends object>(type: string, attributes: T, relationships?: Record<string, ResourceIdentifier | ResourceIdentifier[] | null>) {
  return {
    data: {
      type,
      attributes,
      relationships: relationshipDocument(relationships),
    },
  }
}

export function jsonApiUpdateDocument<T extends object>(type: string, id: string, attributes: Partial<T>, relationships?: Record<string, ResourceIdentifier | ResourceIdentifier[] | null>) {
  return {
    data: {
      type,
      id,
      attributes,
      relationships: relationshipDocument(relationships),
    },
  }
}

function relationshipDocument(relationships?: Record<string, ResourceIdentifier | ResourceIdentifier[] | null>) {
  if (!relationships) return undefined
  return Object.fromEntries(Object.entries(relationships).map(([key, data]) => [key, { data }]))
}

export function resourceIdentifier(type: string, id?: string): ResourceIdentifier | null {
  if (!id) return null
  return { type, id }
}
