// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取智能体定时任务列表 GET /api/admin/agent-tasks/page */
export async function getAgentTaskPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    name?: string;
    agentId?: number;
    taskStatus?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/api/admin/agent-tasks/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取智能体定时任务详情 GET /api/admin/agent-tasks/${id} */
export async function getAgentTaskById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/agent-tasks/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建智能体定时任务 POST /api/admin/agent-tasks */
export async function createAgentTask(data: API.AgentTaskCreateRequest, options?: { [key: string]: any }) {
  return request('/api/admin/agent-tasks', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新智能体定时任务 PUT /api/admin/agent-tasks/${id} */
export async function updateAgentTask(
  id: number,
  data: API.AgentTaskUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/agent-tasks/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 删除智能体定时任务 DELETE /api/admin/agent-tasks/${id} */
export async function deleteAgentTask(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/agent-tasks/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/** 启动智能体定时任务 POST /api/admin/agent-tasks/${id}/start */
export async function startAgentTask(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/agent-tasks/${id}/start`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 暂停智能体定时任务 POST /api/admin/agent-tasks/${id}/pause */
export async function pauseAgentTask(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/agent-tasks/${id}/pause`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 切换智能体定时任务状态 POST /api/admin/agent-tasks/toggle/${id}?status=0|1 */
export async function toggleAgentTaskStatus(id: number, status: number, options?: { [key: string]: any }) {
  return request(`/api/admin/agent-tasks/toggle/${id}`, {
    method: 'POST',
    params: { status },
    ...(options || {}),
  });
}

/** 手动触发一次任务执行 POST /api/admin/agent-tasks/${id}/trigger */
export async function triggerAgentTask(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/agent-tasks/${id}/trigger`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 停止正在运行的任务执行 POST /api/admin/agent-tasks/logs/${logId}/stop */
export async function stopAgentTask(logId: number, options?: { [key: string]: any }) {
  return request(`/api/admin/agent-tasks/logs/${logId}/stop`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 获取智能体定时任务执行日志 GET /api/admin/agent-tasks/${id}/logs */
export async function getAgentTaskLogs(
  id: number,
  params: {
    pageNum?: number;
    pageSize?: number;
    taskName?: string;
    status?: number;
    startTimeFrom?: string;
    startTimeTo?: string;
    keyword?: string;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/admin/agent-tasks/${id}/logs`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取可用Agent列表 GET /api/admin/agent-tasks/agents */
export async function getAvailableAgents(options?: { [key: string]: any }) {
  return request('/api/admin/agent-tasks/agents', {
    method: 'GET',
    ...(options || {}),
  });
}
