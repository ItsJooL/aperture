import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { http, HttpResponse } from 'msw'
import { QueryClient, VueQueryPlugin } from '../../src/vue-query-wrapper'
import { server } from '../mocks/server'
import GraphQLInsightsView from '@/views/insights/GraphQLInsightsView.vue'

function mountWithPlugins(component: unknown) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(component, {
    global: {
      plugins: [createPinia(), [VueQueryPlugin, { queryClient }]],
      mocks: { $router: { push: vi.fn() } },
      stubs: { teleport: true },
    },
  })
}

async function settle(wrapper: ReturnType<typeof mountWithPlugins>) {
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await flushPromises()
  return wrapper
}

describe('GraphQLInsightsView integration', () => {
  it('renders invoices with their nested customer and line items from the mocked GraphQL endpoint', async () => {
    const wrapper = await settle(mountWithPlugins(GraphQLInsightsView))

    expect(wrapper.text()).toContain('#invo-001')
    expect(wrapper.text()).toContain('Northstar Finance')
    expect(wrapper.text()).toContain('billing@northstar.example')

    // Line items are nested and only shown once the row is expanded.
    expect(wrapper.text()).not.toContain('Integration Starter Plan')
    await wrapper.get('tr.clickable').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Integration Starter Plan')
  })

  it('shows the GraphQL-shaped error (HTTP 200, data: null, errors[]) as a readable error state', async () => {
    server.use(
      http.post('http://localhost:8080/graphql/v3', () =>
        HttpResponse.json({ data: null, errors: [{ message: 'Exception while fetching data (/invoices) : tenant context missing' }] }),
      ),
    )

    const wrapper = await settle(mountWithPlugins(GraphQLInsightsView))

    expect(wrapper.text()).toContain('GraphQL request failed')
    expect(wrapper.text()).toContain('tenant context missing')
  })
})
