// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取用户列表 GET /admin/users/list */
export async function getUserPage(
  params: {
   pageNum?: number;
   pageSize?: number;
    keyword?: string;
   status?: number;
  },
  options?: { [key: string]: any },
) {
 return request('/admin/users/list', {
   method: 'GET',
   params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取用户详情 GET /admin/users/${id} */
export async function getUserById(id: number, options?: { [key: string]: any }) {
 return request(`/admin/users/${id}`, {
   method: 'GET',
    ...(options || {}),
  });
}

/**创建用户 POST /admin/users */
export async function createUser(data: API.SysUserCreateRequest, options?: { [key: string]: any }) {
 return request('/admin/users', {
   method: 'POST',
   headers: {
      'Content-Type': 'application/json',
    },
   data: data,
    ...(options || {}),
  });
}

/**更新用户 PUT /admin/users/update/${userId} */
export async function updateUser(
  userId: number,
  data: API.SysUserUpdateRequest,
  options?: { [key: string]: any },
) {
 return request(`/admin/users/update/${userId}`, {
   method: 'PUT',
   headers: {
      'Content-Type': 'application/json',
    },
   data: data,
    ...(options || {}),
  });
}

/** 切换用户状态 PUT /admin/users/toggle/${userId} */
export async function toggleUserStatus(
  userId: number,
  status: number,
  options?: { [key: string]: any },
) {
 return request(`/admin/users/toggle/${userId}`, {
   method: 'PUT',
   params: {
     status,
    },
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
