<template>
  <section class="hero">
    <p class="eyebrow">
      Open source
      <span class="divider">·</span>
      <a href="https://jsonapi.org" target="_blank" rel="noopener">JSON:API</a> compliant
      <span class="divider">·</span>
      Apache 2.0
    </p>

    <h1>Build the Business Model.<br><em>Ship the API.</em></h1>

    <p class="sub">
      Write a YAML manifest describing your domain. Aperture generates a
      fully-compliant JSON:API server — auth, multi-tenancy, hooks, and audit —
      with zero boilerplate code and zero schema management.
    </p>

    <div class="ctas">
      <a href="/guide/" class="btn-primary">Get Started <span aria-hidden>→</span></a>
      <a href="/examples/billing-demo" class="btn-outline">View Demo</a>
    </div>

    <div class="diagram">
      <AperturePipelineGraphic />
    </div>
  </section>
</template>

<script setup lang="ts">
import AperturePipelineGraphic from '../components/AperturePipelineGraphic.vue'
</script>

<style scoped>
.hero {
  background: var(--home-surface);
  border-bottom: 1px solid var(--home-border);
  padding: 96px 48px 80px;
  text-align: center;
}
.eyebrow {
  color: var(--home-muted); font-size: 12.5px; font-weight: 500;
  margin-bottom: 28px; letter-spacing: .3px;
}
.eyebrow a { color: var(--home-accent); text-underline-offset: 2px; }
.eyebrow .divider { color: var(--home-border); margin: 0 6px; }
h1 {
  font-size: 54px; font-weight: 800; letter-spacing: -2px;
  line-height: 1.08; color: var(--home-text); margin-bottom: 22px;
}
h1 em { font-style: normal; color: var(--home-accent); }
.sub {
  font-size: 17px; color: var(--home-muted); max-width: 520px;
  margin: 0 auto 36px; line-height: 1.65;
}
.ctas { display: flex; gap: 12px; justify-content: center; margin-bottom: 72px; }
.btn-primary {
  background: var(--home-accent); color: white;
  padding: 11px 24px; border-radius: 8px; font-size: 14px; font-weight: 600;
  text-decoration: none; transition: opacity .15s;
}
.btn-primary:hover { opacity: .85; }
.btn-outline {
  background: transparent; color: var(--home-text-2);
  border: 1.5px solid var(--home-border);
  padding: 11px 24px; border-radius: 8px; font-size: 14px;
  text-decoration: none; transition: all .15s;
}
.btn-outline:hover { border-color: var(--home-accent); color: var(--home-accent); }
.diagram {
  max-width: 920px; margin: 0 auto;
  background: var(--home-surface-2); border: 1px solid var(--home-border);
  border-radius: 20px; padding: 22px 22px 18px;
  box-shadow: 0 4px 24px rgba(0,0,0,0.06);
  overflow: hidden;
}
.diagram svg { width: 100%; height: auto; }
</style>
