// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** Get built-in CLI registry list GET /api/admin/cli-plugins */
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
