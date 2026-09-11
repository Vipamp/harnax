// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 分页查询 MCP 服务列表 GET /api/mcp/page */
export async function getMcpServerPage(
  params: {
    // 名字必须与 McpServerController.pageMcpServer 的 @RequestParam 一致
    pageNum?: number;
    pageSize?: number;
    keyword?: string;
    status?: number;
    type?: string;
  },
  options?: { [key: string]: any },
) {
  return request('/api/admin/mcp/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取 MCP 服务详情 GET /api/mcp/${id} */
export async function getMcpServerById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/mcp/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建 MCP 服务 POST /api/mcp */
export async function createMcpServer(
  data: API.McpServerCreateRequest,
  options?: { [key: string]: any },
) {
  return request('/api/admin/mcp', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新 MCP 服务 PUT /api/mcp/update/${id} */
export async function updateMcpServer(
  id: number,
  data: API.McpServerUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/mcp/update/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 切换 MCP 服务启用状态 PUT /api/mcp/toggle/${id} */
export async function toggleMcpServerStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/mcp/toggle/${id}`, {
    method: 'PUT',
    params: {
      status,
    },
    ...(options || {}),
  });
}

/** 删除 MCP 服务 DELETE /api/mcp/${id} */
export async function deleteMcpServer(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/mcp/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/** MCP 服务连通性测试 POST /api/mcp/${id}/connectivity-test */
export async function connectivityTestMcpServer(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/mcp/${id}/connectivity-test`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 获取 MCP 工具列表 GET /api/mcp/${id}/list_tools */
export async function getMcpTools(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/mcp/${id}/list_tools`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 发现授权服务器 POST /api/admin/mcp/${id}/oauth/discover */
export async function discoverMcpOAuth(
  id: number,
  options?: { [key: string]: any },
): Promise<{ code: number; message?: string; data?: API.McpOAuthDiscoveryResponse }> {
  return request(`/api/admin/mcp/${id}/oauth/discover`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 登记 OAuth 客户端 POST /api/admin/mcp/${id}/oauth/client */
export async function saveMcpOAuthClient(
  id: number,
  data: API.McpOAuthClientRequest,
  options?: { [key: string]: any },
): Promise<{ code: number; message?: string; data?: API.McpOAuthDiscoveryResponse }> {
  return request(`/api/admin/mcp/${id}/oauth/client`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 拿本用户的授权入口 GET /api/admin/mcp/${id}/oauth/authorize-url */
export async function getMcpOAuthAuthorizeUrl(
  id: number,
  scope?: string,
  options?: { [key: string]: any },
): Promise<{ code: number; message?: string; data?: API.McpOAuthAuthorizeResponse }> {
  return request(`/api/admin/mcp/${id}/oauth/authorize-url`, {
    method: 'GET',
    params: scope ? { scope } : undefined,
    ...(options || {}),
  });
}

/** 用授权服务器带回的参数换本用户的凭据 POST /api/admin/mcp/oauth/exchange */
export async function exchangeMcpOAuthCode(
  data: API.McpOAuthExchangeRequest,
  options?: { [key: string]: any },
): Promise<{ code: number; message?: string; data?: API.McpOAuthExchangeResponse }> {
  return request('/api/admin/mcp/oauth/exchange', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 读本用户的授权状态 GET /api/admin/mcp/${id}/oauth/status */
export async function getMcpOAuthStatus(
  id: number,
  options?: { [key: string]: any },
): Promise<{ code: number; message?: string; data?: API.McpOAuthStatusResponse }> {
  return request(`/api/admin/mcp/${id}/oauth/status`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 撤销本用户的授权 POST /api/admin/mcp/${id}/oauth/revoke */
export async function revokeMcpOAuth(
  id: number,
  options?: { [key: string]: any },
): Promise<{ code: number; message?: string; data?: API.McpOAuthRevokeResponse }> {
  return request(`/api/admin/mcp/${id}/oauth/revoke`, {
    method: 'POST',
    ...(options || {}),
  });
}
