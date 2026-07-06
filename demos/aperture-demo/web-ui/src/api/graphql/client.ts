import { apiFetch, ApiError } from '@/api/http/client'

const GRAPHQL_ENDPOINT = '/graphql/v3'

export type GraphQLEdge<T> = { node: T }
export type GraphQLConnection<T> = { edges?: Array<GraphQLEdge<T>> | null } | null | undefined

export type GraphQLResponseError = {
  message: string
  path?: Array<string | number>
  extensions?: Record<string, unknown>
}

type GraphQLResponse<T> = {
  data?: T | null
  errors?: GraphQLResponseError[]
}

/**
 * Thrown for both transport-level GraphQL failures and the GraphQL-specific error shape
 * (HTTP 200 with a top-level `errors` array and `data: null`), so callers only need to
 * handle one error type regardless of which case produced it.
 */
export class GraphQLRequestError extends Error {
  constructor(
    message: string,
    public readonly errors: GraphQLResponseError[],
  ) {
    super(message)
    this.name = 'GraphQLRequestError'
  }
}

function messageFromErrors(errors?: GraphQLResponseError[]) {
  return (errors ?? [])
    .map((error) => error?.message)
    .filter((message): message is string => Boolean(message))
    .join('; ')
}

/**
 * POSTs a GraphQL query to Elide's versioned GraphQL endpoint (`/graphql/v3`) and returns the
 * `data` payload.
 *
 * Elide's GraphQL responses are not JSON:API documents: a request can come back as HTTP 200
 * with `{ data: null, errors: [{ message }] }`, which the JSON:API error handling in
 * `apiFetch` (which looks for `.title`/`.detail`) does not recognize. This helper normalizes
 * that case, and any transport-level failure that also carries a GraphQL error payload, into
 * a single `GraphQLRequestError`.
 */
export async function graphqlRequest<T>(query: string, variables?: Record<string, unknown>): Promise<T> {
  let response: GraphQLResponse<T>
  try {
    response = await apiFetch<GraphQLResponse<T>>(GRAPHQL_ENDPOINT, {
      method: 'POST',
      jsonApi: false,
      body: variables ? { query, variables } : { query },
    })
  } catch (error) {
    if (error instanceof ApiError && error.payload && typeof error.payload === 'object') {
      const payloadErrors = (error.payload as GraphQLResponse<T>).errors
      if (payloadErrors?.length) {
        throw new GraphQLRequestError(messageFromErrors(payloadErrors) || error.message, payloadErrors)
      }
    }
    throw error
  }

  if (response.errors?.length) {
    throw new GraphQLRequestError(messageFromErrors(response.errors) || 'The GraphQL request returned an error.', response.errors)
  }

  if (response.data == null) {
    throw new GraphQLRequestError('The GraphQL request returned no data.', [])
  }

  return response.data
}

/** Flattens a Relay-style `{ edges: [{ node }] }` connection into a plain array of nodes. */
export function edgeNodes<T>(connection: GraphQLConnection<T>): T[] {
  return connection?.edges?.map((edge) => edge.node) ?? []
}
