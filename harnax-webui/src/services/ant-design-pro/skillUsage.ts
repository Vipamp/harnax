// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** GET /api/admin/skill-usage/summary — per-skill load/use counts for one window */
export async function getSkillUsageSummary(
  params: {
    days?: number;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.SkillUsageSummary>>('/api/admin/skill-usage/summary', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
