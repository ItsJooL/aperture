<template>
  <section class="content-wrap">
    <div class="hero-panel">
      <p class="page-kicker">Access restricted</p>
      <h1 class="page-title">You do not have permission to view this area.</h1>
      <p class="page-subtitle">
        Administration is only shown to SuperAdmins or users with an admin role such as TenantAdmin.
        You can continue using the workspace features available to your account.
      </p>
      <div class="flex" style="margin-top:1.5rem; gap:.75rem;">
        <RouterLink to="/dashboard"><AppButton>Back to dashboard</AppButton></RouterLink>
        <RouterLink to="/settings"><AppButton variant="outline">View account settings</AppButton></RouterLink>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import AppButton from '@/components/ui/AppButton.vue'
</script>
