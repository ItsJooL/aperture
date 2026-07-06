import { edgeNodes, graphqlRequest, type GraphQLConnection } from '@/api/graphql/client'

export type InvoiceInsightLineItem = {
  description: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export type InvoiceInsight = {
  id: string
  amount: number
  status: string
  customerName: string
  customerEmail: string
  lineItems: InvoiceInsightLineItem[]
}

type CustomerNode = { name: string; email: string }
type LineItemNode = { description: string; quantity: number; unit_price: number }
type InvoiceNode = {
  id: string
  amount: number
  status: string
  customer: GraphQLConnection<CustomerNode>
  lineItems: GraphQLConnection<LineItemNode>
}

/**
 * One GraphQL round trip across invoices, their customer, and every line item. The JSON:API
 * equivalent needs either three separate requests or an `include=` compound-document query
 * per invoice. Mirrors the verified query in
 * `api-collection/11-graphql/01-query-invoices-with-customer-and-lineitems.bru`.
 */
export const INVOICE_INSIGHTS_QUERY = `
  query InvoiceInsights {
    invoices {
      edges {
        node {
          id
          amount
          status
          customer {
            edges { node { name email } }
          }
          lineItems {
            edges { node { description quantity unit_price } }
          }
        }
      }
    }
  }
`

export const insightsService = {
  async invoiceInsights(): Promise<InvoiceInsight[]> {
    const data = await graphqlRequest<{ invoices: GraphQLConnection<InvoiceNode> }>(INVOICE_INSIGHTS_QUERY)
    return edgeNodes(data.invoices).map((node) => {
      const customer = edgeNodes(node.customer)[0]
      return {
        id: node.id,
        amount: Number(node.amount ?? 0),
        status: String(node.status ?? '').toLowerCase(),
        customerName: customer?.name ?? 'Unknown customer',
        customerEmail: customer?.email ?? '',
        lineItems: edgeNodes(node.lineItems).map((item) => {
          const quantity = Number(item.quantity ?? 0)
          const unitPrice = Number(item.unit_price ?? 0)
          return { description: item.description, quantity, unitPrice, lineTotal: quantity * unitPrice }
        }),
      }
    })
  },
}
