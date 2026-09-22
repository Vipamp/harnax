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
