<template>
  <div class="home">

    <!-- NAV -->
    <nav class="h-nav">
      <div class="h-nav-left">
        <a href="/" class="h-logo">
          <BrandWordmark class="h-logo-wordmark" />
        </a>
        <div class="h-nav-links" :class="{ open: mobileMenuOpen }">
          <a href="/guide/" @click="mobileMenuOpen = false">Guide</a>
          <a href="/reference/manifest-schema" @click="mobileMenuOpen = false">Reference</a>
          <a href="/examples/billing-demo" @click="mobileMenuOpen = false">Examples</a>
        </div>
      </div>
      <div class="h-nav-right">
        <a href="https://github.com/ItsJooL/aperture" class="h-github" target="_blank" rel="noopener">
          <GitHubIcon /> <span class="h-github-label">GitHub</span>
        </a>
        <button class="h-theme-toggle" @click="toggleTheme" aria-label="Toggle dark mode">
          <SunIcon class="h-icon-when-dark" />
          <MoonIcon class="h-icon-when-light" />
        </button>
        <button class="h-menu-toggle" @click="mobileMenuOpen = !mobileMenuOpen" :aria-expanded="mobileMenuOpen" aria-label="Toggle navigation menu">
          <span /><span /><span />
        </button>
      </div>
    </nav>

    <!-- DOT NAV -->
    <nav class="h-dot-nav" aria-label="Page sections">
      <a v-for="section in sections" :key="section.id"
         :href="'#' + section.id"
         :class="{ active: activeSection === section.id }"
         :title="section.label"
         @click.prevent="scrollTo(section.id)"
      />
    </nav>

    <!-- SECTIONS -->
    <HeroSection          id="hero"     />
    <HowItWorks           id="how"      />
    <FeaturesSection      id="features" />
    <FeatureMatrixSection id="matrix"   />
    <CtaSection           id="cta"      />

  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useData } from 'vitepress'
import HeroSection          from './sections/HeroSection.vue'
import HowItWorks           from './sections/HowItWorks.vue'
import FeaturesSection      from './sections/FeaturesSection.vue'
import FeatureMatrixSection from './sections/FeatureMatrixSection.vue'
import CtaSection           from './sections/CtaSection.vue'
import BrandWordmark   from './components/BrandWordmark.vue'
import GitHubIcon      from './icons/GitHubIcon.vue'
import SunIcon         from './icons/SunIcon.vue'
import MoonIcon        from './icons/MoonIcon.vue'

const sections = [
  { id: 'hero',     label: 'Home'          },
  { id: 'how',      label: 'How it works'  },
  { id: 'features', label: 'Features'      },
  { id: 'matrix',   label: 'All features'  },
  { id: 'cta',      label: 'Get started'   },
]

const activeSection = ref('hero')
const mobileMenuOpen = ref(false)

// Use VitePress's own isDark ref, which persists to localStorage and syncs with
// the toggle that appears in the DefaultTheme navbar on all other pages.
const { isDark } = useData()

function toggleTheme() {
  // Read the authoritative state off <html> (set by VitePress's appearance
  // script) rather than the isDark ref, which can lag after hydration.
  isDark.value = !document.documentElement.classList.contains('dark')
}

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })
}

let observer: IntersectionObserver | null = null

onMounted(() => {
  observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.isIntersecting) activeSection.value = entry.target.id
    })
  }, { rootMargin: '-30% 0px -60% 0px', threshold: 0 })

  sections.forEach(s => {
    const el = document.getElementById(s.id)
    if (el) observer!.observe(el)
  })
})

onUnmounted(() => observer?.disconnect())
</script>

<style scoped>
.home {
  font-family: -apple-system, BlinkMacSystemFont, 'Inter', 'Segoe UI', sans-serif;
  background: var(--home-bg);
  color: var(--home-text);
  min-height: 100vh;
  transition: background .25s, color .25s;
}
.h-nav {
  position: sticky; top: 0; z-index: 200;
  background: var(--home-surface); border-bottom: 1px solid var(--home-border);
  padding: 0 48px; height: 58px;
  display: flex; align-items: center; justify-content: space-between;
  transition: background .25s, border-color .25s;
}
.h-nav-left { display: flex; align-items: center; gap: 12px; }
.h-logo {
  display: flex; align-items: center;
  color: var(--vp-c-text-1); text-decoration: none;
  transition: color .15s;
}
.h-logo:hover { color: var(--vp-c-text-1); }
.h-logo-wordmark { display: block; width: auto; height: 48px; padding-top: 2px; box-sizing: border-box; transform: translateX(-40px); color: var(--vp-c-text-1); }
.h-nav-links { display: flex; gap: 20px; margin-left: -40px;}
.h-nav-links a { color: var(--home-muted); font-size: 13.5px; font-weight: 500; text-decoration: none; transition: color .15s; }
.h-nav-links a:hover { color: var(--home-text); }
.h-nav-right { display: flex; align-items: center; gap: 12px; }
.h-github {
  display: flex; align-items: center; gap: 6px;
  background: var(--home-surface-2); border: 1px solid var(--home-border);
  color: var(--home-text-2); padding: 6px 14px; border-radius: 7px;
  font-size: 12.5px; font-weight: 500; text-decoration: none; transition: all .15s;
}
.h-github:hover { border-color: var(--home-accent); color: var(--home-accent); }
.h-theme-toggle {
  width: 34px; height: 34px; border-radius: 7px;
  background: var(--home-surface-2); border: 1px solid var(--home-border);
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; transition: all .15s; color: var(--home-text-2);
}
.h-theme-toggle:hover { border-color: var(--home-accent); }
.h-menu-toggle {
  display: none;
  width: 34px; height: 34px; border-radius: 7px;
  background: var(--home-surface-2); border: 1px solid var(--home-border);
  flex-direction: column; align-items: center; justify-content: center; gap: 4px;
  cursor: pointer; transition: border-color .15s;
}
.h-menu-toggle:hover { border-color: var(--home-accent); }
.h-menu-toggle span { width: 15px; height: 1.5px; background: var(--home-text-2); border-radius: 1px; }
.h-dot-nav {
  position: fixed; right: 22px; top: 50%; transform: translateY(-50%);
  display: flex; flex-direction: column; gap: 12px; z-index: 199;
}
.h-dot-nav a {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--home-border); border: 1.5px solid var(--home-muted);
  display: block; transition: all .25s; opacity: .5; cursor: pointer;
}
.h-dot-nav a.active {
  background: var(--home-accent); border-color: var(--home-accent);
  opacity: 1; transform: scale(1.3);
}

@media (max-width: 640px) {
  .h-nav { padding: 0 16px; }
  /* Drop the desktop left-nudge (would clip the "A" off the left edge) and
     shrink to fit the mobile nav. */
  .h-logo-wordmark { transform: none; height: 40px; }
  .h-nav-links {
    display: none;
    position: absolute; top: 58px; left: 0; right: 0;
    flex-direction: column; gap: 0; margin-left: 0;
    background: var(--home-surface); border-bottom: 1px solid var(--home-border);
    padding: 6px 16px 12px;
  }
  .h-nav-links.open { display: flex; }
  .h-nav-links a { padding: 11px 0; border-bottom: 1px solid var(--home-border); }
  .h-nav-links a:last-child { border-bottom: none; }
  .h-github-label { display: none; }
  .h-github { padding: 8px; }
  .h-nav-right { gap: 8px; }
  .h-menu-toggle { display: flex; }
}
</style>
