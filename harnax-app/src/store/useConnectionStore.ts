import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getStorage, setStorage } from '@/utils/storage'
import { i18n } from '@/i18n'

const STORAGE_KEY = 'connection_config'
const TOKEN_REFRESH_THRESHOLD = 5 * 60 * 1000 // 5 minutes before expiry

export const useConnectionStore = defineStore('connection', () => {
  const adminUrl = ref('')
  const routerUrl = ref('')
  const routerApiKey = ref('')
  const token = ref('')
  const username = ref('')
  const userId = ref<number | null>(null)
  const nickname = ref('')
  const tokenExpiresAt = ref<number>(0)

  let refreshTimer: ReturnType<typeof setTimeout> | null = null

  const isLoggedIn = computed(() => !!token.value)

  const isTokenExpiringSoon = computed(() => {
    if (!tokenExpiresAt.value) return false
    return Date.now() >= tokenExpiresAt.value - TOKEN_REFRESH_THRESHOLD
  })

  function loadFromStorage() {
    const config = getStorage<{
      adminUrl: string
      routerUrl: string
      routerApiKey: string
      token: string
      username: string
      userId: number | null
      nickname: string
      tokenExpiresAt: number
    }>(STORAGE_KEY)
    if (config) {
      adminUrl.value = config.adminUrl || ''
      routerUrl.value = config.routerUrl || ''
      routerApiKey.value = config.routerApiKey || ''
      token.value = config.token || ''
      username.value = config.username || ''
      userId.value = config.userId ?? null
      nickname.value = config.nickname || ''
      tokenExpiresAt.value = config.tokenExpiresAt || 0
    }
  }

  function save() {
    setStorage(STORAGE_KEY, {
      adminUrl: adminUrl.value,
      routerUrl: routerUrl.value,
      routerApiKey: routerApiKey.value,
      token: token.value,
      username: username.value,
      userId: userId.value,
      nickname: nickname.value,
      tokenExpiresAt: tokenExpiresAt.value,
    })
  }

  function setLoginResult(
    accessToken: string,
    rUrl: string,
    apiKey: string,
    uName: string,
    uId: number,
    uNickname: string,
    expiresIn?: number,
  ) {
    token.value = accessToken
    routerUrl.value = rUrl
    routerApiKey.value = apiKey
    username.value = uName
    userId.value = uId
    nickname.value = uNickname

    if (expiresIn) {
      tokenExpiresAt.value = Date.now() + expiresIn * 1000
    }

    save()
    startTokenRefreshTimer()
  }

  function setServerUrl(url: string) {
    adminUrl.value = url
    save()
  }

  function clearAuth() {
    token.value = ''
    routerApiKey.value = ''
    username.value = ''
    userId.value = null
    nickname.value = ''
    tokenExpiresAt.value = 0
    stopTokenRefreshTimer()
    save()
  }

  function startTokenRefreshTimer() {
    stopTokenRefreshTimer()

    if (!tokenExpiresAt.value) return

    const timeUntilExpiry = tokenExpiresAt.value - Date.now()
    const warningTime = Math.max(timeUntilExpiry - TOKEN_REFRESH_THRESHOLD, 0)

    if (warningTime > 0) {
      refreshTimer = setTimeout(() => {
        console.warn('[Auth] Token expiring soon')
        uni.showToast({
          title: i18n.global.t('common.sessionExpiring'),
          icon: 'none',
          duration: 3000,
        })
        setTimeout(() => {
          clearAuth()
        }, 2000)
      }, warningTime)
    } else {
      clearAuth()
    }
  }

  function stopTokenRefreshTimer() {
    if (refreshTimer) {
      clearTimeout(refreshTimer)
      refreshTimer = null
    }
  }

  function isConfigured(): boolean {
    return !!adminUrl.value && !!token.value
  }

  function isServerConfigured(): boolean {
    return !!adminUrl.value
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
    routerApiKey,
    token,
    username,
    userId,
    nickname,
    tokenExpiresAt,
    isLoggedIn,
    isTokenExpiringSoon,
    loadFromStorage,
    save,
    setLoginResult,
    setServerUrl,
    clearAuth,
    startTokenRefreshTimer,
    stopTokenRefreshTimer,
    isConfigured,
    isServerConfigured,
    getRouterBaseUrl,
    getAdminBaseUrl,
  }
})
