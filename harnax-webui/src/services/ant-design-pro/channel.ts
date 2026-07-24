// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * 分页获取 Channel 列表
 */
export async function getChannelPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    keyword?: string;
    type?: string;
    status?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/admin/channels/page`, {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 获取 Channel 详情
 */
export async function getChannelById(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/channels/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 创建 Channel
 */
export async function createChannel(
  data: API.ChannelCreateRequest,
  options?: { [key: string]: any },
) {
  return request('/api/admin/channels', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
    ...(options || {}),
  });
}

/**
 * 更新 Channel
 */
export async function updateChannel(
  id: number,
  data: API.ChannelUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/channels/update/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
    ...(options || {}),
  });
}

/**
 * 切换 Channel 状态
 */
export async function toggleChannelStatus(
  id: number,
  status: number,
  options?: { [key: string]: any },
) {
  return request(`/api/admin/channels/toggle/${id}`, {
    method: 'PUT',
    params: { status },
    ...(options || {}),
  });
}

/**
 * 删除 Channel
 */
export async function deleteChannel(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/channels/${id}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/**
 * 获取智能体列表（用于下拉选择）
 */
export async function getAgentList(
  params?: {
    pageNum?: number;
    pageSize?: number;
  },
  options?: { [key: string]: any },
) {
  return request(`/api/admin/agents/page`, {
    method: 'GET',
    params: {
      pageNum: 1,
      pageSize: 100,
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * 启动微信扫码登录，返回二维码（base64 PNG data URL）
 */
export async function startWechatLogin(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/channels/${id}/wechat/login`, {
    method: 'POST',
    ...(options || {}),
  });
}

/**
 * 查询微信扫码登录状态（WAITING/SCANNED/LOGGED_IN/EXPIRED/ERROR/NOT_LOGIN）
 */
export async function getWechatLoginStatus(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/channels/${id}/wechat/login/status`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 取消进行中的微信扫码登录
 */
export async function cancelWechatLogin(id: number, options?: { [key: string]: any }) {
  return request(`/api/admin/channels/${id}/wechat/login/cancel`, {
    method: 'POST',
    ...(options || {}),
  });
}
