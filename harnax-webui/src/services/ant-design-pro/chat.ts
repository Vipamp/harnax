// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 发送聊天消息 (SSE 流式响应)
 * 注意: 此接口使用 EventSource 调用，不在这里直接调用
 * 前端直接使用 new EventSource('/ai/chat?params') 即可
 */
export async function sendChatMessage(
  params: {
    sessionId: string;
    message: string;
    enableThink?: boolean;
    enableSearch?: boolean;
  },
  options?: { [key: string]: any },
) {
  return request(`/ai/chat`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 获取历史会话消息
 */
export async function getSessionMessages(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request(`/ai/session/${sessionId}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 获取会话的聊天配置
 */
export async function getSessionConfig(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request(`/api/sessions/${sessionId}/config`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 更新会话的聊天配置
 */
export async function updateSessionConfig(
  sessionId: string,
  data: {
    enableThink: boolean;
    enableSearch: boolean;
    enablePlan: boolean;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/sessions/${sessionId}/config`, {
    method: 'PUT',
    data,
    ...(options || {}),
  });
}
