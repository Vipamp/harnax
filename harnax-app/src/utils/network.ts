import { ref, onMounted, onUnmounted } from 'vue'

export type NetworkType = 'wifi' | '2g' | '3g' | '4g' | '5g' | 'ethernet' | 'none' | 'unknown'

const isConnected = ref(true)
const networkType = ref<NetworkType>('unknown')

let initialized = false
let listenerCount = 0

function initListener() {
  if (initialized) return
  initialized = true

  // Get initial network status
  uni.getNetworkType({
    success: (res) => {
      networkType.value = res.networkType as NetworkType
      isConnected.value = res.networkType !== 'none'
    },
  })

  // Listen for network changes
  uni.onNetworkStatusChange((res) => {
    isConnected.value = res.isConnected
    networkType.value = res.networkType as NetworkType
  })
}

export function useNetwork() {
  onMounted(() => {
    listenerCount++
    initListener()
  })

  onUnmounted(() => {
    listenerCount--
  })

  return {
    isConnected,
    networkType,
  }
}

/**
 * Trigger a short vibration (for AI reply feedback).
 */
export function vibrateShort(): void {
  // #ifdef APP-PLUS
  try {
    uni.vibrateShort({})
  } catch {
    // ignore on unsupported platforms
  }
  // #endif
}

/**
 * Trigger a long vibration (for tool confirmation).
 */
export function vibrateLong(): void {
  // #ifdef APP-PLUS
  try {
    uni.vibrateLong({})
  } catch {
    // ignore on unsupported platforms
  }
  // #endif
}
