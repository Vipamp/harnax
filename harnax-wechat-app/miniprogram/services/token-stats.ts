// services/token-stats.ts
import { request } from './request';

/** Token aggregation response from backend */
interface TokenAggregationResponse {
  overall?: {
    totalInputToken?: number;
    totalOutputToken?: number;
    grandTotalToken?: number;
    totalFee?: number;
    agentCount?: number;
    sessionCount?: number;
    modelCount?: number;
  };
  modelStats?: any[];
  sessionStats?: any[];
  agentStats?: any[];
  timeSeriesData?: any[];
}

/** Token 聚合统计 */
export function getTokenAggregation(params: { startTime?: string; endTime?: string }) {
  return request<TokenAggregationResponse>({
    url: '/api/admin/token-stats/aggregation',
    params,
  });
}

/** Token 时序数据（后端返回 TokenStatsAggregationResponse，含 timeSeriesData 字段） */
export function getTokenTimeSeries(params: {
  startTime?: string;
  endTime?: string;
  granularity?: 'hour' | 'day' | 'month';
}) {
  return request<TokenAggregationResponse>({
    url: '/api/admin/token-stats/time-series',
    params: { granularity: 'day', ...params },
  });
}
