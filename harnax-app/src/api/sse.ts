import type { ChatEvent } from '../types/chat'

export interface SSEOptions {
  onEvent: (event: ChatEvent) => void
  onError?: (error: Error) => void
  signal?: AbortSignal
}

export async function streamSSE(
  url: string,
  body: Record<string, unknown>,
  headers: Record<string, string>,
  options: SSEOptions,
): Promise<void> {
  // #ifdef H5
  return streamSSEH5(url, body, headers, options)
  // #endif
  // #ifdef APP-PLUS
  return streamSSEApp(url, body, headers, options)
  // #endif
}

async function streamSSEH5(
  url: string,
  body: Record<string, unknown>,
  headers: Record<string, string>,
  options: SSEOptions,
): Promise<void> {
  const response = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal: options.signal,
  })

  if (!response.ok) {
    const text = await response.text().catch(() => '')
    options.onError?.(new Error(`HTTP ${response.status}: ${text}`))
    return
  }

  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (trimmed.startsWith('data:')) {
          const jsonStr = trimmed.slice(5).trim()
          if (!jsonStr) continue
          try {
            const event = JSON.parse(jsonStr) as ChatEvent
            options.onEvent(event)
            if (event.eventType === 'EndEvent') return
          } catch (e) {
            console.warn('[SSE] Failed to parse event:', jsonStr, e)
          }
        }
      }
    }
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      options.onError?.(e as Error)
    }
  }
}

async function streamSSEApp(
  url: string,
  body: Record<string, unknown>,
  headers: Record<string, string>,
  options: SSEOptions,
): Promise<void> {
  // App 平台使用 uni.request + enableChunked (HBuilderX 4.0+)
  // 降级方案：如果流式不可用，使用同步接口
  return new Promise((resolve, reject) => {
    const requestTask = uni.request({
      url,
      method: 'POST',
      data: body,
      header: headers,
      enableChunked: true,
      timeout: 600000,
      success: () => {
        resolve()
      },
      fail: (err) => {
        options.onError?.(new Error(err.errMsg || 'Request failed'))
        reject(err)
      },
    })

    let buffer = ''

    // @ts-ignore - enableChunked callback
    requestTask.onChunkReceived?.((res: { data: ArrayBuffer }) => {
      const text = arrayBufferToString(res.data)
      buffer += text

      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (trimmed.startsWith('data:')) {
          const jsonStr = trimmed.slice(5).trim()
          if (!jsonStr) continue
          try {
            const event = JSON.parse(jsonStr) as ChatEvent
            options.onEvent(event)
            if (event.eventType === 'EndEvent') {
              requestTask.abort?.()
              resolve()
              return
            }
          } catch (e) {
            console.warn('[SSE] Failed to parse event:', jsonStr)
          }
        }
      }
    })

    options.signal?.addEventListener('abort', () => {
      requestTask.abort?.()
      resolve()
    })
  })
}

function arrayBufferToString(buffer: ArrayBuffer): string {
  const uint8Array = new Uint8Array(buffer)
  let result = ''
  const chunkSize = 8192
  for (let i = 0; i < uint8Array.length; i += chunkSize) {
    const chunk = uint8Array.subarray(i, Math.min(i + chunkSize, uint8Array.length))
    result += String.fromCharCode(...chunk)
  }
  try {
    return decodeURIComponent(escape(result))
  } catch {
    return result
  }
}
