// services/auth.ts
import { request, requestRaw } from './request';

/** 登录（password 需已 SHA-256 哈希，使用移动端专用接口跳过验证码） */
export function login(data: API.LoginParams) {
  return requestRaw<any>({
    url: '/api/admin/mp/auth/login',
    method: 'POST',
    data,
    silent: true,
  });
}

/** 退出登录（移动端专用接口） */
export function logoutApi() {
  return request<void>({
    url: '/api/admin/mp/auth/logout',
    method: 'POST',
    silent: true,
  });
}
