import { request } from '@umijs/max';

/**
 * 模型服务商分页查询
 */
export async function modelProviderPage(params: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  status?: number;
  isPublic?: number;
}) {
  return request<API.Result<API.PageResult<API.ModelProviderItem>>>('/admin/model-providers/page', {
    method: 'GET',
    params,
  });
}

/**
 * 获取模型服务商详情
 */
export async function getModelProvider(id: number) {
  return request<API.Result<API.ModelProviderItem>>(`/admin/model-providers/${id}`, {
    method: 'GET',
  });
}

/**
 * 创建模型服务商
 */
export async function createModelProvider(data: API.ModelProviderCreateRequest) {
  return request<API.Result<API.ModelProviderItem>>('/admin/model-providers', {
    method: 'POST',
    data,
  });
}

/**
 * 更新模型服务商
 */
export async function updateModelProvider(id: number, data: API.ModelProviderUpdateRequest) {
  return request<API.Result<API.ModelProviderItem>>(`/admin/model-providers/update/${id}`, {
    method: 'PUT',
    data,
  });
}

/**
 * 切换模型服务商状态
 */
export async function toggleModelProvider(id: number, status: number) {
  return request<API.Result<API.ModelProviderItem>>(`/admin/model-providers/toggle/${id}`, {
    method: 'PUT',
    params: { status },
  });
}

/**
 * 删除模型服务商
 */
export async function deleteModelProvider(id: number) {
  return request<API.Result<void>>(`/admin/model-providers/${id}`, {
    method: 'DELETE',
  });
}

/**
 * 连接测试
 */
export async function connectivityTest(id: number) {
  return request<API.Result<boolean>>(`/admin/model-providers/${id}/test`, {
    method: 'POST',
  });
}
