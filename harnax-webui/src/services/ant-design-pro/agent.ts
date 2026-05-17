// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 分页获取智能体列表
 */
export async function getAgentPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    name?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/agents/page`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 获取智能体详情
 */
export async function getAgentById(id: number, options?: { [key: string]: any }) {
  return request(`/api/agents/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 创建智能体
 */
export async function createAgent(
  data: API.AgentCreateRequest,
  options?: { [key: string]: any },
) {
  return request('/api/agents', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
    ...(options || {}),
  });
}

/**
 * 更新智能体
 */
export async function updateAgent(
  id: number,
  data: API.AgentUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/agents/update/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
    ...(options || {}),
  });
}

/**
 * 切换智能体状态
 */
export async function toggleAgentStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/agents/toggle/${id}`, {
    method: 'PUT',
    params: { status },
    ...(options || {}),
  });
}

/**
 * 删除智能体
 */
export async function deleteAgent(id: number, options?: { [key: string]: any }) {
  return request(`/api/agents/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/**
 * 获取 MCP 服务器列表(用于下拉选择)
 */
export async function getMcpServerList(
  params?: {
    pageNum?: number;
    pageSize?: number;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/mcp/page`, {
    method: 'GET',
    params: {
      pageNum: 1,
      pageSize: 100,
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 获取技能仓库列表(用于下拉选择)
 */
export async function getSkillRepositoryList(
  params?: {
    pageNum?: number;
    pageSize?: number;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/skill-repositories/page`, {
    method: 'GET',
    params: {
      pageNum: 1,
      pageSize: 100,
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 根据仓库 ID 获取技能列表
 */
export async function getSkillListByRepository(
  repositoryId: number,
  params?: {
    pageNum?: number;
    pageSize?: number;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/skills/page`, {
    method: 'GET',
    params: {
      repositoryId,
      pageNum: 1,
      pageSize: 100,
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 获取模型列表（用于下拉选择）
 */
export async function getModelList(
  params?: {
    pageNum?: number;
    pageSize?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/models/page`, {
    method: 'GET',
    params: {
      pageNum: 1,
      pageSize: 100,
      ...params,
    },
    ...(options || {}),
  });
}
