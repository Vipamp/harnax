import type { ApiResponse } from '../types/api'
import { useConnectionStore } from '../store/useConnectionStore'

interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE' | 'PUT'
  data?: unknown
  headers?: Record<string, string>
  timeout?: number
}

export async function adminRequest<T = unknown>(
  path: string,
  options: RequestOptions = {},
): Promise<ApiResponse<T>> {
  const connection = useConnectionStore()

  if (!connection.adminUrl) {
    throw new Error('Admin URL not configured')
  }

  const url = `${connection.adminUrl.replace(/\/+$/, '')}${path}`
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...options.headers,
  }

  if (connection.token) {
    headers['Authorization'] = `Bearer ${connection.token}`
  }

  return new Promise((resolve, reject) => {
    uni.request({
      url,
      method: options.method || 'GET',
      data: options.data,
      header: headers,
      timeout: options.timeout || 30000,
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data as ApiResponse<T>)
        } else if (res.statusCode === 401) {
          connection.clearAuth()
          reject(new Error('Authentication expired'))
        } else {
          reject(new Error(`HTTP ${res.statusCode}: ${JSON.stringify(res.data)}`))
        }
      },
      fail: (err) => {
        reject(new Error(err.errMsg || 'Request failed'))
      },
    })
  })
}

export async function routerRequest<T = unknown>(
  path: string,
  options: RequestOptions = {},
): Promise<ApiResponse<T>> {
  const connection = useConnectionStore()

  if (!connection.routerUrl) {
    throw new Error('Router URL not configured')
  }

  if (!connection.routerApiKey) {
    throw new Error('Router API Key not configured')
  }

  const url = `${connection.routerUrl.replace(/\/+$/, '')}${path}`
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Api-Key': connection.routerApiKey,
    ...options.headers,
  }

  return new Promise((resolve, reject) => {
    uni.request({
      url,
      method: options.method || 'GET',
      data: options.data,
      header: headers,
      timeout: options.timeout || 30000,
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data as ApiResponse<T>)
        } else if (res.statusCode === 401) {
          connection.clearAuth()
          reject(new Error('Authentication expired'))
        } else {
          reject(new Error(`HTTP ${res.statusCode}: ${JSON.stringify(res.data)}`))
        }
      },
      fail: (err) => {
        reject(new Error(err.errMsg || 'Request failed'))
      },
    })
  })
}
