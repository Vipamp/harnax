// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** GET /api/admin/tool-metrics/summary — per-subject totals over one inclusive day range */
export async function getToolMetricsSummary(
  params: {
    /** First day, `yyyy-MM-dd`, inclusive; the server defaults it to 30 days before `end` */
    start?: string;
    /** Last day, `yyyy-MM-dd`, inclusive; the server defaults it to today */
    end?: string;
    kind?: string;
    groupBy?: string;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.CallMetricsSummary>>('/api/admin/tool-metrics/summary', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** GET /api/admin/tool-metrics/time-series — zero-filled buckets for the trend line */
export async function getToolMetricsTimeSeries(
  params: {
    start?: string;
    end?: string;
    granularity?: string;
    kind?: string;
    subjectId?: number;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.CallMetricsTrend>>('/api/admin/tool-metrics/time-series', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** GET /api/admin/tool-metrics/invocations — detail rows, inside the retention window only */
export async function getToolInvocations(
  params: {
    start?: string;
    end?: string;
    kind?: string;
    toolName?: string;
    mcpId?: number;
    cliId?: number;
    agentId?: number;
    sessionId?: string;
    outcome?: string;
    pageNum?: number;
    pageSize?: number;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.CallInvocationPage>>('/api/admin/tool-metrics/invocations', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
