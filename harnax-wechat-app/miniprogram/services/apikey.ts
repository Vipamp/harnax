// services/apikey.ts
import { request } from './request';

export function getApiKeyPage(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  enabled?: number;
}) {
  return request<API.PageResult<API.ApiKeyItem>>({
    url: '/api/admin/api-keys/page',
    params,
  });
}

export function getApiKeyById(id: number) {
  return request<API.ApiKeyItem>({ url: `/api/admin/api-keys/${id}` });
}

export function createApiKey(data: API.ApiKeyCreateRequest) {
  return request<API.ApiKeyCreatedResponse>({
    url: '/api/admin/api-keys',
    method: 'POST',
    data,
  });
}

export function updateApiKey(id: number, data: API.ApiKeyUpdateRequest) {
  return request<API.ApiKeyItem>({
    url: `/api/admin/api-keys/update/${id}`,
    method: 'PUT',
    data,
  });
}

export function toggleApiKey(id: number, enabled: number) {
  return request<void>({
    url: `/api/admin/api-keys/toggle/${id}`,
    method: 'PUT',
    params: { enabled },
  });
}

export function deleteApiKey(id: number) {
  return request<void>({ url: `/api/admin/api-keys/${id}`, method: 'DELETE' });
}

export function regenerateApiKey(id: number) {
  return request<API.ApiKeyCreatedResponse>({
    url: `/api/admin/api-keys/${id}/regenerate`,
    method: 'POST',
  });
}
