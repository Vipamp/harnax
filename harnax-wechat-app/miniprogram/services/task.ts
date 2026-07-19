// services/task.ts
import { request } from './request';

/** 分页获取定时任务列表 */
export function getAgentTaskPage(params: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  agentId?: number;
  taskStatus?: number;
}) {
  return request<API.PageResult<API.AgentTaskItem>>({
    url: '/api/admin/agent-tasks/page',
    params,
  });
}

/** 任务详情 */
export function getAgentTaskById(id: number) {
  return request<API.AgentTaskItem>({ url: `/api/admin/agent-tasks/${id}` });
}

/** 创建任务 */
export function createAgentTask(data: API.AgentTaskCreateRequest) {
  return request<API.AgentTaskItem>({
    url: '/api/admin/agent-tasks',
    method: 'POST',
    data,
  });
}

/** 更新任务 */
export function updateAgentTask(id: number, data: API.AgentTaskUpdateRequest) {
  return request<API.AgentTaskItem>({
    url: `/api/admin/agent-tasks/${id}`,
    method: 'PUT',
    data,
  });
}

/** 删除任务 */
export function deleteAgentTask(id: number) {
  return request<void>({
    url: `/api/admin/agent-tasks/${id}`,
    method: 'DELETE',
  });
}

/** 启停任务 */
export function toggleAgentTaskStatus(id: number, status: number) {
  return request<void>({
    url: `/api/admin/agent-tasks/toggle/${id}`,
    method: 'POST',
    params: { status },
  });
}

/** 手动触发任务 */
export function triggerAgentTask(id: number) {
  return request<void>({
    url: `/api/admin/agent-tasks/${id}/trigger`,
    method: 'POST',
  });
}

/** 任务执行日志 */
export function getAgentTaskLogs(
  id: number,
  params: { pageNum?: number; pageSize?: number; status?: number; keyword?: string },
) {
  return request<API.PageResult<API.AgentTaskLogItem>>({
    url: `/api/admin/agent-tasks/${id}/logs`,
    params,
  });
}

/** 可用 Agent 列表 */
export function getAvailableAgents() {
  return request<API.AgentOption[]>({ url: '/api/admin/agent-tasks/agents' });
}
