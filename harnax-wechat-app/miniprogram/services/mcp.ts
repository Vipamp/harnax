// services/mcp.ts
import { request } from './request';

export function getMcpPage(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: number;
  type?: string;
}) {
  return request<API.PageResult<API.McpItem>>({
    url: '/api/admin/mcp/page',
    params,
  });
}

export function getMcpById(id: number) {
  return request<API.McpItem>({ url: `/api/admin/mcp/${id}` });
}

export function createMcp(data: API.McpCreateRequest) {
  return request<API.McpItem>({ url: '/api/admin/mcp', method: 'POST', data });
}

export function updateMcp(id: number, data: API.McpUpdateRequest) {
  return request<API.McpItem>({
    url: `/api/admin/mcp/update/${id}`,
    method: 'PUT',
    data,
  });
}

export function toggleMcp(id: number, status: number) {
  return request<void>({
    url: `/api/admin/mcp/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

export function deleteMcp(id: number) {
  return request<void>({ url: `/api/admin/mcp/${id}`, method: 'DELETE' });
}

export function testMcp(id: number) {
  return request<any>({ url: `/api/admin/mcp/${id}/connectivity-test`, method: 'POST' });
}

export function getMcpTools(id: number) {
  return request<any>({ url: `/api/admin/mcp/${id}/list_tools` });
}
