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

/** 退出登录 POST /admin/auth/logout */
export async function logout(options?: { [key: string]: any }) {
 return request('/admin/auth/logout', {
  method: 'POST',
    ...(options || {}),
  });
}

/** 获取验证码 GET /admin/auth/captcha */
export async function getCaptcha(options?: { [key: string]: any }) {
 return request('/admin/auth/captcha', {
 method: 'GET',
   ...(options || {}),
  });
}
