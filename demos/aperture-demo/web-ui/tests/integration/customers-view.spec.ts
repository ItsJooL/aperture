import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '../../src/vue-query-wrapper'
import CustomersView from '@/views/customers/CustomersView.vue'

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

describe('CustomersView integration', () => {
  it('renders customer rows from the mocked JSON:API backend', async () => {
    const wrapper = mountWithPlugins(CustomersView)
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await flushPromises()

    expect(wrapper.text()).toContain('Northstar Finance')
    expect(wrapper.text()).toContain('billing@northstar.example')
    expect(wrapper.text()).toContain('Harbour Health')
  })
})
