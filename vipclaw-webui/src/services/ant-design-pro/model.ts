import { request } from '@umijs/max';

/**
 * 模型分页查询
 */
export async function modelPage(params: {
  current?: number;
  pageSize?: number;
  name?: string;
  providerId?: number;
  modelType?: string;
  status?: number;
  tags?: string;
  minPrice?: number;
  maxPrice?: number;
}) {
  return request<API.Result<API.PageResult<API.ModelItem>>>('/admin/model/page', {
    method: 'GET',
    params,
  });
}

/**
 * 获取模型详情
 */
export async function getModel(id: number) {
  return request<API.Result<API.ModelItem>>(`/admin/model/${id}`, {
    method: 'GET',
  });
}

/**
 * 创建模型
 */
export async function createModel(data: API.ModelCreateRequest) {
  return request<API.Result<API.ModelItem>>('/admin/model', {
    method: 'POST',
    data,
  });
}

/**
 * 更新模型
 */
export async function updateModel(id: number, data: API.ModelUpdateRequest) {
  return request<API.Result<API.ModelItem>>(`/admin/model/update/${id}`, {
    method: 'PUT',
    data,
  });
}

/**
 * 切换模型状态
 */
export async function toggleModel(id: number) {
  return request<API.Result<API.ModelItem>>(`/admin/model/toggle/${id}`, {
    method: 'PUT',
  });
}

/**
 * 删除模型
 */
export async function deleteModel(id: number) {
  return request<API.Result<void>>(`/admin/model/${id}`, {
    method: 'DELETE',
  });
}
