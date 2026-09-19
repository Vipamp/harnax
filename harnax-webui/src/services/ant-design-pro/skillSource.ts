// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

export async function getSkillSourcePage(
  params: {
    pageNum?: number;
    pageSize?: number;
    name?: string;
    sourceType?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/api/admin/skill-sources/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

export async function getSkillSourceById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/skill-sources/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

export async function createSkillSource(data: API.SkillSourceCreateRequest, options?: { [key: string]: any }) {
  return request('/api/admin/skill-sources', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

export async function updateSkillSource(
  id: number,
  data: API.SkillSourceUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/skill-sources/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

export async function deleteSkillSource(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/skill-sources/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

export async function toggleSkillSourceStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/skill-sources/toggle/${id}`, {
    method: 'PUT',
    params: {
      status,
    },
    ...(options || {}),
  });
}

export async function fetchSkillSourceSkills(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/skill-sources/${id}/fetch`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 从源安装技能：不传 names 即全量重装，传了则只装勾选的那几个。
 * POST /api/admin/skill-sources/${id}/install
 *
 * 修改 url / branch / packageName 只会更新配置，不会重新拉取，必须再调一次本接口。
 * ZIP 源不留存压缩包，调用会被后端拒绝。
 */
export async function installSkillSource(
  id: number,
  names?: string[],
  options?: { [key: string]: any },
) {
  return request(`/api/admin/skill-sources/${id}/install`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: names?.length ? { names } : {},
    ...(options || {}),
  });
}

/** 技能源列表（下拉选择用），补齐分页默认值 */
export async function getSkillSourceOptions(
  params?: {
    pageNum?: number;
    pageSize?: number;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return getSkillSourcePage(
    {
      pageNum: 1,
      pageSize: 100,
      ...params,
    },
    options,
  );
}

export async function uploadSkillSourceZip(
  file: File,
  name: string,
  options?: { [key: string]: any },
) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('name', name);

  return request('/api/admin/skill-sources/upload', {
    method: 'POST',
    data: formData,
    ...(options || {}),
  });
}
