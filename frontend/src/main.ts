import { createApp } from 'vue'
import App from './App.vue'
import { initializeLanguage } from './i18n'
import './style.css'
import './desktop.css'
import 'katex/dist/katex.min.css'

initializeLanguage().finally(()=>createApp(App).mount('#app'))
