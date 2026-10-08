// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** GET /api/admin/tool-metrics/summary — per-subject totals over one inclusive hour range */
export async function getToolMetricsSummary(
  params: {
    /** First hour, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd`, inclusive; the server defaults it to the 720 hours before `end` */
    start?: string;
    /** Last hour, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd`, inclusive; an end the clock has not reached is folded back to the current hour */
    end?: string;
    kind?: string;
    /** tool / mcp / cli read the hourly aggregate, agent / session read the detail table */
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
    /** First hour, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd`, inclusive */
    start?: string;
    /** Last hour, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd`, inclusive */
    end?: string;
    /** auto / hour / day / week / month; auto folds by span — hour up to 48 hours, day up to 92 days, then week. The response names the one actually used. */
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
    /** First hour, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd`, inclusive */
    start?: string;
    /** Last hour, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd`, inclusive */
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
