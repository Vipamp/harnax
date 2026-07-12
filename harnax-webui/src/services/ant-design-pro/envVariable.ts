// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 分页查询环境变量列表 GET /api/admin/env-variables/page */
export async function getEnvVariablePage(
  params: {
    current?: number;
    size?: number;
    keyword?: string;
  },
  options?: { [key: string]: any },
) {
  return request('/api/admin/env-variables/page', {
    method: 'GET',
    params: { pageNum: params.current, pageSize: params.size, keyword: params.keyword },
    ...(options || {}),
  });
}

/** 获取环境变量详情 GET /api/admin/env-variables/${id} */
export async function getEnvVariableById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/env-variables/${id}`, { method: 'GET', ...(options || {}) });
}

/** 创建环境变量 POST /api/admin/env-variables */
export async function createEnvVariable(
  data: any,
  options?: { [key: string]: any },
) {
  return request('/api/admin/env-variables', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data,
    ...(options || {}),
  });
}

/** 更新环境变量 PUT /api/admin/env-variables/update/${id} */
export async function updateEnvVariable(
  id: number,
  data: any,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/env-variables/update/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    data,
    ...(options || {}),
  });
}

/** 删除环境变量 DELETE /api/admin/env-variables/${id} */
export async function deleteEnvVariable(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/env-variables/${id}`, { method: 'DELETE', ...(options || {}) });
}

/** 切换环境变量启停状态 PUT /api/admin/env-variables/${id}/toggle */
export async function toggleEnvVariable(id: number, enabled: number, options?: { [key: string]: any }) {
  return request(`/api/admin/env-variables/${id}/toggle`, {
    method: 'PUT',
    params: { enabled },
    ...(options || {}),
  });
}

/** 获取环境变量列表（用于智能体配置下拉） GET /api/admin/env-variables/list */
export async function getEnvVariableList(options?: { [key: string]: any }) {
  return request('/api/admin/env-variables/list', {
    method: 'GET',
    ...(options || {}),
  });
}
