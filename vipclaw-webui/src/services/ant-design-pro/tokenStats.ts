// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 获取 Token 聚合统计数据
 */
export async function getTokenStatsAggregation(
  params: {
    startTime?: string;
    endTime?: string;
  },
  options?: { [key: string]: any },
) {
  return request('/admin/token-stats/aggregation', {
    method: 'GET',
    params: {
      startTime: params.startTime,
      endTime: params.endTime,
    },
    ...(options || {}),
  });
}

/**
 * 获取 Token 时序数据
 */
export async function getTokenStatsTimeSeries(
  params: {
    startTime?: string;
    endTime?: string;
    granularity?: 'hour' | 'day' | 'month';
  },
  options?: { [key: string]: any },
) {
  return request('/admin/token-stats/time-series', {
    method: 'GET',
    params: {
      startTime: params.startTime,
      endTime: params.endTime,
      granularity: params.granularity || 'day',
    },
    ...(options || {}),
  });
}

/**
 * 获取模型时序数据
 */
export async function getModelTimeSeries(
  params: {
    startTime?: string;
    endTime?: string;
    granularity?: 'hour' | 'day' | 'month';
  },
  options?: { [key: string]: any },
) {
  return request('/admin/token-stats/time-series/model', {
    method: 'GET',
    params: {
      startTime: params.startTime,
      endTime: params.endTime,
      granularity: params.granularity || 'day',
    },
    ...(options || {}),
  });
}

/**
 * 获取智能体时序数据
 */
export async function getAgentTimeSeries(
  params: {
    startTime?: string;
    endTime?: string;
    granularity?: 'hour' | 'day' | 'month';
  },
  options?: { [key: string]: any },
) {
  return request('/admin/token-stats/time-series/agent', {
    method: 'GET',
    params: {
      startTime: params.startTime,
      endTime: params.endTime,
      granularity: params.granularity || 'day',
    },
    ...(options || {}),
  });
}

/**
 * 获取会话时序数据
 */
export async function getSessionTimeSeries(
  params: {
    startTime?: string;
    endTime?: string;
    granularity?: 'hour' | 'day' | 'month';
  },
  options?: { [key: string]: any },
) {
  return request('/admin/token-stats/time-series/session', {
    method: 'GET',
    params: {
      startTime: params.startTime,
      endTime: params.endTime,
      granularity: params.granularity || 'day',
    },
    ...(options || {}),
  });
}
