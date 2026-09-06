// services/skill.ts
// 技能仓库 + 技能
import { request } from './request';

// GitSkillLoader 的克隆上限是 180 秒，NpmSkillLoader 的安装上限是 120 秒；创建来源时这两步
// 都在请求内同步完成，用默认的 30 秒会在服务端还在干活的时候先超时
const SOURCE_CREATE_TIMEOUT = 190000;

// ---- 仓库 ----
export function getRepoPage(params: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  status?: number;
}) {
  return request<API.PageResult<API.SkillRepositoryItem>>({
    url: '/api/admin/skill-sources/page',
    params,
  });
}

export function getRepoById(id: number) {
  return request<API.SkillRepositoryItem>({ url: `/api/admin/skill-sources/${id}` });
}

/**
 * 新建技能来源（创建即安装）。
 *
 * 走 skill-sources 而不是旧版 skill-repositories：旧版 DTO 只有 name / url / branch，表单里的
 * sourceType、sourceConfig、version 会被静默丢掉，NPM 来源存不进去，GIT 来源也因为拿不到顶层
 * url 而被服务端校验拒绝。服务端在这一次请求里同步克隆或 npm install，超时按克隆上限放宽。
 */
export function createRepo(data: API.SkillSourceCreateRequest) {
  return request<API.SkillSourceInstallResult>({
    url: '/api/admin/skill-sources',
    method: 'POST',
    data,
    timeout: SOURCE_CREATE_TIMEOUT,
  });
}

/** 更新技能来源。只写配置、不会重新拉取，改完地址要在列表里再同步一次才会生效 */
export function updateRepo(id: number, data: API.SkillSourceUpdateRequest) {
  return request<void>({
    url: `/api/admin/skill-sources/${id}`,
    method: 'PUT',
    data,
  });
}

export function toggleRepo(id: number, status: number) {
  return request<void>({
    url: `/api/admin/skill-sources/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

export function deleteRepo(id: number) {
  return request<void>({ url: `/api/admin/skill-sources/${id}`, method: 'DELETE' });
}

/**
 * 拉取远程技能列表（同步用）。
 *
 * 后端返回的是对象数组而不是技能名字符串数组，调用方需自己取 name 再传给 batchSaveSkills。
 */
export function fetchRemoteSkills(id: number) {
  return request<API.SkillSyncItem[]>({ url: `/api/admin/skill-sources/${id}/fetch` });
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

/** 批量保存技能（同步），返回逐个技能的落库结果 */
export function batchSaveSkills(repositoryId: number, names: string[]) {
  return request<API.SkillInstallResult>({
    url: '/api/admin/skills/batch',
    method: 'POST',
    params: { repositoryId },
    data: names,
  });
}
