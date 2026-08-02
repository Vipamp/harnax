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
