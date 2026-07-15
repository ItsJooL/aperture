<template>
  <div class="code-window">
    <div class="cw-header">
      <span class="dot r"/><span class="dot y"/><span class="dot g"/>
      <span class="cw-filename">{{ filename }}</span>
    </div>
    <pre class="cw-body"><code>{{ code }}</code></pre>
  </div>
</template>

<script setup lang="ts">
defineProps<{ filename: string; code: string }>()
</script>

<style scoped>
.code-window {
  border-radius: 10px; overflow: hidden;
  border: 1px solid var(--home-border);
  box-shadow: 0 8px 32px rgba(0,0,0,0.10);
  box-sizing: border-box;
  min-width: 0;
  width: 100%;
}
.cw-header {
  background: var(--home-surface-2);
  border-bottom: 1px solid var(--home-border);
  padding: 9px 14px;
  display: flex; align-items: center; gap: 6px;
}
.dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.r { background: #f87171; } .y { background: #fbbf24; } .g { background: #34d399; }
.cw-filename {
  margin-left: 8px; font-size: 11px; color: var(--home-muted);
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}
.cw-body {
  background: var(--home-code-bg);
  margin: 0; padding: 20px 22px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px; line-height: 1.9;
  color: var(--home-code-text);
  overflow-x: auto;
  white-space: pre;
}
</style>
