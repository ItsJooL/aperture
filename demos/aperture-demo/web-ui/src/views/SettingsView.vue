<template>
  <section class="content-wrap">
    <PageHeader kicker="Settings" title="Workspace settings" subtitle="Manage appearance, session details and account preferences." />
    <section class="grid grid-2">
      <AppCard>
        <h2 class="card-title">Profile</h2>
        <p class="card-description">Signed-in account details for this session.</p>
        <div class="detail-list" style="margin-top:1rem;">
          <div class="detail-row"><span class="muted">Username</span><strong>{{ auth.user?.username }}</strong></div>
          <div class="detail-row"><span class="muted">Tenant</span><span class="mono">{{ auth.tenantId }}</span></div>
          <div class="detail-row"><span class="muted">Access</span><StatusBadge :status="auth.canAccessAdmin ? 'admin' : 'member'" /></div>
        </div>
      </AppCard>
      <AppCard>
        <h2 class="card-title">Appearance</h2>
        <p class="card-description">Use the Natural Slate / Sage theme in light or soft dark mode.</p>
        <div class="flex" style="gap:.65rem; margin-top:1rem;"><AppButton @click="app.toggleTheme">Switch to {{ app.theme === 'dark' ? 'light' : 'dark' }} mode</AppButton></div>
      </AppCard>
      <AppCard>
        <h2 class="card-title">Password</h2>
        <p class="card-description">Update your password when required by your organisation.</p>
        <form class="stack" style="margin-top:1rem;" @submit.prevent="changePassword">
          <AppInput v-model="currentPassword" label="Current password" type="password" />
          <AppInput v-model="newPassword" label="New password" type="password" />
          <InlineFormError :message="passwordError" />
          <div><AppButton type="submit">Update password</AppButton></div>
        </form>
      </AppCard>
    </section>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import InlineFormError from '@/components/ui/InlineFormError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'

const app = useAppStore()
const auth = useAuthStore()
const currentPassword = ref('')
const newPassword = ref('')
const passwordError = ref<string | undefined>()
async function changePassword() {
  passwordError.value = undefined
  if (newPassword.value.length < 8) {
    passwordError.value = 'Use at least 8 characters.'
    return
  }
  await auth.changePassword(currentPassword.value, newPassword.value)
  currentPassword.value = ''
  newPassword.value = ''
  app.toast('Password updated', 'Your account password has been changed.', 'success')
}
</script>
