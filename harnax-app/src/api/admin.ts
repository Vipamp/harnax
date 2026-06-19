import type {
  ApiResponse,
  LoginRequest,
  LoginResponse,
  CaptchaResponse,
  MpSessionResponse,
  MpCreateSessionRequest,
  MpUpdateSessionRequest,
  MpChatMessageDto,
} from '../types/api'
import { adminRequest } from './client'

// ========== Auth APIs ==========

export async function mpLogin(request: LoginRequest): Promise<ApiResponse<LoginResponse>> {
  return adminRequest<LoginResponse>('/api/mp/auth/login', {
    method: 'POST',
    data: request,
  })
}

export async function mpLogout(): Promise<ApiResponse<void>> {
  return adminRequest<void>('/api/mp/auth/logout', {
    method: 'POST',
  })
}

export async function mpGetCaptcha(): Promise<ApiResponse<CaptchaResponse>> {
  return adminRequest<CaptchaResponse>('/api/mp/auth/captcha')
}

// ========== Session APIs ==========

export async function mpListSessions(): Promise<ApiResponse<MpSessionResponse[]>> {
  return adminRequest<MpSessionResponse[]>('/api/mp/sessions')
}

export async function mpCreateSession(
  request: MpCreateSessionRequest,
): Promise<ApiResponse<MpSessionResponse>> {
  return adminRequest<MpSessionResponse>('/api/mp/sessions', {
    method: 'POST',
    data: request,
  })
}

export async function mpUpdateSession(
  id: number,
  request: MpUpdateSessionRequest,
): Promise<ApiResponse<MpSessionResponse>> {
  return adminRequest<MpSessionResponse>(`/api/mp/sessions/${id}`, {
    method: 'PUT',
    data: request,
  })
}

export async function mpDeleteSession(id: number): Promise<ApiResponse<void>> {
  return adminRequest<void>(`/api/mp/sessions/${id}`, {
    method: 'DELETE',
  })
}

// ========== Chat History APIs ==========

export async function mpGetChatHistory(
  sessionId: number,
): Promise<ApiResponse<MpChatMessageDto[]>> {
  return adminRequest<MpChatMessageDto[]>(`/api/mp/chat/history/${sessionId}`)
}

export async function mpSaveChatMessages(
  sessionId: number,
  messages: MpChatMessageDto[],
): Promise<ApiResponse<void>> {
  return adminRequest<void>(`/api/mp/chat/history/${sessionId}`, {
    method: 'POST',
    data: messages,
  })
}

export async function mpDeleteChatHistory(
  sessionId: number,
): Promise<ApiResponse<void>> {
  return adminRequest<void>(`/api/mp/chat/history/${sessionId}`, {
    method: 'DELETE',
  })
}
