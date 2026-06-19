import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import App from './App.vue'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'

export function createApp() {
  const app = createSSRApp(App)

  const pinia = createPinia()
  app.use(pinia)

  const i18n = createI18n({
    locale: 'zh-CN',
    fallbackLocale: 'en-US',
    messages: {
      'zh-CN': zhCN,
      'en-US': enUS,
    },
  })
  app.use(i18n)

  return { app }
}
