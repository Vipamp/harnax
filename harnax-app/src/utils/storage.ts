const STORAGE_PREFIX = 'harnax_chat_'
const MESSAGE_KEY_PREFIX = 'messages_'
const MAX_CACHE_SIZE = 5 * 1024 * 1024 // 5MB

export function getStorage<T>(key: string, defaultValue?: T): T | undefined {
  return storage.get<T>(key, defaultValue)
}

export function setStorage(key: string, value: unknown): void {
  storage.set(key, value)
}

export function removeStorage(key: string): void {
  storage.remove(key)
}

export const storage = {
  get<T>(key: string, defaultValue?: T): T | undefined {
    try {
      const value = uni.getStorageSync(STORAGE_PREFIX + key)
      if (value === '' || value === undefined || value === null) {
        return defaultValue
      }
      if (typeof value === 'string') {
        try {
          return JSON.parse(value) as T
        } catch {
          return value as unknown as T
        }
      }
      return value as T
    } catch {
      return defaultValue
    }
  },

  set(key: string, value: unknown): void {
    try {
      const serialized = typeof value === 'string' ? value : JSON.stringify(value)
      uni.setStorageSync(STORAGE_PREFIX + key, serialized)
    } catch (e) {
      console.error(`[Storage] Failed to set key "${key}":`, e)
    }
  },

  remove(key: string): void {
    try {
      uni.removeStorageSync(STORAGE_PREFIX + key)
    } catch (e) {
      console.error(`[Storage] Failed to remove key "${key}":`, e)
    }
  },
}

export function getEstimateStorageSize(): number {
  try {
    const info = uni.getStorageInfoSync()
    return info.currentSize * 1024 // convert KB to bytes
  } catch {
    return 0
  }
}

export function getMessageCacheKeys(): string[] {
  try {
    const info = uni.getStorageInfoSync()
    return info.keys
      .filter((k) => k.startsWith(STORAGE_PREFIX + MESSAGE_KEY_PREFIX))
      .map((k) => k.slice(STORAGE_PREFIX.length))
  } catch {
    return []
  }
}

export function evictOldMessageCaches(keepSessionIds: Set<string>): void {
  const cacheKeys = getMessageCacheKeys()
  for (const key of cacheKeys) {
    const sessionId = key.slice(MESSAGE_KEY_PREFIX.length)
    if (!keepSessionIds.has(sessionId)) {
      storage.remove(key)
    }
  }
}

export function ensureCacheSpace(): void {
  const used = getEstimateStorageSize()
  if (used < MAX_CACHE_SIZE * 0.8) return

  const cacheKeys = getMessageCacheKeys()
  const entries: { key: string; size: number }[] = []

  for (const key of cacheKeys) {
    try {
      const raw = uni.getStorageSync(STORAGE_PREFIX + key)
      const size = typeof raw === 'string' ? raw.length : 0
      entries.push({ key, size })
    } catch {
      // ignore
    }
  }

  entries.sort((a, b) => b.size - a.size)

  let freed = 0
  const target = MAX_CACHE_SIZE * 0.3
  for (const entry of entries) {
    if (freed >= target) break
    storage.remove(entry.key)
    freed += entry.size
    console.info(`[Storage] Evicted cache: ${entry.key} (${entry.size} bytes)`)
  }
}
