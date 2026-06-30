<template>
  <main class="hero-card">
    <section class="auth-art">
      <div class="brand-block" style="border:0;">
        <div class="brand-mark">A</div>
        <div><strong>Aperture</strong><div class="muted">Invitation setup</div></div>
      </div>
      <div>
        <p class="page-kicker">Account setup</p>
        <h1 class="page-title">Create your workspace account from an invite.</h1>
        <p class="page-subtitle">Use the invitation your administrator sent you to create an account and join the workspace.</p>
      </div>
      <div class="card card-pad"><strong>What happens next?</strong><p class="muted">Your invite confirms your workspace access and opens the app when setup is complete.</p></div>
    </section>
    <section class="auth-panel">
      <AppCard class="auth-card">
        <p class="page-kicker">Invitation</p>
        <h2 style="font-size:2rem; margin:.25rem 0 .6rem; letter-spacing:-.04em;">Set up account</h2>
        <form class="stack" style="margin-top:1.25rem;" @submit.prevent="submit">
          <AppInput v-model="token" label="Invite token" placeholder="Paste invitation token" />
          <AppInput v-model="username" label="Username" placeholder="you@company.example" />
          <AppInput v-model="password" label="Password" type="password" placeholder="Choose a password" />
          <AppButton type="submit" :disabled="loading">{{ loading ? 'Creating account…' : 'Create account' }}</AppButton>
          <RouterLink to="/login" class="muted" style="text-align:center; font-weight:700;">Back to sign in</RouterLink>
        </form>
      </AppCard>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'

const router = useRouter()
const app = useAppStore()
const auth = useAuthStore()
const token = ref('demo-invite-token')
const username = ref('new.user@aperture.local')
const password = ref('aperture-demo')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await auth.acceptInvite(token.value, username.value, password.value)
    app.toast('Account created', 'Welcome to the Aperture workspace.', 'success')
    router.push('/dashboard')
  } catch (error) {
    app.toast('Invite could not be accepted', error instanceof Error ? error.message : 'Check the token and try again.', 'error')
  } finally {
    loading.value = false
  }
}
</script>
