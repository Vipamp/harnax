// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取技能列表 GET /admin/skills/page */
export async function getSkillPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    name?: string;
    repositoryId?: number;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/admin/skills/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取技能详情 GET /admin/skills/${id} */
export async function getSkillById(id: number, options?: { [key: string]: any }) {
  return request(`/admin/skills/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建技能 POST /admin/skills */
export async function createSkill(data: API.SkillCreateRequest, options?: { [key: string]: any }) {
  return request('/admin/skills', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新技能 PUT /admin/skills/update/${skillId} */
export async function updateSkill(
  skillId: number,
  data: API.SkillUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/admin/skills/update/${skillId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 切换技能状态 PUT /admin/skills/toggle/${skillId} */
export async function toggleSkillStatus(
  skillId: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/admin/skills/toggle/${skillId}`, {
    method: 'PUT',
    params: {
      status,
    },
    ...(options || {}),
  });
}

/** 删除技能 DELETE /admin/skills/${skillId} */
export async function deleteSkill(skillId: number, options?: { [key: string]: any }) {
  return request(`/admin/skills/${skillId}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/** 批量保存技能（同步用）POST /admin/skills/batch */
export async function batchSaveSkills(
  repositoryId: number,
  data: API.SkillSyncItem[],
  options?: { [key: string]: any },
) {
  return request('/admin/skills/batch', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    params: { repositoryId },
    data: data,
    ...(options || {}),
  });
}
