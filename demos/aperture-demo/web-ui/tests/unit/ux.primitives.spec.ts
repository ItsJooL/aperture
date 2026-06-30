import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PageHeader from '@/components/ui/PageHeader.vue'
import DataToolbar from '@/components/ui/DataToolbar.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppModal from '@/components/ui/AppModal.vue'

describe('UX primitives', () => {
  it('renders product-facing page header with breadcrumbs and actions', () => {
    const wrapper = mount(PageHeader, {
      props: {
        kicker: 'Customers',
        title: 'Customer accounts',
        subtitle: 'Manage profiles and billing readiness.',
        breadcrumbs: [{ label: 'Home', to: '/dashboard' }, { label: 'Customers' }],
      },
      slots: { actions: '<button>Create</button>' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })

    expect(wrapper.text()).toContain('Customers')
    expect(wrapper.text()).toContain('Customer accounts')
    expect(wrapper.text()).toContain('Manage profiles')
    expect(wrapper.text()).toContain('Create')
  })

  it('emits search changes from the data toolbar', async () => {
    const wrapper = mount(DataToolbar, {
      props: { search: '', count: 2, noun: 'customers', searchLabel: 'Search customers' },
    })

    await wrapper.find('input').setValue('north')

    expect(wrapper.emitted('update:search')?.[0]).toEqual(['north'])
    expect(wrapper.text()).toContain('2 customers')
  })

  it('wires field errors into accessible inputs', () => {
    const wrapper = mount(AppInput, {
      props: { modelValue: '', label: 'Email', error: 'Enter a valid email address.', id: 'email' },
    })

    const input = wrapper.find('input')
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(input.attributes('aria-describedby')).toContain('email-error')
    expect(wrapper.text()).toContain('Enter a valid email address.')
  })

  it('renders dialogs with modal accessibility attributes', async () => {
    const wrapper = mount(AppModal, {
      props: { open: true, title: 'Edit customer', description: 'Update profile details.' },
      slots: { default: '<button>Save</button>' },
      attachTo: document.body,
    })

    const dialog = document.body.querySelector('[role="dialog"]') as HTMLElement | null
    expect(dialog).not.toBeNull()
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    expect(document.body.textContent).toContain('Edit customer')
    wrapper.unmount()
  })

})
