// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 租户管理 API
 */

export interface TenantItem {
  id?: number;
  name?: string;
  status?: number;
  creator?: string;
  createTime?: string;
  updateTime?: string;
}

export interface UserTenantItem {
  id?: number;
  userId?: number;
  username?: string;
  nickname?: string;
  tenantId?: number;
  tenantName?: string;
  role?: string;
  status?: number;
  joinedAt?: string;
}

/**
 * 创建租户
 */
export async function createTenant(data: { name: string; adminUserId: number }) {
  return request('/api/tenant', {
    method: 'POST',
    data,
  });
}

/**
 * 查询租户列表
 */
export async function getTenantList(params: {
  pageNum: number;
  pageSize: number;
  name?: string;
  status?: number;
}) {
  return request('/api/tenant', {
    method: 'GET',
    params,
  });
}

/**
 * 获取租户详情
 */
export async function getTenantById(id: number) {
  return request(`/api/tenant/${id}`, {
    method: 'GET',
  });
}



/**
 * 切换租户状态
 */
export async function toggleTenantStatus(id: number) {
  return request(`/api/tenant/${id}/status`, {
    method: 'PUT',
  });
}

/**
 * 删除租户
 */
export async function deleteTenant(id: number) {
  return request(`/api/tenant/${id}`, {
    method: 'DELETE',
  });
}

/**
 * 查询租户下用户
 */
export async function getTenantUsers(tenantId: number, params: { pageNum: number; pageSize: number }) {
  return request(`/api/tenant/${tenantId}/users`, {
    method: 'GET',
    params,
  });
}

/**
 * 添加用户到租户
 */
export async function addUserToTenant(tenantId: number, data: { userId: number; role: string }) {
  return request(`/api/tenant/${tenantId}/users`, {
    method: 'POST',
    data,
  });
}

/**
 * 从租户移除用户
 */
export async function removeUserFromTenant(tenantId: number, userId: number) {
  return request(`/api/tenant/${tenantId}/users/${userId}`, {
    method: 'DELETE',
  });
}

/**
 * 更新用户在租户中的角色
 */
export async function updateUserRole(tenantId: number, userId: number, role: string) {
  return request(`/api/tenant/${tenantId}/users/${userId}/role`, {
    method: 'PUT',
    data: { role },
  });
}
