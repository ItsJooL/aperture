import DefaultTheme from 'vitepress/theme'
import { h }         from 'vue'
import { useData }   from 'vitepress'
import HomeLayout    from './HomeLayout.vue'
import BrandWordmark from './components/BrandWordmark.vue'
import './style.css'

export default {
  extends: DefaultTheme,
  Layout() {
    const { frontmatter } = useData()
    if (frontmatter.value.layout === 'home-custom') {
      return h(HomeLayout)
    }
    return h(DefaultTheme.Layout, null, {
      'nav-bar-title-before': () =>
        h(BrandWordmark, { class: 'vp-brand-wordmark', 'aria-hidden': 'true' }),
    })
  },
}
