import type {
  ApiResponse,
  LoginRequest,
  LoginResponse,
  MpLoginResponse,
  CaptchaResponse,
  MpSessionResponse,
  MpCreateSessionRequest,
  MpUpdateSessionRequest,
  MpChatMessageDto,
  MpAgentResponse,
  MpAgentDetailResponse,
  MpUserProfileResponse,
  MpChangePasswordRequest,
} from '../types/api'
import { adminRequest } from './client'

// ========== Auth APIs ==========

export async function mpLogin(request: LoginRequest): Promise<ApiResponse<MpLoginResponse>> {
  return adminRequest<MpLoginResponse>('/api/admin/mp/auth/login', {
    method: 'POST',
    data: request,
  })
}

export async function mpLogout(): Promise<ApiResponse<void>> {
  return adminRequest<void>('/api/admin/mp/auth/logout', {
    method: 'POST',
  })
}

export async function mpGetCaptcha(): Promise<ApiResponse<CaptchaResponse>> {
  return adminRequest<CaptchaResponse>('/api/admin/mp/auth/captcha')
}

// ========== Agent APIs ==========

export async function mpListAgents(): Promise<ApiResponse<MpAgentResponse[]>> {
  return adminRequest<MpAgentResponse[]>('/api/admin/mp/agents')
}

export async function mpGetAgent(agentId: number): Promise<ApiResponse<MpAgentDetailResponse>> {
  return adminRequest<MpAgentDetailResponse>(`/api/admin/mp/agents/${agentId}`)
}

// ========== Session APIs ==========

export async function mpListSessions(): Promise<ApiResponse<MpSessionResponse[]>> {
  return adminRequest<MpSessionResponse[]>('/api/admin/mp/sessions')
}

export async function mpCreateSession(
  request: MpCreateSessionRequest,
): Promise<ApiResponse<MpSessionResponse>> {
  return adminRequest<MpSessionResponse>('/api/admin/mp/sessions', {
    method: 'POST',
    data: request,
  })
}

export async function mpUpdateSession(
  id: number,
  request: MpUpdateSessionRequest,
): Promise<ApiResponse<MpSessionResponse>> {
  return adminRequest<MpSessionResponse>(`/api/admin/mp/sessions/${id}`, {
    method: 'PUT',
    data: request,
  })
}

export async function mpDeleteSession(id: number): Promise<ApiResponse<void>> {
  return adminRequest<void>(`/api/admin/mp/sessions/${id}`, {
    method: 'DELETE',
  })
}

// ========== Chat History APIs ==========

export async function mpGetChatHistory(
  sessionId: number,
): Promise<ApiResponse<MpChatMessageDto[]>> {
  return adminRequest<MpChatMessageDto[]>(`/api/admin/mp/chat/history/${sessionId}`)
}

export async function mpSaveChatMessages(
  sessionId: number,
  messages: MpChatMessageDto[],
): Promise<ApiResponse<void>> {
  return adminRequest<void>(`/api/admin/mp/chat/history/${sessionId}`, {
    method: 'POST',
    data: messages,
  })
}

export async function mpDeleteChatHistory(
  sessionId: number,
): Promise<ApiResponse<void>> {
  return adminRequest<void>(`/api/admin/mp/chat/history/${sessionId}`, {
    method: 'DELETE',
  })
}

// ========== User APIs ==========

export async function mpGetProfile(): Promise<ApiResponse<MpUserProfileResponse>> {
  return adminRequest<MpUserProfileResponse>('/api/admin/mp/user/profile')
}

export async function mpChangePassword(
  request: MpChangePasswordRequest,
): Promise<ApiResponse<void>> {
  return adminRequest<void>('/api/admin/mp/user/password', {
    method: 'PUT',
    data: request,
  })
}
