// services/user.ts
import { request } from './request';

export function getUserPage(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: number;
}) {
  return request<API.PageResult<API.UserItem>>({
    url: '/api/admin/users/page',
    params,
  });
}

export function getUserById(id: number) {
  return request<API.UserItem>({ url: `/api/admin/users/${id}` });
}

export function createUser(data: API.UserCreateRequest) {
  return request<API.UserItem>({ url: '/api/admin/users', method: 'POST', data });
}

export function updateUser(id: number, data: API.UserUpdateRequest) {
  return request<API.UserItem>({
    url: `/api/admin/users/update/${id}`,
    method: 'PUT',
    data,
  });
}

export function toggleUser(id: number, status: number) {
  return request<void>({
    url: `/api/admin/users/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

export function deleteUser(id: number) {
  return request<void>({ url: `/api/admin/users/${id}`, method: 'DELETE' });
}
