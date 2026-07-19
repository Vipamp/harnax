// services/agent.ts
import { request } from './request';

/** 分页获取 Agent 列表 */
export function getAgentPage(params: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  status?: number;
}) {
  return request<API.PageResult<API.AgentItem>>({
    url: '/api/admin/agents/page',
    params,
  });
}

/** Agent 详情 */
export function getAgentById(id: number) {
  return request<API.AgentItem>({ url: `/api/admin/agents/${id}` });
}

/** 创建 Agent */
export function createAgent(data: API.AgentCreateRequest) {
  return request<API.AgentItem>({
    url: '/api/admin/agents',
    method: 'POST',
    data,
  });
}

/** 更新 Agent */
export function updateAgent(id: number, data: API.AgentUpdateRequest) {
  return request<API.AgentItem>({
    url: `/api/admin/agents/update/${id}`,
    method: 'PUT',
    data,
  });
}

/** 切换 Agent 状态 */
export function toggleAgentStatus(id: number, status: number) {
  return request<void>({
    url: `/api/admin/agents/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

/** 删除 Agent */
export function deleteAgent(id: number) {
  return request<void>({
    url: `/api/admin/agents/${id}`,
    method: 'DELETE',
  });
}

/** 模型列表（下拉） */
export function getModelOptions() {
  return request<API.PageResult<API.ModelItem>>({
    url: '/api/admin/models/page',
    params: { pageNum: 1, pageSize: 100, status: 1 },
  });
}

/** MCP 列表（下拉） */
export function getMcpOptions() {
  return request<API.PageResult<API.McpItem>>({
    url: '/api/admin/mcp/page',
    params: { pageNum: 1, pageSize: 100, status: 1 },
  });
}

/** 可用工具列表（供 Agent 配置下拉） */
export function getToolOptions() {
  return request<any[]>({
    url: '/api/admin/tools/available',
  });
}

/** 技能列表（供 Agent 配置下拉） */
export function getSkillOptions() {
  return request<API.PageResult<API.SkillItem>>({
    url: '/api/admin/skills/page',
    params: { pageNum: 1, pageSize: 200, status: 1 },
  });
}

/** 技能仓库列表（下拉） */
export function getSkillRepoOptions() {
  return request<API.PageResult<API.SkillRepositoryItem>>({
    url: '/api/admin/skill-repositories/page',
    params: { pageNum: 1, pageSize: 100, status: 1 },
  });
}

/** 按仓库获取技能列表 */
export function getSkillsByRepo(repositoryId: number) {
  return request<API.PageResult<API.SkillItem>>({
    url: '/api/admin/skills/page',
    params: { repositoryId, pageNum: 1, pageSize: 100, status: 1 },
  });
}

/** 环境变量下拉选项（供工具/MCP 环境参数绑定） */
export function getEnvVarOptions() {
  return request<API.EnvVarOption[]>({
    url: '/api/admin/env-variables/list',
  });
}
