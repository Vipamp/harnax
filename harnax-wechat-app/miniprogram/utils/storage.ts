// utils/storage.ts
// 本地存储封装

export function getStorage<T = any>(key: string, defaultValue?: T): T | undefined {
  try {
    const value = wx.getStorageSync(key);
    return value === '' || value === undefined ? defaultValue : value;
  } catch {
    return defaultValue;
  }
}

export function setStorage(key: string, value: any): void {
  try {
    wx.setStorageSync(key, value);
  } catch (e) {
    console.error('[storage] set failed', key, e);
  }
}

export function removeStorage(key: string): void {
  try {
    wx.removeStorageSync(key);
  } catch (e) {
    console.error('[storage] remove failed', key, e);
  }
}

export function clearStorage(): void {
  try {
    wx.clearStorageSync();
  } catch (e) {
    console.error('[storage] clear failed', e);
  }
}
