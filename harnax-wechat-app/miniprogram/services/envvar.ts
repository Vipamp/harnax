// services/envvar.ts
import { request } from './request';

export function getEnvVariablePage(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
}) {
  return request<API.PageResult<API.EnvVariableItem>>({
    url: '/api/admin/env-variables/page',
    params,
  });
}

export function getEnvVariableById(id: number) {
  return request<API.EnvVariableItem>({ url: `/api/admin/env-variables/${id}` });
}

export function createEnvVariable(data: API.EnvVariableCreateRequest) {
  return request<API.EnvVariableItem>({
    url: '/api/admin/env-variables',
    method: 'POST',
    data,
  });
}

export function updateEnvVariable(id: number, data: API.EnvVariableUpdateRequest) {
  return request<API.EnvVariableItem>({
    url: `/api/admin/env-variables/update/${id}`,
    method: 'PUT',
    data,
  });
}

export function deleteEnvVariable(id: number) {
  return request<void>({ url: `/api/admin/env-variables/${id}`, method: 'DELETE' });
}

export function toggleEnvVariable(id: number, enabled: number) {
  return request<void>({
    url: `/api/admin/env-variables/${id}/toggle`,
    method: 'PUT',
    params: { enabled },
  });
}
