// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取技能仓库列表 GET /api/skill-repositories/page */
export async function getSkillRepositoryPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    name?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/api/skill-repositories/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取所有启用的仓库列表 GET /api/skill-repositories/active */
export async function getActiveRepositories(options?: { [key: string]: any }) {
  return request('/api/skill-repositories/active', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 获取技能仓库详情 GET /api/skill-repositories/${id} */
export async function getSkillRepositoryById(id: number, options?: { [key: string]: any }) {
  return request(`/api/skill-repositories/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建技能仓库 POST /api/skill-repositories */
export async function createSkillRepository(data: API.SkillRepositoryCreateRequest, options?: { [key: string]: any }) {
  return request('/api/skill-repositories', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新技能仓库 PUT /api/skill-repositories/update/${repositoryId} */
export async function updateSkillRepository(
  repositoryId: number,
  data: API.SkillRepositoryUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/skill-repositories/update/${repositoryId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 切换技能仓库状态 PUT /api/skill-repositories/toggle/${repositoryId} */
export async function toggleSkillRepositoryStatus(
  repositoryId: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/skill-repositories/toggle/${repositoryId}`, {
    method: 'PUT',
    params: {
      status,
    },
    ...(options || {}),
  });
}

/** 删除技能仓库 DELETE /api/skill-repositories/${repositoryId} */
export async function deleteSkillRepository(repositoryId: number, options?: { [key: string]: any }) {
  return request(`/api/skill-repositories/${repositoryId}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/** 获取远程技能列表 GET /api/skill-repositories/fetch/${repositoryId} */
export async function fetchRemoteSkills(repositoryId: number, options?: { [key: string]: any }) {
  return request(`/api/skill-repositories/fetch/${repositoryId}`, {
    method: 'GET',
    ...(options || {}),
  });
}
