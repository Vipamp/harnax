// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 我的智能体记忆：列出当前登录用户拥有长期记忆的智能体 GET /api/admin/memory
 */
export async function getMemoryList(options?: { [key: string]: any }) {
  return request<API.Result<API.MemoryAgentItem[]>>('/api/admin/memory', {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 单个智能体的记忆详情 GET /api/admin/memory/{agentId}
 *
 * agentId 是智能体的名字，不是数字 id，可能带空格等字符，所以进 URL 前必须编码。
 */
export async function getMemoryDetail(agentId: string, options?: { [key: string]: any }) {
  return request<API.Result<API.MemoryDetail>>(`/api/admin/memory/${encodeURIComponent(agentId)}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 删除当前用户在该智能体下的长期记忆 DELETE /api/admin/memory/{agentId}
 */
export async function deleteMemory(agentId: string, options?: { [key: string]: any }) {
  return request<API.Result<API.MemoryDeleteResponse>>(`/api/admin/memory/${encodeURIComponent(agentId)}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}
