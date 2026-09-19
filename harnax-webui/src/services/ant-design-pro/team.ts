// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 分页查询团队列表 GET /api/admin/teams/page */
export async function getTeamPage(
  params: {
    current?: number;
    size?: number;
    name?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/api/admin/teams/page', {
    method: 'GET',
    params: {
      pageNum: params.current ?? 1,
      pageSize: params.size ?? 10,
      name: params.name,
      status: params.status,
    },
    ...(options || {}),
  });
}

/** 获取团队详情 GET /api/admin/teams/${id} */
export async function getTeamById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/teams/${id}`, { method: 'GET', ...(options || {}) });
}

/** 创建团队 POST /api/admin/teams */
export async function createTeam(data: API.TeamCreateRequest, options?: { [key: string]: any }) {
  return request('/api/admin/teams', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data,
    ...(options || {}),
  });
}

/** 更新团队 PUT /api/admin/teams/update/${id} */
export async function updateTeam(
  id: number,
  data: API.TeamUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/teams/update/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    data,
    ...(options || {}),
  });
}

/** 删除团队 DELETE /api/admin/teams/${id} */
export async function deleteTeam(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/teams/${id}`, { method: 'DELETE', ...(options || {}) });
}

/** 切换团队启停状态 PUT /api/admin/teams/toggle/${id} */
export async function toggleTeam(id: number, status: number, options?: { [key: string]: any }) {
  return request(`/api/admin/teams/toggle/${id}`, {
    method: 'PUT',
    params: { status },
    ...(options || {}),
  });
}

/** 获取团队关联的会话列表 GET /api/admin/teams/${id}/related-sessions */
export async function getTeamRelatedSessions(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/teams/${id}/related-sessions`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 团队会话已发布的成员产物 GET /api/admin/team-artifacts */
export async function listTeamArtifacts(sessionId: string, options?: { [key: string]: any }) {
  return request('/api/admin/team-artifacts', {
    method: 'GET',
    params: { sessionId },
    ...(options || {}),
  });
}

/**
 * 下载成员产物（admin 侧代理 MinIO，需要登录态）
 * fileId 与 sessionId 都由后端校验归属，这里只做 URL 编码。
 */
export async function downloadTeamArtifact(fileId: string, sessionId: string): Promise<Blob> {
  const headers: Record<string, string> = {};
  try {
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    if (tokenInfoStr) {
      const tokenInfo = JSON.parse(tokenInfoStr);
      if (tokenInfo.accessToken) {
        headers.Authorization = `Bearer ${tokenInfo.accessToken}`;
      }
    }
  } catch {
    // ignore: 取不到 token 时让后端返回 401
  }

  const response = await fetch(
    `/api/admin/team-artifacts/${encodeURIComponent(fileId)}?sessionId=${encodeURIComponent(sessionId)}`,
    { method: 'GET', headers },
  );
  if (!response.ok) {
    throw new Error(`Download failed: ${response.status} ${response.statusText}`);
  }
  return response.blob();
}
