// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 分页获取 Channel 列表
 */
export async function getChannelPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    keyword?: string;
    type?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/channels/page`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 获取 Channel 详情
 */
export async function getChannelById(id: number, options?: { [key: string]: any }) {
  return request(`/api/channels/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 创建 Channel
 */
export async function createChannel(
  data: API.ChannelCreateRequest,
  options?: { [key: string]: any },
) {
  return request('/api/channels', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
    ...(options || {}),
  });
}

/**
 * 更新 Channel
 */
export async function updateChannel(
  id: number,
  data: API.ChannelUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/channels/update/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
    ...(options || {}),
  });
}

/**
 * 切换 Channel 状态
 */
export async function toggleChannelStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/channels/toggle/${id}`, {
    method: 'PUT',
    params: { status },
    ...(options || {}),
  });
}

/**
 * 删除 Channel
 */
export async function deleteChannel(id: number, options?: { [key: string]: any }) {
  return request(`/api/channels/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/**
 * 获取智能体列表（用于下拉选择）
 */
export async function getAgentList(
  params?: {
    pageNum?: number;
    pageSize?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/agents/page`, {
    method: 'GET',
    params: {
      pageNum: 1,
      pageSize: 100,
      ...params,
    },
    ...(options || {}),
  });
}
