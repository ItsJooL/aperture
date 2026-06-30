import { describe, expect, it } from 'vitest'
import { adminService } from '@/api/services/adminService'
import { customerService } from '@/api/services/customerService'
import { productService } from '@/api/services/productService'

describe('admin and lifecycle service flows', () => {
  it('revokes invites and disables integration credentials through mocked HTTP', async () => {
    const beforeInvites = await adminService.invites('tenant-demo')
    expect(beforeInvites.length).toBeGreaterThan(0)

    await adminService.revokeInvite('tenant-demo', beforeInvites[0].inviteId)
    expect(await adminService.invites('tenant-demo')).toHaveLength(beforeInvites.length - 1)

    const serviceAccount = (await adminService.serviceAccounts('tenant-demo'))[0]
    const disabledAccount = await adminService.disableServiceAccount('tenant-demo', serviceAccount.id)
    expect(disabledAccount.status).toBe('DISABLED')

    const key = (await adminService.apiKeys('tenant-demo'))[0]
    const revokedKey = await adminService.revokeApiKey('tenant-demo', key.id)
    expect(revokedKey.status).toBe('DISABLED')
  })

  it('archives customers and deactivates products without deleting history', async () => {
    await customerService.archive('cust-001')
    const archived = await customerService.get('cust-001')
    expect(archived?.deletedAt).toBeTruthy()

    await productService.deactivate('prod-001')
    const inactive = await productService.get('prod-001')
    expect(inactive?.active).toBe(false)
  })
})
