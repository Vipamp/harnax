// utils/auth.ts
// 登录态与 Token 管理

import { STORAGE_KEYS } from './constants';
import { getStorage, setStorage, removeStorage } from './storage';

/** 保存登录信息 */
export function saveAuth(data: {
  accessToken: string;
  tokenType?: string;
  expiresIn?: number;
  routerApiKey?: string;
  routerUrl?: string;
  userInfo?: any;
}): void {
  setStorage(STORAGE_KEYS.ACCESS_TOKEN, data.accessToken);
  setStorage(STORAGE_KEYS.TOKEN_INFO, {
    accessToken: data.accessToken,
    tokenType: data.tokenType || 'Bearer',
    expiresIn: data.expiresIn,
    routerApiKey: data.routerApiKey || '',
    routerUrl: data.routerUrl || '',
  });
  if (data.userInfo) {
    setStorage(STORAGE_KEYS.USER_INFO, data.userInfo);
  }
}

/** 获取 JWT Token */
export function getAccessToken(): string {
  return getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN, '') || '';
}

/** 获取 Router API Key */
export function getRouterApiKey(): string {
  const tokenInfo = getStorage<API.TokenInfo>(STORAGE_KEYS.TOKEN_INFO);
  return tokenInfo?.routerApiKey || '';
}

/** 获取 Router 服务地址
 *  Admin 和 Router 通常共享同一个 nginx 入口（base URL），
 *  nginx 会将 /api/router/... 代理到 Router 服务。
 *  仅当用户手动填写了可达的 Router 地址时才使用独立地址。
 */
export function getRouterUrl(): string {
  const tokenInfo = getStorage<API.TokenInfo>(STORAGE_KEYS.TOKEN_INFO);
  const stored = (tokenInfo?.routerUrl || '').replace(/\/+$/, '');
  const base = getBaseUrl();

  if (!stored) return base;

  // 内部地址（localhost / 127.x）在真机上不可达，使用 base URL（nginx 代理）
  const isInternal = (url: string) =>
    /:\/\/(localhost|127\.\d+\.\d+\.\d+)/.test(url);
  if (isInternal(stored)) {
    return base;
  }

  return stored;
}

/** 获取当前用户信息 */
export function getUserInfo(): IUserInfo | undefined {
  return getStorage<IUserInfo>(STORAGE_KEYS.USER_INFO);
}

/** 获取服务器地址 */
export function getBaseUrl(): string {
  return getStorage<string>(STORAGE_KEYS.BASE_URL, '') || '';
}

/** 是否已登录 */
export function isLoggedIn(): boolean {
  return !!getAccessToken();
}

/** 清除登录态并跳转登录页 */
let _isRedirecting = false;
export function logout(redirect = true): void {
  removeStorage(STORAGE_KEYS.ACCESS_TOKEN);
  removeStorage(STORAGE_KEYS.TOKEN_INFO);
  removeStorage(STORAGE_KEYS.USER_INFO);
  if (redirect && !_isRedirecting) {
    _isRedirecting = true;
    wx.reLaunch({
      url: '/pages/login/index',
      complete() { _isRedirecting = false; },
    });
  }
}
