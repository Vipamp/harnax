// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取可用工具列表 GET /api/admin/tools/available */
export async function getAvailableTools(options?: { [key: string]: any }) {
  return request('/api/admin/tools/available', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 获取内置工具列表 GET /api/admin/tools/builtin */
export async function getBuiltinTools(options?: { [key: string]: any }) {
  return request('/api/admin/tools/builtin', { method: 'GET', ...(options || {}) });
}
