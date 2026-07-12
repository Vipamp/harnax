// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

function getRouterApiKey(): string {
  try {
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    if (tokenInfoStr) {
      const tokenInfo = JSON.parse(tokenInfoStr);
      return tokenInfo.routerApiKey || '';
    }
  } catch {
    // ignore
  }
  return '';
}

/**
 * Build router request options: X-Api-Key header + skip JWT Authorization
 */
function buildRouterOptions(extraOptions?: { [key: string]: any }) {
  const apiKey = getRouterApiKey();
  return {
    skipAuthorization: true,
    headers: {
      'X-Api-Key': apiKey,
    },
    ...(extraOptions || {}),
  };
}

/**
 * 获取历史会话消息
 */
export async function getSessionMessages(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request(`/api/router/agent/chat/history/${sessionId}`, {
    method: 'GET',
    ...buildRouterOptions(options),
  });
}

/**
 * 获取会话的聊天配置
 */
export async function getSessionConfig(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/sessions/${sessionId}/config`, {
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
  return request(`/api/admin/sessions/${sessionId}/config`, {
    method: 'PUT',
    data,
    ...(options || {}),
  });
}
