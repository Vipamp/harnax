// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取技能仓库列表 GET /admin/skill-repositories/list */
export async function getSkillRepositoryPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    name?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/admin/skill-repositories/list', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取所有启用的仓库列表 GET /admin/skill-repositories/active */
export async function getActiveRepositories(options?: { [key: string]: any }) {
  return request('/admin/skill-repositories/active', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 获取技能仓库详情 GET /admin/skill-repositories/${id} */
export async function getSkillRepositoryById(id: number, options?: { [key: string]: any }) {
  return request(`/admin/skill-repositories/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建技能仓库 POST /admin/skill-repositories */
export async function createSkillRepository(data: API.SkillRepositoryCreateRequest, options?: { [key: string]: any }) {
  return request('/admin/skill-repositories', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新技能仓库 PUT /admin/skill-repositories/update/${repositoryId} */
export async function updateSkillRepository(
  repositoryId: number,
  data: API.SkillRepositoryUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/admin/skill-repositories/update/${repositoryId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 切换技能仓库状态 PUT /admin/skill-repositories/toggle/${repositoryId} */
export async function toggleSkillRepositoryStatus(
  repositoryId: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/admin/skill-repositories/toggle/${repositoryId}`, {
    method: 'PUT',
    params: {
      status,
    },
    ...(options || {}),
  });
}

/** 删除技能仓库 DELETE /admin/skill-repositories/${repositoryId} */
export async function deleteSkillRepository(repositoryId: number, options?: { [key: string]: any }) {
  return request(`/admin/skill-repositories/${repositoryId}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/** 同步技能仓库 POST /admin/skill-repositories/sync/${repositoryId} */
export async function syncSkillRepository(repositoryId: number, options?: { [key: string]: any }) {
  return request(`/admin/skill-repositories/sync/${repositoryId}`, {
    method: 'POST',
    ...(options || {}),
  });
}
