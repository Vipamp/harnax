// services/chat.ts
// 对话服务：历史消息（Router）+ SSE 流式对话

import { request, requestRaw } from './request';
import { getRouterApiKey, getRouterUrl } from '../utils/auth';

/** 获取历史会话消息（Router 鉴权） */
export function getSessionMessages(sessionId: string) {
  const routerUrl = getRouterUrl();
  const apiKey = getRouterApiKey();
  console.log('[chat] getSessionMessages: sessionId=%s, routerUrl=%s, hasApiKey=%s', sessionId, routerUrl, !!apiKey);
  return request<any>({
    url: `/api/router/agent/chat/history/${sessionId}`,
    method: 'GET',
    useRouterAuth: true,
    silent: true,
  });
}

/** 获取会话聊天配置 */
export function getSessionConfig(sessionId: string) {
  return request<any>({
    url: `/api/admin/sessions/${sessionId}/config`,
    method: 'GET',
    silent: true,
  });
}

/** 静默命令返回体 */
export interface CommandResult {
  success?: boolean;
  message?: string;
}

/**
 * 发送会话静默命令（配置切换等，Router 鉴权）。
 * command: ENABLE/DISABLE（args: thinking/search/plan）、PERMISSION（args: 权限模式）、CLEAR/INTERRUPT/STOP_SANDBOX 等。
 */
export function sendSessionCommand(sessionId: string, command: string, args = '') {
  return requestRaw<CommandResult>({
    url: '/api/router/agent/command',
    method: 'POST',
    useRouterAuth: true,
    silent: true,
    data: { type: 'COMMAND', sessionId, command, args },
  });
}

/** UTF-8 ArrayBuffer -> string */
function ab2str(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let str = '';
  let i = 0;
  while (i < bytes.length) {
    const byte1 = bytes[i++];
    if (byte1 < 0x80) {
      str += String.fromCharCode(byte1);
    } else if (byte1 >= 0xc0 && byte1 < 0xe0) {
      const byte2 = bytes[i++];
      str += String.fromCharCode(((byte1 & 0x1f) << 6) | (byte2 & 0x3f));
    } else if (byte1 >= 0xe0 && byte1 < 0xf0) {
      const byte2 = bytes[i++];
      const byte3 = bytes[i++];
      str += String.fromCharCode(
        ((byte1 & 0x0f) << 12) | ((byte2 & 0x3f) << 6) | (byte3 & 0x3f),
      );
    } else {
      const byte2 = bytes[i++];
      const byte3 = bytes[i++];
      const byte4 = bytes[i++];
      let codepoint =
        ((byte1 & 0x07) << 18) |
        ((byte2 & 0x3f) << 12) |
        ((byte3 & 0x3f) << 6) |
        (byte4 & 0x3f);
      codepoint -= 0x10000;
      str += String.fromCharCode(0xd800 + (codepoint >> 10), 0xdc00 + (codepoint & 0x3ff));
    }
  }
  return str;
}

/** 待确认工具 */
export interface PendingCallTool {
  toolId: string;
  toolName: string;
  arguments?: any;
}

/** SSE 事件（对齐 webui：eventType + message 字段） */
export interface SSEEvent {
  eventType?:
    | 'TextEvent'
    | 'ThinkingEvent'
    | 'CallToolEvent'
    | 'ToolResultEvent'
    | 'EndEvent'
    | 'ToolConfirmEvent'
    | string;
  message?: string;
  isLast?: boolean;
  toolName?: string;
  toolId?: string;
  arguments?: any;
  success?: boolean;
  pendingCallTools?: PendingCallTool[];
  [key: string]: any;
}

export interface StreamHandlers {
  onEvent: (event: SSEEvent) => void;
  onDone: () => void;
  onError: (err: any) => void;
}

/** 通用流式请求（CHAT / CONFIRM 共用），返回 RequestTask，可 .abort() */
function streamRequest(path: string, body: any, handlers: StreamHandlers) {
  const baseUrl = getRouterUrl();
  const apiKey = getRouterApiKey();
  let buffer = '';

  const task = wx.request({
    url: `${baseUrl}${path}`,
    method: 'POST',
    enableChunked: true,
    responseType: 'arraybuffer',
    header: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-Api-Key': apiKey,
    },
    data: body,
    success(res) {
      // Handle HTTP errors (401, 500, etc.) - response body is error JSON, not SSE
      if (res.statusCode && res.statusCode >= 400) {
        let msg = `Router 请求失败(${res.statusCode})`;
        try {
          // With responseType=arraybuffer, error body needs decoding
          const raw = typeof res.data === 'string' ? res.data : ab2str(res.data as ArrayBuffer);
          const parsed = JSON.parse(raw);
          if (parsed && (parsed.message || parsed.msg)) {
            msg = parsed.message || parsed.msg;
          }
        } catch { /* use default msg */ }
        console.error('[chat] stream HTTP error: status=%d, msg=%s', res.statusCode, msg);
        handlers.onError(new Error(msg));
        return;
      }
      // 流结束，处理 buffer 中可能残留的最后一行
      flushBuffer();
      handlers.onDone();
    },
    fail(err) {
      handlers.onError(err);
    },
  });

  task.onChunkReceived((res: any) => {
    let text = '';
    try {
      text = typeof res.data === 'string' ? res.data : ab2str(res.data);
    } catch (e) {
      console.error('[chat] decode chunk failed', e);
      return;
    }
    buffer += text;
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith('data:')) continue;
      const jsonStr = trimmed.substring(5).trim();
      if (!jsonStr || jsonStr === '[DONE]') continue;
      try {
        const event = JSON.parse(jsonStr) as SSEEvent;
        handlers.onEvent(event);
      } catch {
        // 分块可能截断 JSON，忽略解析失败（等待下一块补齐）
      }
    }
  });

  // 处理 buffer 中残留数据（服务端可能不发尾部换行）
  const flushBuffer = () => {
    if (!buffer) return;
    const trimmed = buffer.trim();
    buffer = '';
    if (!trimmed.startsWith('data:')) return;
    const jsonStr = trimmed.substring(5).trim();
    if (!jsonStr || jsonStr === '[DONE]') return;
    try {
      const event = JSON.parse(jsonStr) as SSEEvent;
      handlers.onEvent(event);
    } catch {
      // 残留数据无法解析为完整 JSON，丢弃
    }
  };

  return task;
}

/** 发起 SSE 流式对话 */
export function streamChat(
  sessionId: string,
  message: string,
  handlers: StreamHandlers,
  imageUrls: string[] = [],
) {
  return streamRequest(
    '/api/router/agent/chat/stream',
    { type: 'CHAT', sessionId, message, imageUrls },
    handlers,
  );
}

/** 工具确认 / 拒绝（返回后续 SSE 流） */
export function confirmTools(
  sessionId: string,
  isConfirmed: boolean,
  toolInfoList: { toolId: string; toolName: string }[],
  handlers: StreamHandlers,
) {
  return streamRequest(
    '/api/router/agent/confirm',
    { type: 'CONFIRM', sessionId, isConfirmed, toolInfoList },
    handlers,
  );
}
