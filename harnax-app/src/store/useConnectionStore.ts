import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getStorage, setStorage } from '@/utils/storage'

const STORAGE_KEY = 'connection_config'

export const useConnectionStore = defineStore('connection', () => {
  const routerUrl = ref('')
  const apiKey = ref('')
  const isConnected = ref(false)
  const isTesting = ref(false)

  function loadFromStorage() {
    const config = getStorage<{ routerUrl: string; apiKey: string }>(STORAGE_KEY)
    if (config) {
      routerUrl.value = config.routerUrl || ''
      apiKey.value = config.apiKey || ''
    }
  }

  function save() {
    setStorage(STORAGE_KEY, {
      routerUrl: routerUrl.value,
      apiKey: apiKey.value,
    })
  }

  function setConnected(connected: boolean) {
    isConnected.value = connected
  }

  function isConfigured(): boolean {
    return !!routerUrl.value && !!apiKey.value
  }

  function getBaseUrl(): string {
    return routerUrl.value.replace(/\/+$/, '')
  }

  return {
    routerUrl,
    apiKey,
    isConnected,
    isTesting,
    loadFromStorage,
    save,
    setConnected,
    isConfigured,
    getBaseUrl,
  }
})
