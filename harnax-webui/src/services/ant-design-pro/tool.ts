// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取工具详情 GET /api/admin/tools/${id} */
export async function getAgentToolById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/tools/${id}`, { method: 'GET', ...(options || {}) });
}

/** 获取可用工具列表 GET /api/admin/tools/available */
export async function getAvailableTools(
  params?: { type?: string },
  options?: { [key: string]: any },
) {
  return request('/api/admin/tools/available', {
    method: 'GET',
    params,
    ...(options || {}),
  });
}

/** 获取内置工具列表 GET /api/admin/tools/builtin */
export async function getBuiltinTools(options?: { [key: string]: any }) {
  return request('/api/admin/tools/builtin', { method: 'GET', ...(options || {}) });
}

/** 获取工具所需环境参数key GET /api/admin/tools/${id}/required-env-params */
export async function getToolRequiredEnvParams(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/tools/${id}/required-env-params`, { method: 'GET', ...(options || {}) });
}
