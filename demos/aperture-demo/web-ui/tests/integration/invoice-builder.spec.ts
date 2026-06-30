import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '../../src/vue-query-wrapper'
import InvoiceBuilderView from '@/views/invoices/InvoiceBuilderView.vue'

async function mountBuilder() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/invoices', component: { template: '<div />' } },
      { path: '/invoices/new', component: InvoiceBuilderView },
      { path: '/invoices/:id', component: { template: '<div />' } },
    ],
  })
  router.push('/invoices/new?customerId=cust-001')
  await router.isReady()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(InvoiceBuilderView, {
    global: {
      plugins: [router, createPinia(), [VueQueryPlugin, { queryClient }]],
      stubs: { teleport: true },
    },
  })
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await flushPromises()
  return { wrapper, router }
}

describe('InvoiceBuilderView integration', () => {
  it('guides a user through creating an invoice using mocked customer and product data', async () => {
    const { wrapper, router } = await mountBuilder()

    expect(wrapper.text()).toContain('Northstar Finance')

    await wrapper.findAll('button').find((button) => button.text() === 'Continue')!.trigger('click')
    await flushPromises()
    await wrapper.find('select').setValue('prod-001')
    await flushPromises()
    expect(wrapper.text()).toContain('Integration Starter')
    expect(wrapper.text()).toContain('€249')

    await wrapper.findAll('button').find((button) => button.text() === 'Continue')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'Continue')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'Create invoice')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toMatch(/^\/invoices\/invo-/)
  })
})
