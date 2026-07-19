// services/session.ts
import { request } from './request';

/** 分页获取会话列表 */
export function getSessionPage(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: number;
}) {
  return request<API.PageResult<API.SessionItem>>({
    url: '/api/admin/sessions/page',
    params,
  });
}

/** 会话详情 */
export function getSessionById(id: number) {
  return request<API.SessionItem>({ url: `/api/admin/sessions/${id}` });
}

/** 创建会话（后端返回 Void） */
export function createSession(data: API.SessionCreateRequest) {
  return request<void>({
    url: '/api/admin/sessions',
    method: 'POST',
    data,
  });
}

/** 切换会话状态 */
export function toggleSessionStatus(id: number, status: number) {
  return request<void>({
    url: `/api/admin/sessions/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

/** 删除会话 */
export function deleteSession(id: number) {
  return request<void>({
    url: `/api/admin/sessions/${id}`,
    method: 'DELETE',
  });
}

/** Agent 下拉（创建会话用） */
export function getAgentOptions() {
  return request<API.PageResult<API.AgentItem>>({
    url: '/api/admin/agents/page',
    params: { pageNum: 1, pageSize: 100, status: 1 },
  });
}
