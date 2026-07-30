// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取 CLI 列表 GET /api/admin/clis/page */
export async function getCliPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    name?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/api/admin/clis/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取 CLI 详情 GET /api/admin/clis/${id} */
export async function getCliById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/clis/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建 CLI POST /api/admin/clis */
export async function createCli(data: API.CliCreateRequest, options?: { [key: string]: any }) {
  return request('/api/admin/clis', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新 CLI PUT /api/admin/clis/update/${id} */
export async function updateCli(
  id: number,
  data: API.CliUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/clis/update/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 切换 CLI 状态 PUT /api/admin/clis/toggle/${id} */
export async function toggleCliStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/clis/toggle/${id}`, {
    method: 'PUT',
    params: {
      status,
    },
    ...(options || {}),
  });
}

/** 删除 CLI DELETE /api/admin/clis/${id} */
export async function deleteCli(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/clis/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/** 获取绑定该 CLI 的 agent 列表 GET /api/admin/clis/${id}/related-agents */
export async function getCliRelatedAgents(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/clis/${id}/related-agents`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 获取受该 CLI 影响的会话列表 GET /api/admin/clis/${id}/related-sessions */
export async function getCliRelatedSessions(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/clis/${id}/related-sessions`, {
    method: 'GET',
    ...(options || {}),
  });
}
