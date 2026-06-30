import { defineStore } from 'pinia'

type Toast = {
  id: number
  title: string
  message?: string
  kind: 'success' | 'error' | 'info'
}

export const useAppStore = defineStore('app', {
  state: () => ({
    theme: (localStorage.getItem('aperture.theme') || 'light') as 'light' | 'dark',
    toasts: [] as Toast[],
  }),
  actions: {
    applyTheme() {
      document.documentElement.classList.toggle('dark', this.theme === 'dark')
      localStorage.setItem('aperture.theme', this.theme)
    },
    toggleTheme() {
      this.theme = this.theme === 'dark' ? 'light' : 'dark'
      this.applyTheme()
    },
    toast(title: string, message?: string, kind: Toast['kind'] = 'info') {
      const id = Date.now() + Math.round(Math.random() * 1000)
      this.toasts.push({ id, title, message, kind })
      window.setTimeout(() => {
        this.toasts = this.toasts.filter((toast) => toast.id !== id)
      }, 3800)
    },
  },
})
