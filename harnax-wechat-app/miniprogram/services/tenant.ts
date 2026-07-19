// services/tenant.ts
import { request } from './request';

export function getTenantList(params: {
  pageNum: number;
  pageSize: number;
  name?: string;
  status?: number;
}) {
  return request<API.PageResult<API.TenantItem>>({
    url: '/api/admin/tenant',
    params,
  });
}

export function getTenantById(id: number) {
  return request<API.TenantItem>({ url: `/api/admin/tenant/${id}` });
}

export function createTenant(data: { name: string; code?: string; description?: string; adminUserId: number }) {
  return request<API.TenantItem>({ url: '/api/admin/tenant', method: 'POST', data });
}

export function toggleTenantStatus(id: number) {
  return request<void>({ url: `/api/admin/tenant/${id}/status`, method: 'PUT' });
}

export function deleteTenant(id: number) {
  return request<void>({ url: `/api/admin/tenant/${id}`, method: 'DELETE' });
}

export function getTenantUsers(tenantId: number, params: { pageNum: number; pageSize: number }) {
  return request<API.PageResult<any>>({
    url: `/api/admin/tenant/${tenantId}/users`,
    params,
  });
}

export function addUserToTenant(tenantId: number, data: { userId: number; role: string }) {
  return request<void>({
    url: `/api/admin/tenant/${tenantId}/users`,
    method: 'POST',
    data,
  });
}

export function removeUserFromTenant(tenantId: number, userId: number) {
  return request<void>({
    url: `/api/admin/tenant/${tenantId}/users/${userId}`,
    method: 'DELETE',
  });
}
