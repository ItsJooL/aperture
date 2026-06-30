import { apiFetch, queryString } from '@/api/http/client'
import { collectionFromDocument, jsonApiCreateDocument, jsonApiUpdateDocument, singleFromDocument } from '@/api/jsonapi/adapter'
import type { DomainResource, JsonApiDocument, ResourceIdentifier } from '@/api/jsonapi/types'

type ListOptions = {
  search?: string
  sort?: string[]
  include?: string[]
  paginate?: boolean
  pageSize?: number
  pageNumber?: number
  filters?: Record<string, string | undefined>
}

export type ResourceList<T extends object> = {
  items: DomainResource<T>[]
  meta: Record<string, unknown>
  links: Record<string, unknown>
}

const apiVersion = import.meta.env.VITE_DEFAULT_API_VERSION || 'v3'

function rsqlString(value: string) {
  return `'${value.replaceAll('\\', '\\\\').replaceAll("'", "\\'")}'`
}

function pathFor(resource: string, id?: string) {
  return `/api/${apiVersion}/${resource}${id ? `/${id}` : ''}`
}

function listParams(resource: string, options: ListOptions = {}) {
  const params: Record<string, string | number | string[] | undefined> = {
    sort: options.sort,
    include: options.include,
  }
  if (options.paginate !== false) {
    params['page[number]'] = options.pageNumber ?? 1
    params['page[size]'] = options.pageSize ?? 20
    params['page[totals]'] = 'true'
  }
  if (options.search) {
    const searchFields: Record<string, string> = {
      customers: 'name',
      products: 'name',
      suppliers: 'company_name',
      countries: 'name',
      currencies: 'code',
      invoices: 'status',
      lineitems: 'description',
    }
    const field = searchFields[resource]
    if (field) params.filter = `${field}==${rsqlString(`*${options.search}*`)}`
  }
  if (options.filters) Object.assign(params, options.filters)
  return queryString(params)
}

export async function listResources<T extends object>(resource: string, options: ListOptions = {}): Promise<ResourceList<T>> {
  const document = await apiFetch<JsonApiDocument<T>>(`${pathFor(resource)}${listParams(resource, options)}`, { jsonApi: true })
  return {
    items: collectionFromDocument<T>(document),
    meta: document.meta ?? {},
    links: document.links ?? {},
  }
}

export async function getResource<T extends object>(resource: string, id: string, include?: string[]) {
  const document = await apiFetch<JsonApiDocument<T>>(`${pathFor(resource, id)}${queryString({ include })}`, { jsonApi: true })
  return singleFromDocument<T>(document)
}

export async function createResource<TResponse extends object, TInput extends object = Partial<TResponse>>(
  resource: string,
  attributes: TInput,
  relationships?: Record<string, ResourceIdentifier | ResourceIdentifier[] | null>,
) {
  const document = await apiFetch<JsonApiDocument<TResponse>>(pathFor(resource), {
    method: 'POST',
    jsonApi: true,
    body: jsonApiCreateDocument(resource, attributes, relationships),
  })
  return singleFromDocument<TResponse>(document)
}

export async function updateResource<TResponse extends object, TInput extends object = Partial<TResponse>>(
  resource: string,
  id: string,
  attributes: TInput,
  relationships?: Record<string, ResourceIdentifier | ResourceIdentifier[] | null>,
) {
  await apiFetch<void>(pathFor(resource, id), {
    method: 'PATCH',
    jsonApi: true,
    body: jsonApiUpdateDocument(resource, id, attributes, relationships),
  })
}

export async function deleteResource(resource: string, id: string) {
  await apiFetch<void>(pathFor(resource, id), { method: 'DELETE', jsonApi: true })
}
