// services/skill.ts
// 技能仓库 + 技能
import { request } from './request';

// ---- 仓库 ----
export function getRepoPage(params: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  status?: number;
}) {
  return request<API.PageResult<API.SkillRepositoryItem>>({
    url: '/api/admin/skill-repositories/page',
    params,
  });
}

export function getRepoById(id: number) {
  return request<API.SkillRepositoryItem>({ url: `/api/admin/skill-repositories/${id}` });
}

export function createRepo(data: API.SkillRepositoryCreateRequest) {
  return request<API.SkillRepositoryItem>({
    url: '/api/admin/skill-repositories',
    method: 'POST',
    data,
  });
}

export function updateRepo(id: number, data: API.SkillRepositoryUpdateRequest) {
  return request<API.SkillRepositoryItem>({
    url: `/api/admin/skill-repositories/update/${id}`,
    method: 'PUT',
    data,
  });
}

export function toggleRepo(id: number, status: number) {
  return request<void>({
    url: `/api/admin/skill-repositories/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

export function deleteRepo(id: number) {
  return request<void>({ url: `/api/admin/skill-repositories/${id}`, method: 'DELETE' });
}

/** 拉取远程技能列表（同步用） */
export function fetchRemoteSkills(id: number) {
  return request<string[]>({ url: `/api/admin/skill-repositories/fetch/${id}` });
}

// ---- 技能 ----
export function getSkillPage(params: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  repositoryId?: number;
  status?: number;
}) {
  return request<API.PageResult<API.SkillItem>>({
    url: '/api/admin/skills/page',
    params,
  });
}

export function getSkillById(id: number) {
  return request<API.SkillItem>({ url: `/api/admin/skills/${id}` });
}

export function deleteSkill(id: number) {
  return request<void>({ url: `/api/admin/skills/${id}`, method: 'DELETE' });
}

export function toggleSkill(id: number, status: number) {
  return request<void>({
    url: `/api/admin/skills/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

/** 批量保存技能（同步） */
export function batchSaveSkills(repositoryId: number, names: string[]) {
  return request<any>({
    url: '/api/admin/skills/batch',
    method: 'POST',
    params: { repositoryId },
    data: names,
  });
}
