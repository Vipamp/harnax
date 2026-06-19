// ========== Router API Types (AI 对话) ==========

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

// ========== Admin API Types (MP 接口) ==========

/** 统一响应格式，匹配后端 ResultVo */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data?: T
  timestamp: number
}

/** 登录请求 */
export interface LoginRequest {
  username: string
  password?: string
  captcha?: string
  captchaKey?: string
}

/** 用户信息 */
export interface UserInfo {
  userId: number
  username: string
  nickname?: string
  avatar?: string
  email?: string
  phone?: string
  gender?: number
  isAdmin?: number
}

/** 登录响应 */
export interface LoginResponse {
  accessToken?: string
  tokenType?: string
  expiresIn?: number
  expiresAt?: number
  userInfo?: UserInfo
}

/** 验证码响应 */
export interface CaptchaResponse {
  imageBase64?: string
  captchaKey?: string
  expiresIn?: number
}

/** MP 会话响应 */
export interface MpSessionResponse {
  id: number
  sessionName: string
  routerSessionId: string
  status: number
  messageCount: number
  lastMessage?: string
  createTime: string
  updateTime: string
}

/** MP 创建会话请求 */
export interface MpCreateSessionRequest {
  sessionName: string
  routerSessionId?: string
}

/** MP 更新会话请求 */
export interface MpUpdateSessionRequest {
  sessionName: string
}

/** MP 聊天消息 DTO */
export interface MpChatMessageDto {
  role: 'user' | 'assistant' | 'system'
  content: string
  segmentsJson?: string
  tokenUsageJson?: string
  imageUrlsJson?: string
}

/** 连接配置 */
export interface ConnectionConfig {
  adminUrl: string
  routerUrl: string
  apiKey: string
  token: string
  username: string
}
