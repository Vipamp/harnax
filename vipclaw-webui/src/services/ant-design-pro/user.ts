// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取用户列表 GET /admin/users/page */
export async function getUserPage(
  params: {
   pageNum?: number;
   pageSize?: number;
    keyword?: string;
   status?: number;
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

/** 检查用户名是否存在 GET /admin/users/check/username */
export async function checkUsername(
  username: string,
  options?: { [key: string]: any },
) {
 return request('/admin/users/check/username', {
   method: 'GET',
   params: { username },
    ...(options || {}),
  });
}

/** 检查手机号是否存在 GET /admin/users/check/phone */
export async function checkPhone(
  phone: string,
  options?: { [key: string]: any },
) {
 return request('/admin/users/check/phone', {
   method: 'GET',
   params: { phone },
    ...(options || {}),
  });
}

/** 检查邮箱是否存在 GET /admin/users/check/email */
export async function checkEmail(
  email: string,
  options?: { [key: string]: any },
) {
 return request('/admin/users/check/email', {
   method: 'GET',
   params: { email },
    ...(options || {}),
  });
}
