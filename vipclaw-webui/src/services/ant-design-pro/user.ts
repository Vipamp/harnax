// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取用户列表 GET /admin/users/list */
export async function getUserList(options?: { [key: string]: any }) {
  return request('/admin/users/list', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 分页获取用户列表 GET /admin/users/page */
export async function getUserPage(
  params: {
    pageNum?: number;
    pageSize?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/admin/users/page', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取用户详情 GET /admin/users/${userId} */
export async function getUserById(userId: number, options?: { [key: string]: any }) {
  return request(`/admin/users/${userId}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建用户 POST /admin/users */
export async function createUser(data: API.UserItem, options?: { [key: string]: any }) {
  return request('/admin/users', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新用户 PUT /admin/users/${userId} */
export async function updateUser(
  userId: number,
  data: API.UserItem,
  options?: { [key: string]: any },
) {
  return request(`/admin/users/${userId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 删除用户 DELETE /admin/users/${userId} */
export async function deleteUser(userId: number, options?: { [key: string]: any }) {
  return request(`/admin/users/${userId}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}
