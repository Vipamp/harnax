// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 用户登录 POST /admin/auth/login */
export async function login(body: API.LoginParams, options?: { [key: string]: any }) {
  return request('/admin/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 获取当前用户信息 GET /admin/auth/currentUser */
export async function currentUser(options?: { [key: string]: any }) {
  return request('/admin/auth/currentUser', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 退出登录 POST /admin/auth/logout */
export async function logout(options?: { [key: string]: any }) {
  return request('/admin/auth/logout', {
    method: 'POST',
    ...(options || {}),
  });
}

/** 发送验证码 POST /admin/auth/captcha */
export async function getCaptcha(
  params: {
    phone?: string;
  },
  options?: { [key: string]: any },
) {
  return request('/admin/auth/captcha', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
