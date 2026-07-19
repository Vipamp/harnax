// services/channel.ts
import { request } from './request';

export function getChannelPage(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  type?: string;
  status?: number;
}) {
  return request<API.PageResult<API.ChannelItem>>({
    url: '/api/admin/channels/page',
    params,
  });
}

export function getChannelById(id: number) {
  return request<API.ChannelItem>({ url: `/api/admin/channels/${id}` });
}

export function createChannel(data: API.ChannelCreateRequest) {
  return request<API.ChannelItem>({ url: '/api/admin/channels', method: 'POST', data });
}

export function updateChannel(id: number, data: API.ChannelUpdateRequest) {
  return request<API.ChannelItem>({
    url: `/api/admin/channels/update/${id}`,
    method: 'PUT',
    data,
  });
}

export function toggleChannel(id: number, status: number) {
  return request<void>({
    url: `/api/admin/channels/toggle/${id}`,
    method: 'PUT',
    params: { status },
  });
}

export function deleteChannel(id: number) {
  return request<void>({ url: `/api/admin/channels/${id}`, method: 'DELETE' });
}

/** Agent 下拉 */
export function getAgentOptions() {
  return request<API.PageResult<API.AgentItem>>({
    url: '/api/admin/agents/page',
    params: { pageNum: 1, pageSize: 100, status: 1 },
  });
}
