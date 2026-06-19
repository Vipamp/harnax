export interface ChatRequest {
  sessionId: string
  message: string
  imageUrls?: string[]
  requestId?: string
}

export interface CommandRequest {
  sessionId: string
  command: 'INTERRUPT' | 'CLEAR' | 'COMPACT' | 'APPROVE'
}

export interface ConfirmRequest {
  sessionId: string
  isConfirmed: boolean
  toolInfoList: Array<{
    toolId: string
    toolName: string
  }>
}

export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data?: T
  timestamp: number
}

export interface ChatResponse {
  sessionId: string
  content: string
  thinking?: string
  tokenUsage?: {
    inputTokens: number
    outputTokens: number
    totalTokens: number
    costTime: number
    timestamp: number
  }
}

export interface CommandResponse {
  sessionId: string
  success: boolean
  result?: unknown
  message?: string
}

export interface SessionInfo {
  sessionId: string
  name: string
  description?: string
  lastMessage?: string
  lastActiveTime: number
  messageCount: number
}

export interface HistoryMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp?: number
}
