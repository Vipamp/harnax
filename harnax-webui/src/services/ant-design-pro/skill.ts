// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取技能列表 GET /api/skills/page */
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
  return request('/api/admin/skills/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取技能详情 GET /api/skills/${id} */
export async function getSkillById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/skills/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 技能审核轨迹 GET /api/admin/skills/${skillId}/review-history
 *
 * 后端对草稿与技能序列化同一个 ReviewHistoryItem，所以条目形状沿用草稿的类型。
 * 读不到的技能答 404，而不是空的轨迹 —— 空表是「没人记过」，404 是「这条技能你不该看到」。
 */
export async function getSkillReviewHistory(skillId: number, options?: { [key: string]: any }) {
  return request<API.Result<API.SkillDraftHistoryItem[]>>(
    `/api/admin/skills/${skillId}/review-history`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** 切换技能状态 PUT /api/skills/toggle/${skillId} */
export async function toggleSkillStatus(
  skillId: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/skills/toggle/${skillId}`, {
    method: 'PUT',
    params: {
      status,
    },
    ...(options || {}),
  });
}

/** 读取技能可见性策略 GET /api/admin/skill-visibility/${skillId} */
export async function getSkillVisibility(skillId: number, options?: { [key: string]: any }) {
  return request(`/api/admin/skill-visibility/${skillId}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 设置技能可见性策略 PUT /api/admin/skill-visibility/${skillId} */
export async function updateSkillVisibility(
  skillId: number,
  data: API.SkillVisibilityUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/skill-visibility/${skillId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}
