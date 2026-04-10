// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 分页获取会话列表
 */
export async function getSessionPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    keyword?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/admin/sessions/page`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 获取会话详情
 */
export async function getSessionById(id: number, options?: { [key: string]: any }) {
  return request(`/admin/sessions/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 检查会话名称是否存在
 */
export async function checkSessionTitle(
  title: string,
  options?: { [key: string]: any },
) {
  return request('/admin/sessions/check-title', {
    method: 'GET',
    params: { title },
    ...(options || {}),
  });
}

/**
 * 创建会话
 */
export async function createSession(
  data: API.SessionCreateRequest,
  options?: { [key: string]: any },
) {
  return request('/admin/sessions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
    ...(options || {}),
  });
}

/**
 * 切换会话状态
 */
export async function toggleSessionStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/admin/sessions/toggle/${id}`, {
    method: 'PUT',
    params: { status },
    ...(options || {}),
  });
}

/**
 * 删除会话
 */
export async function deleteSession(id: number, options?: { [key: string]: any }) {
  return request(`/admin/sessions/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}
