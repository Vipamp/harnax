// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取用户列表 GET /api/users/page */
export async function getUserPage(
  params: {
   pageNum?: number;
   pageSize?: number;
    keyword?: string;
   status?: number;
  },
  options?: { [key: string]: any },
) {
 return request('/api/users/page', {
   method: 'GET',
   params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取用户详情 GET /api/users/${id} */
export async function getUserById(id: number, options?: { [key: string]: any }) {
 return request(`/api/users/${id}`, {
   method: 'GET',
    ...(options || {}),
  });
}

/**创建用户 POST /api/users */
export async function createUser(data: API.SysUserCreateRequest, options?: { [key: string]: any }) {
 return request('/api/users', {
   method: 'POST',
   headers: {
      'Content-Type': 'application/json',
    },
   data: data,
    ...(options || {}),
  });
}

/**更新用户 PUT /api/users/update/${userId} */
export async function updateUser(
  userId: number,
  data: API.SysUserUpdateRequest,
  options?: { [key: string]: any },
) {
 return request(`/api/users/update/${userId}`, {
   method: 'PUT',
   headers: {
      'Content-Type': 'application/json',
    },
   data: data,
    ...(options || {}),
  });
}

/** 切换用户状态 PUT /api/users/toggle/${userId} */
export async function toggleUserStatus(
  userId: number,
  status: number,
  options?: { [key: string]: any },
) {
 return request(`/api/users/toggle/${userId}`, {
   method: 'PUT',
   params: {
     status,
    },
    ...(options || {}),
  });
}

/** 删除用户 DELETE /api/users/${userId} */
export async function deleteUser(userId: number, options?: { [key: string]: any }) {
 return request(`/api/users/${userId}`, {
   method: 'DELETE',
    ...(options || {}),
  });
}

/** 检查用户名是否存在 GET /api/users/check/username */
export async function checkUsername(
  username: string,
  options?: { [key: string]: any },
) {
 return request('/api/users/check/username', {
   method: 'GET',
   params: { username },
    ...(options || {}),
  });
}

/** 检查手机号是否存在 GET /api/users/check/phone */
export async function checkPhone(
  phone: string,
  options?: { [key: string]: any },
) {
 return request('/api/users/check/phone', {
   method: 'GET',
   params: { phone },
    ...(options || {}),
  });
}

/** 检查邮箱是否存在 GET /api/users/check/email */
export async function checkEmail(
  email: string,
  options?: { [key: string]: any },
) {
 return request('/api/users/check/email', {
   method: 'GET',
   params: { email },
    ...(options || {}),
  });
}
