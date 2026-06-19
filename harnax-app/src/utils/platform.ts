export type PlatformType = 'app' | 'mp-weixin' | 'h5' | 'harmony'

export function getPlatform(): PlatformType {
  // #ifdef APP-PLUS
  return 'app'
  // #endif
  // #ifdef MP-WEIXIN
  return 'mp-weixin'
  // #endif
  // #ifdef H5
  return 'h5'
  // #endif
  // @ts-ignore - fallback
  return 'h5'
}

export function isApp(): boolean {
  return getPlatform() === 'app'
}

export function isH5(): boolean {
  return getPlatform() === 'h5'
}

export function isMpWeixin(): boolean {
  return getPlatform() === 'mp-weixin'
}

export function generateUUID(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
