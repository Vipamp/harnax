// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 分页查询工具列表 GET /api/admin/tools/page */
export async function getAgentToolPage(
  params: {
    current?: number;
    size?: number;
    keyword?: string;
    status?: number;
    type?: string;
  },
  options?: { [key: string]: any },
) {
  return request('/api/admin/tools/page', {
    method: 'GET',
    params: { pageNum: params.current, pageSize: params.size, keyword: params.keyword, status: params.status, type: params.type },
    ...(options || {}),
  });
}

/** 获取工具详情 GET /api/admin/tools/${id} */
export async function getAgentToolById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/tools/${id}`, { method: 'GET', ...(options || {}) });
}

/** 更新工具 PUT /api/admin/tools/update/${id} */
export async function updateAgentTool(
  id: number,
  data: any,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/tools/update/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    data,
    ...(options || {}),
  });
}

/** 切换工具状态 PUT /api/admin/tools/toggle/${id} */
export async function toggleAgentToolStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/tools/toggle/${id}`, {
    method: 'PUT',
    params: { status },
    ...(options || {}),
  });
}

/** 删除工具 DELETE /api/admin/tools/${id} */
export async function deleteAgentTool(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/tools/${id}`, { method: 'DELETE', ...(options || {}) });
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
