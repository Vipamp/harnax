// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 分页查询 MCP 服务列表 GET /admin/mcp/page */
export async function getMcpServerPage(
  params: {
    current?: number;
    size?: number;
    keyword?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/admin/mcp/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取 MCP 服务详情 GET /admin/mcp/${id} */
export async function getMcpServerById(id: number, options?: { [key: string]: any }) {
  return request(`/admin/mcp/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建 MCP 服务 POST /admin/mcp */
export async function createMcpServer(
  data: API.McpServerCreateRequest,
  options?: { [key: string]: any },
) {
  return request('/admin/mcp', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新 MCP 服务 PUT /admin/mcp/update/${id} */
export async function updateMcpServer(
  id: number,
  data: API.McpServerUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/admin/mcp/update/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 切换 MCP 服务启用状态 PUT /admin/mcp/toggle/${id} */
export async function toggleMcpServerStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/admin/mcp/toggle/${id}`, {
    method: 'PUT',
    params: {
      status,
    },
    ...(options || {}),
  });
}

/** 删除 MCP 服务 DELETE /admin/mcp/${id} */
export async function deleteMcpServer(id: number, options?: { [key: string]: any }) {
  return request(`/admin/mcp/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/** MCP 服务连通性测试 POST /admin/mcp/${id}/connectivity-test */
export async function connectivityTestMcpServer(id: number, options?: { [key: string]: any }) {
  return request(`/admin/mcp/${id}/connectivity-test`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 获取 MCP 工具列表 GET /admin/mcp/${id}/list_tools */
export async function getMcpTools(id: number, options?: { [key: string]: any }) {
  return request(`/admin/mcp/${id}/list_tools`, {
    method: 'GET',
    ...(options || {}),
  });
}
