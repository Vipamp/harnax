import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getStorage, setStorage } from '@/utils/storage'

const STORAGE_KEY = 'connection_config'

export const useConnectionStore = defineStore('connection', () => {
  const adminUrl = ref('')
  const routerUrl = ref('')
  const apiKey = ref('')
  const token = ref('')
  const username = ref('')
  const isConnected = ref(false)
  const isTesting = ref(false)

  const isLoggedIn = computed(() => !!token.value)

  function loadFromStorage() {
    const config = getStorage<{
      adminUrl: string
      routerUrl: string
      apiKey: string
      token: string
      username: string
    }>(STORAGE_KEY)
    if (config) {
      adminUrl.value = config.adminUrl || ''
      routerUrl.value = config.routerUrl || ''
      apiKey.value = config.apiKey || ''
      token.value = config.token || ''
      username.value = config.username || ''
    }
  }

  function save() {
    setStorage(STORAGE_KEY, {
      adminUrl: adminUrl.value,
      routerUrl: routerUrl.value,
      apiKey: apiKey.value,
      token: token.value,
      username: username.value,
    })
  }

  function setConnected(connected: boolean) {
    isConnected.value = connected
  }

  function setToken(newToken: string) {
    token.value = newToken
    save()
  }

  function clearAuth() {
    token.value = ''
    username.value = ''
    isConnected.value = false
    save()
  }

  function isConfigured(): boolean {
    return !!adminUrl.value && !!routerUrl.value && !!token.value
  }

  function getRouterBaseUrl(): string {
    return routerUrl.value.replace(/\/+$/, '')
  }

  function getAdminBaseUrl(): string {
    return adminUrl.value.replace(/\/+$/, '')
  }

  return {
    adminUrl,
    routerUrl,
    apiKey,
    token,
    username,
    isConnected,
    isTesting,
    isLoggedIn,
    loadFromStorage,
    save,
    setConnected,
    setToken,
    clearAuth,
    isConfigured,
    getRouterBaseUrl,
    getAdminBaseUrl,
  }
})
