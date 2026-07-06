<template>
  <main class="hero-card">
    <section class="auth-art">
      <div class="brand-block" style="border:0;">
        <div class="brand-mark">A</div>
        <div>
          <strong>Aperture Workspace</strong>
          <div class="muted">Multi-tenant billing and operations</div>
        </div>
      </div>
      <div>
        <p class="page-kicker">Natural Slate / Sage</p>
        <h1 class="page-title">Run customer, product and billing workflows from one calm workspace.</h1>
        <p class="page-subtitle">A guided workspace for customer accounts, product catalogues, invoices and payments.</p>
      </div>
      <div class="grid grid-3">
        <div class="card card-pad"><strong>Customers</strong><p class="muted">Profiles and invoice history.</p></div>
        <div class="card card-pad"><strong>Products</strong><p class="muted">Catalogue and pricing.</p></div>
        <div class="card card-pad"><strong>Billing</strong><p class="muted">Invoices and payments.</p></div>
      </div>
    </section>
    <section class="auth-panel">
      <AppCard class="auth-card demo-quickbar-card" padded>
        <div class="demo-quickbar-heading">
          <span class="demo-quickbar-icon" aria-hidden="true"><Sparkles class="button-icon" /></span>
          <div>
            <p class="page-kicker">Demo personas · one-click login</p>
            <h2 class="card-title">Jump straight into a seeded workspace</h2>
          </div>
        </div>
        <div class="demo-chip-row">
          <button
            v-for="persona in demoPersonas"
            :key="persona.id"
            type="button"
            class="demo-chip"
            :disabled="loading"
            :title="persona.description"
            @click="loginAs(persona)"
          >
            <strong>{{ persona.label }}</strong>
            <small>{{ persona.tenant }} · {{ persona.role }}</small>
          </button>
        </div>
        <p class="demo-chip-hint muted">Hover a persona for what it can do — no password needed.</p>
      </AppCard>

      <AppCard class="auth-card">
        <p class="page-kicker">Welcome back</p>
        <h2 style="font-size:2rem; margin:.25rem 0 .6rem; letter-spacing:-.04em;">Sign in</h2>
        <p class="card-description">Use your tenant user account to open the Aperture workspace.</p>
        <form class="stack" style="margin-top:1.25rem;" @submit.prevent="submit">
          <AppInput v-model="username" label="Username" placeholder="admin@aperture.local" />
          <AppInput v-model="password" label="Password" type="password" placeholder="••••••••" />
          <AppButton type="submit" :disabled="loading">{{ loading ? 'Signing in…' : 'Sign in' }}</AppButton>
          <RouterLink to="/accept-invite" class="muted" style="text-align:center; font-weight:700;">Accept an invitation instead</RouterLink>
        </form>
      </AppCard>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { Sparkles } from '@lucide/vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import { demoPersonas, type DemoPersona } from '@/data/demoPersonas'
import { useAppStore } from '@/stores/appStore'
import { useAuthStore } from '@/stores/authStore'

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const auth = useAuthStore()
app.applyTheme()
const username = ref('admin@aperture.local')
const password = ref('aperture-demo')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    app.toast('Signed in', 'Your workspace is ready.', 'success')
    router.push((route.query.redirect as string) || '/dashboard')
  } catch (error) {
    app.toast('Unable to sign in', error instanceof Error ? error.message : 'Check your credentials.', 'error')
  } finally {
    loading.value = false
  }
}

async function loginAs(persona: DemoPersona) {
  username.value = persona.username
  password.value = persona.password
  await submit()
}
</script>
