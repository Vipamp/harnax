// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 首页运营总览：一次请求拿齐当前租户的全部指标。
 *
 * 接口不带任何查询参数：租户由请求头 X-Tenant-ID 经后端 TenantResolver 解析，时间窗由后端在
 * 一次 `now` 读取上推好（设计 §3.5 的 D3/D5）。加了 days 或 tenantId 参数就破了两条已定稿的决策。
 */
export async function getDashboardOverview(options?: { [key: string]: any }) {
  return request<API.Result<API.DashboardOverview>>('/api/admin/dashboard/overview', {
    method: 'GET',
    ...(options || {}),
  });
}
