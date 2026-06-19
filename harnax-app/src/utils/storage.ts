const STORAGE_PREFIX = 'harnax_chat_'

export function getStorage<T>(key: string, defaultValue?: T): T | undefined {
  return storage.get<T>(key, defaultValue)
}

export function setStorage(key: string, value: unknown): void {
  storage.set(key, value)
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
