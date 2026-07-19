// services/request.ts
// 网络请求封装（基于 wx.request）

import { getAccessToken, getBaseUrl, getRouterApiKey, getRouterUrl, logout } from '../utils/auth';

export interface RequestConfig {
  url: string;
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  data?: any;
  params?: Record<string, any>;
  header?: Record<string, string>;
  /** 跳过 JWT，改用 Router X-Api-Key */
  useRouterAuth?: boolean;
  /** 不弹出错误提示 */
  silent?: boolean;
  /** 请求超时（ms） */
  timeout?: number;
}

/** 拼接 query string */
function buildQuery(params?: Record<string, any>): string {
  if (!params) return '';
  const pairs: string[] = [];
  Object.keys(params).forEach((key) => {
    const val = params[key];
    if (val !== undefined && val !== null && val !== '') {
      pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(val)}`);
    }
  });
  return pairs.length ? '?' + pairs.join('&') : '';
}

/** 核心请求方法，返回后端 data 字段 */
export function request<T = any>(config: RequestConfig): Promise<T> {
  const baseUrl = config.useRouterAuth ? getRouterUrl() : getBaseUrl();
  const fullUrl = `${baseUrl}${config.url}${buildQuery(config.params)}`;

  const header: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(config.header || {}),
  };

  if (config.useRouterAuth) {
    header['X-Api-Key'] = getRouterApiKey();
  } else {
    const token = getAccessToken();
    if (token) header['Authorization'] = `Bearer ${token}`;
  }

  return new Promise<T>((resolve, reject) => {
    wx.request({
      url: fullUrl,
      method: config.method || 'GET',
      data: config.data,
      header,
      timeout: config.timeout || 30000,
      success(res) {
        const status = res.statusCode;
        if (status === 401) {
          if (config.useRouterAuth) {
            // Router API 401: extract real error from response body
            const body = res.data as any;
            const detail = (body && (body.message || body.msg)) || 'Router 认证失败';
            console.error('[request] Router 401:', detail, 'url:', fullUrl);
            reject(new Error(detail));
          } else {
            logout(true);
            reject(new Error('登录已过期'));
          }
          return;
        }
        const body = res.data as API.Result<T>;
        if (status >= 200 && status < 300 && body && body.code === 200) {
          resolve(body.data as T);
        } else {
          const msg = (body && body.message) || `请求失败(${status})`;
          if (!config.silent) {
            wx.showToast({ title: msg, icon: 'none' });
          }
          reject(new Error(msg));
        }
      },
      fail(err) {
        if (!config.silent) {
          wx.showToast({ title: '网络错误，请检查网络', icon: 'none' });
        }
        reject(err);
      },
    });
  });
}

/** 返回完整 Result（含 code/message），供需要完整响应的场景 */
export function requestRaw<T = any>(config: RequestConfig): Promise<API.Result<T>> {
  const baseUrl = config.useRouterAuth ? getRouterUrl() : getBaseUrl();
  const fullUrl = `${baseUrl}${config.url}${buildQuery(config.params)}`;
  const header: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(config.header || {}),
  };
  if (config.useRouterAuth) {
    header['X-Api-Key'] = getRouterApiKey();
  } else {
    const token = getAccessToken();
    if (token) header['Authorization'] = `Bearer ${token}`;
  }
  return new Promise((resolve, reject) => {
    wx.request({
      url: fullUrl,
      method: config.method || 'GET',
      data: config.data,
      header,
      timeout: config.timeout || 30000,
      success(res) {
        if (res.statusCode === 401) {
          if (config.useRouterAuth) {
            const body = res.data as any;
            const detail = (body && (body.message || body.msg)) || 'Router 认证失败';
            reject(new Error(detail));
          } else {
            logout(true);
            reject(new Error('登录已过期'));
          }
          return;
        }
        resolve(res.data as API.Result<T>);
      },
      fail(err) {
        if (!config.silent) {
          wx.showToast({ title: '网络错误', icon: 'none' });
        }
        reject(err);
      },
    });
  });
}
