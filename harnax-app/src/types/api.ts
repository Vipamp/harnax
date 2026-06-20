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

export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data?: T
  timestamp: number
}

export interface LoginRequest {
  username: string
  password?: string
  captcha?: string
  captchaKey?: string
}

export interface MpLoginUserInfo {
  userId: number
  username: string
  nickname?: string
}

export interface MpLoginResponse {
  accessToken: string
  routerApiKey: string
  routerUrl: string
  expiresIn: number
  userInfo: MpLoginUserInfo
}

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

export interface LoginResponse {
  accessToken?: string
  tokenType?: string
  expiresIn?: number
  expiresAt?: number
  userInfo?: UserInfo
}

export interface CaptchaResponse {
  imageBase64?: string
  captchaKey?: string
  expiresIn?: number
}

export interface MpSessionResponse {
  id: number
  sessionName: string
  routerSessionId: string
  agentId: number
  agentName: string
  status: number
  messageCount: number
  lastMessage?: string
  createTime: string
  updateTime: string
}

export interface MpCreateSessionRequest {
  sessionName: string
  agentId: number
}

export interface MpUpdateSessionRequest {
  sessionName: string
}

export interface MpChatMessageDto {
  role: 'user' | 'assistant' | 'system'
  content: string
  segmentsJson?: string
  tokenUsageJson?: string
  imageUrlsJson?: string
}

export interface MpAgentResponse {
  id: number
  name: string
  description: string
  modelName: string
  status: number
  sessionCount: number
}

export interface MpAgentDetailResponse {
  id: number
  name: string
  description: string
  modelName: string
  modelProvider: string
  mcpList: MpAgentMcpInfo[]
  skillList: MpAgentSkillInfo[]
  enableThink: boolean
  enableSearch: boolean
  enablePlan: boolean
}

export interface MpAgentMcpInfo {
  id: number
  name: string
  description: string
}

export interface MpAgentSkillInfo {
  id: number
  name: string
  description: string
}

export interface MpUserProfileResponse {
  userId: number
  username: string
  nickname?: string
  avatar?: string
  email?: string
  phone?: string
  createTime?: string
}

export interface MpChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

export interface ConnectionConfig {
  adminUrl: string
  routerUrl: string
  routerApiKey: string
  token: string
  username: string
  userId: number | null
  nickname: string
}
