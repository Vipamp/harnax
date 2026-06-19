import type {
  ChatRequest,
  CommandRequest,
  ConfirmRequest,
  ApiResponse,
  ChatResponse,
  CommandResponse,
  HistoryMessage,
} from '../types/api'
import type { ChatEvent } from '../types/chat'
import { request } from './client'
import { streamSSE, type SSEOptions } from './sse'
import { useConnectionStore } from '../store/useConnectionStore'
import { generateUUID } from '../utils/platform'

function getStreamUrl(path: string): string {
  const connection = useConnectionStore()
  const base = connection.routerUrl.replace(/\/+$/, '')
  return `${base}${path}`
}

function getStreamHeaders(): Record<string, string> {
  const connection = useConnectionStore()
  return {
    'Content-Type': 'application/json',
    'X-Api-Key': connection.apiKey,
  }
}

export async function streamChat(
  params: ChatRequest,
  onEvent: (event: ChatEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const body = {
    ...params,
    requestId: params.requestId || generateUUID(),
  }

  await streamSSE(
    getStreamUrl('/api/router/agent/chat/stream'),
    body,
    getStreamHeaders(),
    {
      onEvent,
      signal,
      onError: (error) => {
        onEvent({
          eventType: 'ErrorEvent',
          code: -1,
          message: error.message,
        })
        onEvent({ eventType: 'EndEvent' })
      },
    },
  )
}

export async function streamConfirm(
  params: ConfirmRequest,
  onEvent: (event: ChatEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  await streamSSE(
    getStreamUrl('/api/router/agent/confirm'),
    params,
    getStreamHeaders(),
    {
      onEvent,
      signal,
      onError: (error) => {
        onEvent({
          eventType: 'ErrorEvent',
          code: -1,
          message: error.message,
        })
        onEvent({ eventType: 'EndEvent' })
      },
    },
  )
}

export async function sendCommand(
  params: CommandRequest,
): Promise<ApiResponse<CommandResponse>> {
  return request<CommandResponse>('/api/router/agent/command', {
    method: 'POST',
    data: params,
  })
}

export async function clearSession(
  sessionId: string,
): Promise<ApiResponse<void>> {
  return request<void>(`/api/router/agent/session/${sessionId}`, {
    method: 'DELETE',
  })
}

export async function getChatHistory(
  sessionId: string,
): Promise<ApiResponse<HistoryMessage[]>> {
  return request<HistoryMessage[]>(
    `/api/router/agent/chat/history/${sessionId}`,
  )
}

export async function getPlans(
  sessionId: string,
): Promise<ApiResponse<unknown[]>> {
  return request<unknown[]>(
    `/api/router/agent/session/${sessionId}/plans`,
  )
}

export async function getCurrentPlan(
  sessionId: string,
): Promise<ApiResponse<unknown>> {
  return request<unknown>(
    `/api/router/agent/session/${sessionId}/current-plan`,
  )
}

export async function checkHealth(): Promise<boolean> {
  try {
    const res = await request('/api/router/health')
    return res.code === 200
  } catch {
    return false
  }
}
