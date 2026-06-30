<template>
  <section class="content-wrap">
    <PageHeader
      kicker="Administration"
      title="Tenant administration"
      subtitle="Manage members, invitations, service access and tenant settings from role-protected screens."
    >
      <template #actions>
        <AppButton v-if="auth.hasPermission('admin:tenants:write')" @click="tenantOpen = true">Provision tenant</AppButton>
      </template>
    </PageHeader>

    <AppCard v-if="isSuperAdminView" class="super-admin-card">
      <div>
        <p class="page-kicker">System scope</p>
        <h2 class="card-title">System administration overview</h2>
        <p class="card-description">Choose a tenant to inspect users, invitations, service accounts and API keys without switching out of the SuperAdmin session.</p>
      </div>
      <label class="field super-admin-tenant-picker">
        <span>Tenant context</span>
        <select v-model="selectedTenantId" class="select" aria-label="Admin tenant context">
          <option value="">Select tenant</option>
          <option v-for="tenant in tenants" :key="tenant.id" :value="tenant.id">{{ tenant.name || tenant.id }}</option>
        </select>
      </label>
    </AppCard>

    <div class="grid grid-4">
      <StatCard label="Tenant" :value="selectedTenantName" detail="Current admin context" />
      <StatCard label="Users" :value="users.length" detail="Tenant members" />
      <StatCard label="Invites" :value="invites.length" detail="Pending onboarding" />
      <StatCard label="Service access" :value="serviceAccounts.length + apiKeys.length" detail="Accounts and keys" />
    </div>

    <div class="tab-list" role="tablist" aria-label="Admin sections">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        class="tab-button"
        role="tab"
        :aria-selected="activeTab === tab.value"
        :aria-controls="`admin-panel-${tab.value}`"
        @click="activeTab = tab.value"
      >
        {{ tab.label }}
      </button>
    </div>

    <ErrorState v-if="hasError" description="Administration data could not be loaded."><AppButton variant="outline" @click="refetchAll">Retry</AppButton></ErrorState>
    <PageSkeleton v-else-if="isLoading" />

    <section v-else :id="`admin-panel-${activeTab}`" role="tabpanel" tabindex="0">
      <AppCard v-if="activeTab === 'users'">
        <div class="flex-between">
          <div>
            <h2 class="card-title">Users</h2>
            <p class="card-description">People with access to this tenant.</p>
          </div>
          <AppButton v-if="auth.hasPermission('admin:invites:write')" variant="outline" @click="inviteOpen = true">Invite user</AppButton>
        </div>
        <div class="table-wrap" style="margin-top:1rem;">
          <table class="responsive-table">
            <thead><tr><th>User</th><th>Status</th><th>Roles</th><th>Security</th><th>Profile</th><th>Action</th></tr></thead>
            <tbody>
              <tr v-for="user in users" :key="user.id">
                <td data-label="User"><strong>{{ user.username }}</strong></td>
                <td data-label="Status"><StatusBadge :status="user.status" /></td>
                <td data-label="Roles">{{ rolesFor(user).join(', ') || 'Member' }}</td>
                <td data-label="Security"><span class="muted">{{ formatAttributes(user.securityAttributes) }}</span></td>
                <td data-label="Profile"><span class="muted">{{ formatAttributes(user.profile) }}</span></td>
                <td data-label="Action">
                  <AppSelect
                    v-if="auth.hasPermission('admin:users:write')"
                    :model-value="rolesFor(user)[0] || 'Viewer'"
                    :options="roleOptions"
                    aria-label="Update role"
                    @update:model-value="replaceRoles(user.id, [$event])"
                  />
                  <span v-else class="permission-note">Read only</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </AppCard>

      <AppCard v-else-if="activeTab === 'invites'">
        <div class="flex-between">
          <div>
            <h2 class="card-title">Invitations</h2>
            <p class="card-description">Invite people to create an account and join the tenant.</p>
          </div>
          <AppButton v-if="auth.hasPermission('admin:invites:write')" variant="outline" @click="inviteOpen = true">Create invite</AppButton>
        </div>
        <div class="table-wrap" style="margin-top:1rem;">
          <table class="responsive-table">
            <thead><tr><th>Invite</th><th>Status</th><th>Roles</th><th>Expires</th><th>Action</th></tr></thead>
            <tbody>
              <tr v-for="invite in invites" :key="invite.inviteId">
                <td data-label="Invite" class="mono">{{ invite.inviteId }}</td>
                <td data-label="Status"><StatusBadge :status="invite.status" /></td>
                <td data-label="Roles">{{ invite.roleNames?.join(', ') || 'Viewer' }}</td>
                <td data-label="Expires">{{ dateShort(invite.expiresAt) }}</td>
                <td data-label="Action">
                  <AppButton v-if="auth.hasPermission('admin:invites:write')" variant="outline" @click="confirmAction = { type: 'invite', id: invite.inviteId }">Revoke</AppButton>
                  <span v-else class="permission-note">Read only</span>
                </td>
              </tr>
              <tr v-if="!invites.length"><td colspan="5"><EmptyState title="No open invites" description="Create an invite when someone needs access." /></td></tr>
            </tbody>
          </table>
        </div>
      </AppCard>

      <AppCard v-else-if="activeTab === 'service'">
        <div class="flex-between">
          <div>
            <h2 class="card-title">Service accounts</h2>
            <p class="card-description">Machine identities for integrations and scheduled jobs.</p>
          </div>
          <AppButton v-if="auth.hasPermission('admin:service-access:write')" variant="outline" @click="createServiceAccount">Create service account</AppButton>
        </div>
        <div class="table-wrap" style="margin-top:1rem;">
          <table class="responsive-table">
            <thead><tr><th>Client</th><th>Status</th><th>Expires</th><th>Action</th></tr></thead>
            <tbody>
              <tr v-for="account in serviceAccounts" :key="account.id">
                <td data-label="Client" class="mono">{{ account.clientId }}</td>
                <td data-label="Status"><StatusBadge :status="account.status" /></td>
                <td data-label="Expires">{{ dateShort(account.expiresAt) }}</td>
                <td data-label="Action">
                  <AppButton v-if="auth.hasPermission('admin:service-access:write')" variant="outline" :disabled="account.status === 'DISABLED'" @click="confirmAction = { type: 'serviceAccount', id: account.id }">Disable</AppButton>
                  <span v-else class="permission-note">Read only</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </AppCard>

      <AppCard v-else-if="activeTab === 'keys'">
        <div class="flex-between">
          <div>
            <h2 class="card-title">Personal API keys</h2>
            <p class="card-description">Personal API keys are created by users themselves. Admins can audit and revoke keys for this tenant.</p>
          </div>
        </div>
        <div class="table-wrap" style="margin-top:1rem;">
          <table class="responsive-table">
            <thead><tr><th>Key</th><th>Status</th><th>Last used</th><th>Action</th></tr></thead>
            <tbody>
              <tr v-for="key in apiKeys" :key="key.id">
                <td data-label="Key" class="mono">{{ key.id }}</td>
                <td data-label="Status"><StatusBadge :status="key.status" /></td>
                <td data-label="Last used">{{ dateShort(key.lastUsedAt) }}</td>
                <td data-label="Action">
                  <AppButton v-if="auth.hasPermission('admin:service-access:write')" variant="outline" :disabled="key.status === 'DISABLED'" @click="confirmAction = { type: 'apiKey', id: key.id }">Revoke</AppButton>
                  <span v-else class="permission-note">Read only</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </AppCard>

      <AppCard v-else-if="activeTab === 'tenants'">
        <div class="flex-between">
          <div>
            <h2 class="card-title">Tenants</h2>
            <p class="card-description">Workspace records available to administrators.</p>
          </div>
          <AppButton v-if="auth.hasPermission('admin:tenants:write')" variant="outline" @click="tenantOpen = true">Provision tenant</AppButton>
        </div>
        <div class="table-wrap" style="margin-top:1rem;">
          <table class="responsive-table">
            <thead><tr><th>Tenant</th><th>Status</th></tr></thead>
            <tbody>
              <tr v-for="tenant in tenants" :key="tenant.id">
                <td data-label="Tenant"><strong>{{ tenant.name || tenant.id }}</strong><div class="muted mono">{{ tenant.id }}</div></td>
                <td data-label="Status"><StatusBadge :status="tenant.status" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </AppCard>

      <AppCard v-else>
        <h2 class="card-title">Audit activity</h2>
        <p class="card-description">A lightweight activity feed for sensitive access changes.</p>
        <div class="timeline" style="margin-top:1rem;">
          <div v-for="item in auditItems" :key="item" class="timeline-item"><div class="timeline-dot"><Check class="timeline-icon" aria-hidden="true" /></div><div><strong>{{ item }}</strong><p class="muted">Recorded in this admin session.</p></div></div>
          <EmptyState v-if="!auditItems.length" title="No admin activity yet" description="Role updates, invites and key changes will be listed here during the session." />
        </div>
      </AppCard>
    </section>

    <AppModal :open="inviteOpen" title="Invite user" description="Send an invite that opens the account setup journey." @close="inviteOpen = false">
      <form class="stack" @submit.prevent="createInvite">
        <AppInput v-model="inviteEmail" label="Email" placeholder="person@company.example" inputmode="email" autocomplete="email" :error="inviteError" />
        <AppSelect v-model="inviteRole" label="Role" :options="roleOptions" />
        <div class="form-actions"><AppButton type="submit">Create invite</AppButton></div>
      </form>
    </AppModal>
    <AppModal :open="tenantOpen" title="Provision tenant" description="Create a new tenant workspace." @close="tenantOpen = false">
      <form class="stack" @submit.prevent="provisionTenant">
        <AppInput v-model="tenantName" label="Tenant name" placeholder="New tenant" :error="tenantError" />
        <div class="form-actions"><AppButton type="submit">Provision tenant</AppButton></div>
      </form>
    </AppModal>
    <ConfirmDialog :open="Boolean(confirmAction)" title="Confirm access change" description="This will change access for a user or integration." confirm-label="Confirm" tone="danger" @cancel="confirmAction = null" @confirm="runConfirmedAction" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Check } from '@lucide/vue'
import { useQuery, useQueryClient } from '@/vue-query-wrapper'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageSkeleton from '@/components/ui/PageSkeleton.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { adminService } from '@/api/services/adminService'
import type { User } from '@/api/types/domain'
import { dateShort } from '@/lib/utils'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'

const app = useAppStore()
const auth = useAuthStore()
const client = useQueryClient()
const tenantId = computed(() => auth.tenantId)
const selectedTenantId = ref('')
const isSuperAdminView = computed(() => auth.hasPermission('admin:tenants:write') && !auth.tenantId)
const effectiveTenantId = computed(() => isSuperAdminView.value ? selectedTenantId.value : tenantId.value)
const tabs = computed(() => [
  { label: 'Users', value: 'users' },
  { label: 'Invites', value: 'invites' },
  { label: 'Service accounts', value: 'service' },
  { label: 'API keys', value: 'keys' },
  ...(auth.hasPermission('admin:tenants:write') ? [{ label: 'Tenants', value: 'tenants' }] : []),
  { label: 'Audit', value: 'audit' },
])
const activeTab = ref('users')
const usersQuery = useQuery({ queryKey: ['admin-users', effectiveTenantId], queryFn: () => effectiveTenantId.value ? adminService.users(effectiveTenantId.value) : Promise.resolve([]) })
const invitesQuery = useQuery({ queryKey: ['admin-invites', effectiveTenantId], queryFn: () => effectiveTenantId.value ? adminService.invites(effectiveTenantId.value) : Promise.resolve([]) })
const serviceAccountsQuery = useQuery({ queryKey: ['service-accounts', effectiveTenantId], queryFn: () => effectiveTenantId.value ? adminService.serviceAccounts(effectiveTenantId.value) : Promise.resolve([]) })
const apiKeysQuery = useQuery({ queryKey: ['api-keys', effectiveTenantId], queryFn: () => effectiveTenantId.value ? adminService.apiKeys(effectiveTenantId.value) : Promise.resolve([]) })
const tenantsQuery = useQuery({ queryKey: ['tenants'], queryFn: () => auth.hasPermission('admin:tenants:write') ? adminService.tenants() : Promise.resolve([]) })
const users = computed(() => usersQuery.data.value ?? [])
const invites = computed(() => invitesQuery.data.value ?? [])
const serviceAccounts = computed(() => serviceAccountsQuery.data.value ?? [])
const apiKeys = computed(() => apiKeysQuery.data.value ?? [])
const tenants = computed(() => tenantsQuery.data.value ?? [])
const selectedTenant = computed(() => tenants.value.find((tenant) => tenant.id === effectiveTenantId.value))
const selectedTenantName = computed(() => selectedTenant.value?.name || effectiveTenantId.value || 'Framework')
const isLoading = computed(() => [usersQuery, invitesQuery, serviceAccountsQuery, apiKeysQuery, tenantsQuery].some((q) => q.isLoading.value))
const hasError = computed(() => [usersQuery, invitesQuery, serviceAccountsQuery, apiKeysQuery, tenantsQuery].some((q) => q.isError.value))
const inviteOpen = ref(false)
const tenantOpen = ref(false)
const inviteEmail = ref('')
const inviteRole = ref('Viewer')
const tenantName = ref('')
const inviteError = ref<string | undefined>()
const tenantError = ref<string | undefined>()
const roleOptions = computed(() => {
  const base = [{ label: 'Accountant', value: 'Accountant' }, { label: 'Viewer', value: 'Viewer' }]
  if (auth.hasPermission('admin:tenants:write') && !auth.tenantId) {
    base.unshift({ label: 'Tenant admin', value: 'TenantAdmin' })
  }
  return base
})
const auditItems = ref<string[]>([])
const confirmAction = ref<null | { type: 'invite' | 'serviceAccount' | 'apiKey'; id: string }>(null)
function rolesFor(user: User) { return user.roleNames ?? user.roles ?? [] }
function formatAttributes(attrs: Record<string, unknown> | undefined) {
  if (!attrs) return '-'
  const keys = Object.keys(attrs)
  if (!keys.length) return '-'
  return keys.map((k) => `${k}: ${attrs[k]}`).join(', ')
}
function refetchAll() { usersQuery.refetch(); invitesQuery.refetch(); serviceAccountsQuery.refetch(); apiKeysQuery.refetch(); tenantsQuery.refetch() }
watch(tenants, (next) => {
  if (isSuperAdminView.value && !selectedTenantId.value && next.length) selectedTenantId.value = next[0].id
}, { immediate: true })
watch(effectiveTenantId, () => {
  void usersQuery.refetch()
  void invitesQuery.refetch()
  void serviceAccountsQuery.refetch()
  void apiKeysQuery.refetch()
})
async function replaceRoles(userId: string, roleNames: string[]) {
  if (!auth.hasPermission('admin:users:write')) return
  if (!effectiveTenantId.value) return
  await adminService.replaceUserRoles(effectiveTenantId.value, userId, roleNames)
  auditItems.value.unshift(`Updated roles for ${userId}`)
  app.toast('Roles updated', 'Access has been changed.', 'success')
  await client.invalidateQueries({ queryKey: ['admin-users'] })
}
async function createInvite() {
  if (!auth.hasPermission('admin:invites:write')) return
  inviteError.value = undefined
  if (!effectiveTenantId.value) { inviteError.value = 'Choose a tenant first.'; return }
  if (!/^\S+@\S+\.\S+$/.test(inviteEmail.value)) { inviteError.value = 'Enter a valid email address.'; return }
  await adminService.createInvite(effectiveTenantId.value, inviteEmail.value, [inviteRole.value])
  inviteOpen.value = false
  auditItems.value.unshift(`Created invite for ${inviteEmail.value}`)
  inviteEmail.value = ''
  app.toast('Invite created', 'The user can now accept the invite.', 'success')
  await client.invalidateQueries({ queryKey: ['admin-invites'] })
}
async function provisionTenant() {
  if (!auth.hasPermission('admin:tenants:write')) return
  tenantError.value = undefined
  if (!tenantName.value.trim()) { tenantError.value = 'Tenant name is required.'; return }
  await adminService.provisionTenant(tenantName.value)
  auditItems.value.unshift(`Provisioned tenant ${tenantName.value}`)
  tenantOpen.value = false
  tenantName.value = ''
  app.toast('Tenant provisioned', 'New workspace created.', 'success')
  await client.invalidateQueries({ queryKey: ['tenants'] })
}
async function createServiceAccount() {
  if (!auth.hasPermission('admin:service-access:write')) return
  if (!effectiveTenantId.value) return
  const result = await adminService.createServiceAccount(effectiveTenantId.value, 'demo-service-account') as any
  auditItems.value.unshift('Created service account')
  app.toast('Service account created', `Secret: ${result.secret} - Store securely. It will not be shown again.`, 'success')
  await client.invalidateQueries({ queryKey: ['service-accounts'] })
}
async function runConfirmedAction() {
  if (!confirmAction.value) return
  const action = confirmAction.value
  if (action.type === 'invite' && !auth.hasPermission('admin:invites:write')) return
  if ((action.type === 'serviceAccount' || action.type === 'apiKey') && !auth.hasPermission('admin:service-access:write')) return
  if (!effectiveTenantId.value) return
  if (action.type === 'invite') { await adminService.revokeInvite(effectiveTenantId.value, action.id); await client.invalidateQueries({ queryKey: ['admin-invites'] }); auditItems.value.unshift(`Revoked invite ${action.id}`) }
  if (action.type === 'serviceAccount') { await adminService.disableServiceAccount(effectiveTenantId.value, action.id); await client.invalidateQueries({ queryKey: ['service-accounts'] }); auditItems.value.unshift(`Disabled service account ${action.id}`) }
  if (action.type === 'apiKey') { await adminService.revokeApiKey(effectiveTenantId.value, action.id); await client.invalidateQueries({ queryKey: ['api-keys'] }); auditItems.value.unshift(`Revoked API key ${action.id}`) }
  app.toast('Access updated', 'The change has been applied.', 'success')
  confirmAction.value = null
}
</script>
