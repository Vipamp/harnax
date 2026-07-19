// services/model.ts
// 模型服务商 + 模型管理
import { request } from './request';

// ---- 服务商 ----
export function getProviderPage(params: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  type?: string;
  status?: number;
}) {
  return request<API.PageResult<API.ModelProviderItem>>({
    url: '/api/admin/model-providers/page',
    params,
  });
}

export function getProviderById(id: number) {
  return request<API.ModelProviderItem>({ url: `/api/admin/model-providers/${id}` });
}

export function createProvider(data: API.ModelProviderCreateRequest) {
  return request<API.ModelProviderItem>({
    url: '/api/admin/model-providers',
    method: 'POST',
    data,
  });
}

export function updateProvider(id: number, data: API.ModelProviderUpdateRequest) {
  return request<API.ModelProviderItem>({
    url: `/api/admin/model-providers/update/${id}`,
    method: 'PUT',
    data,
  });
}

export function toggleProvider(id: number, status: number) {
  return request<void>({
    url: `/api/admin/model-providers/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

export function deleteProvider(id: number) {
  return request<void>({ url: `/api/admin/model-providers/${id}`, method: 'DELETE' });
}

export function testProvider(id: number) {
  return request<boolean>({ url: `/api/admin/model-providers/${id}/test`, method: 'POST' });
}

// ---- 模型 ----
export function getModelPage(params: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  providerId?: number;
  modelType?: string;
  status?: number;
}) {
  return request<API.PageResult<API.ModelItem>>({
    url: '/api/admin/models/page',
    params,
  });
}

export function getModelById(id: number) {
  return request<API.ModelItem>({ url: `/api/admin/models/${id}` });
}

export function createModel(data: API.ModelCreateRequest) {
  return request<API.ModelItem>({ url: '/api/admin/models', method: 'POST', data });
}

export function updateModel(id: number, data: API.ModelUpdateRequest) {
  return request<API.ModelItem>({
    url: `/api/admin/models/update/${id}`,
    method: 'PUT',
    data,
  });
}

export function toggleModel(id: number, status: number) {
  return request<void>({
    url: `/api/admin/models/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

export function deleteModel(id: number) {
  return request<void>({ url: `/api/admin/models/${id}`, method: 'DELETE' });
}

/** 服务商下拉（供模型表单选择） */
export function getProviderOptions() {
  return request<API.PageResult<API.ModelProviderItem>>({
    url: '/api/admin/model-providers/page',
    params: { pageNum: 1, pageSize: 100, status: 1 },
  });
}
