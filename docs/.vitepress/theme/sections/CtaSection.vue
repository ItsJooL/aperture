<template>
  <section class="cta">
    <h2>Ready to stop bikeshedding?</h2>
    <p>Write a manifest. Ship a production API. Focus on the model — Aperture handles the exposure.</p>
    <div class="cta-buttons">
      <a href="/guide/" class="btn-primary">Get Started →</a>
      <a href="https://github.com/ItsJooL/aperture" class="btn-outline" target="_blank" rel="noopener">View on GitHub</a>
    </div>
  </section>
</template>

<style scoped>
.cta {
  background: var(--home-bg);
  border-top: 1px solid var(--home-border);
  padding: 80px 48px; text-align: center;
}
h2 {
  font-size: 34px; font-weight: 800; letter-spacing: -1px;
  color: var(--home-text); margin-bottom: 12px;
}
p { color: var(--home-muted); font-size: 15px; margin-bottom: 32px; }
.cta-buttons { display: flex; gap: 12px; justify-content: center; }
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
</style>
