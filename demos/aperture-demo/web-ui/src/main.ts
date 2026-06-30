import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from './vue-query-wrapper'
import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)

app.use(createPinia())
app.use(VueQueryPlugin)
app.use(router)
app.mount('#app')
