// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** Get CLI plugin list GET /api/admin/cli-plugins */
export async function getCliPlugins(
  params?: { type?: string },
  options?: { [key: string]: any },
) {
  return request('/api/admin/cli-plugins', {
    method: 'GET',
    params,
    ...(options || {}),
  });
}

/** Get CLI plugin details GET /api/admin/cli-plugins/${id} */
export async function getCliPluginById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/cli-plugins/${id}`, { method: 'GET', ...(options || {}) });
}

/** Toggle CLI plugin status PUT /api/admin/cli-plugins/toggle/${id} */
export async function toggleCliPlugin(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/cli-plugins/toggle/${id}`, {
    method: 'PUT',
    params: { status },
    ...(options || {}),
  });
}
