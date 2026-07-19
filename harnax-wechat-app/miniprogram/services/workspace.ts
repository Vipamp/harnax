// services/workspace.ts
// 会话工作区（沙箱）文件服务：列表 / 读取 / 状态 / 上传 / 下载（均走 Router 鉴权）

import { request } from './request';
import { getRouterApiKey, getRouterUrl } from '../utils/auth';

export interface WorkspaceFile {
  name: string;
  type: string; // 'file' | 'directory'
  size?: number;
  path?: string;
  modifyTime?: string | number;
}

export interface WorkspaceFileContent {
  content?: string;
  path?: string;
  size?: number;
}

/** 列出工作区文件 */
export function listWorkspaceFiles(sessionId: string, path = '/workspace') {
  return request<WorkspaceFile[]>({
    url: `/api/router/agent/workspace/${sessionId}/files`,
    params: { path },
    useRouterAuth: true,
    silent: true,
  });
}

/** 读取文件内容 */
export function readWorkspaceFile(sessionId: string, path: string) {
  return request<WorkspaceFileContent>({
    url: `/api/router/agent/workspace/${sessionId}/read`,
    params: { path },
    useRouterAuth: true,
    silent: true,
  });
}

/** 沙箱状态（返回 sessionId -> { active } 映射） */
export function getWorkspaceStatus(sessionId: string) {
  return request<Record<string, { active: boolean; containerName?: string; image?: string }>>({
    url: `/api/router/agent/workspace/status`,
    params: { sessionIds: sessionId },
    useRouterAuth: true,
    silent: true,
  });
}

/** 上传文件到工作区 */
export function uploadWorkspaceFile(sessionId: string, filePath: string, path = '/workspace') {
  return new Promise<any>((resolve, reject) => {
    wx.uploadFile({
      url: `${getRouterUrl()}/api/router/agent/workspace/${sessionId}/upload`,
      filePath,
      name: 'file',
      formData: { path },
      header: { 'X-Api-Key': getRouterApiKey() },
      success: (res) => {
        try {
          const body = JSON.parse(res.data);
          if (body && body.code === 200) resolve(body.data);
          else reject(new Error((body && body.message) || '上传失败'));
        } catch (e) {
          reject(new Error('上传响应解析失败'));
        }
      },
      fail: (err) => reject(err),
    });
  });
}

/** 下载工作区文件，返回临时文件路径 */
export function downloadWorkspaceFile(sessionId: string, path: string) {
  const url = `${getRouterUrl()}/api/router/agent/workspace/${sessionId}/download?path=${encodeURIComponent(
    path,
  )}`;
  return new Promise<string>((resolve, reject) => {
    wx.downloadFile({
      url,
      header: { 'X-Api-Key': getRouterApiKey() },
      success: (res) => {
        if (res.statusCode === 200) resolve(res.tempFilePath);
        else reject(new Error(`下载失败(${res.statusCode})`));
      },
      fail: (err) => reject(err),
    });
  });
}
