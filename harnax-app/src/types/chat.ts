export interface TokenUsage {
  inputTokens: number
  outputTokens: number
  totalTokens: number
  costTime: number
  timestamp: number
}

export interface PendingCallTool {
  toolId: string
  toolName: string
  arguments?: Record<string, unknown>
  isDangerous?: boolean
}

export interface ThinkingEvent {
  eventType: 'ThinkingEvent'
  message: string
  isLast: boolean
  tokenUsage?: TokenUsage
}

export interface TextEvent {
  eventType: 'TextEvent'
  message: string
  isLast: boolean
  tokenUsage?: TokenUsage
}

export interface CallToolEvent {
  eventType: 'CallToolEvent'
  toolId: string
  toolName: string
  arguments: Record<string, unknown>
  tokenUsage?: TokenUsage
}

export interface ToolResultEvent {
  eventType: 'ToolResultEvent'
  toolId: string
  toolName: string
  message: string
  success: boolean
  tokenUsage?: TokenUsage
}

export interface ToolConfirmEvent {
  eventType: 'ToolConfirmEvent'
  pendingCallTools: PendingCallTool[]
}

export interface ErrorEvent {
  eventType: 'ErrorEvent'
  code: number
  message: string
}

export interface EndEvent {
  eventType: 'EndEvent'
}

export type ChatEvent =
  | ThinkingEvent
  | TextEvent
  | CallToolEvent
  | ToolResultEvent
  | ToolConfirmEvent
  | ErrorEvent
  | EndEvent

export interface MessageSegment {
  type: 'text' | 'thinking' | 'tool_call' | 'tool_result' | 'tool_confirm'
  content: string
  toolName?: string
  toolId?: string
  confirmStatus?: 'pending' | 'confirmed' | 'rejected'
  pendingCallTools?: PendingCallTool[]
  toolResult?: string
  success?: boolean
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  segments: MessageSegment[]
  timestamp: number
  imageUrls?: string[]
  tokenUsage?: TokenUsage
}
