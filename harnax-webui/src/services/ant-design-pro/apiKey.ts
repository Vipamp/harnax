import { request } from '@umijs/max';

export async function getApiKeyPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    keyword?: string;
    enabled?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/api/api-keys/page', {
    method: 'GET',
    params: { ...params },
    ...(options || {}),
  });
}

export async function getApiKeyById(id: number, options?: { [key: string]: any }) {
  return request(`/api/api-keys/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

export async function createApiKey(
  data: API.ApiKeyCreateRequest,
  options?: { [key: string]: any },
) {
  return request('/api/api-keys', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data,
    ...(options || {}),
  });
}

export async function updateApiKey(
  id: number,
  data: API.ApiKeyUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/api-keys/update/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    data,
    ...(options || {}),
  });
}

export async function toggleApiKeyStatus(
  id: number,
  enabled: number,
  options?: { [key: string]: any },
) {
  return request(`/api/api-keys/toggle/${id}`, {
    method: 'PUT',
    params: { enabled },
    ...(options || {}),
  });
}

export async function deleteApiKey(id: number, options?: { [key: string]: any }) {
  return request(`/api/api-keys/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

export async function regenerateApiKey(id: number, options?: { [key: string]: any }) {
  return request(`/api/api-keys/${id}/regenerate`, {
    method: 'POST',
    ...(options || {}),
  });
}
