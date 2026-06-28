// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * Get router API key from localStorage (stored during login)
 */
function getRouterApiKey(): string {
  try {
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    if (tokenInfoStr) {
      const tokenInfo = JSON.parse(tokenInfoStr);
      return tokenInfo.routerApiKey || '';
    }
  } catch {
    // ignore
  }
  return '';
}

/**
 * Build router request options: X-Api-Key header + skip JWT Authorization
 */
function buildRouterOptions(extraOptions?: { [key: string]: any }) {
  const apiKey = getRouterApiKey();
  return {
    skipAuthorization: true,
    headers: {
      'X-Api-Key': apiKey,
    },
    ...(extraOptions || {}),
  };
}

/**
 * List workspace files for a session sandbox
 */
export async function listWorkspaceFiles(
  sessionId: string,
  path: string = '/workspace',
  options?: { [key: string]: any },
) {
  return request<API.Result<API.WorkspaceFile[]>>(
    `/api/router/agent/workspace/${sessionId}/files`,
    {
      method: 'GET',
      params: { path },
      ...buildRouterOptions(options),
    },
  );
}

/**
 * Read file content from workspace
 */
export async function readWorkspaceFile(
  sessionId: string,
  path: string,
  options?: { [key: string]: any },
) {
  return request<API.Result<API.WorkspaceFileContent>>(
    `/api/router/agent/workspace/${sessionId}/read`,
    {
      method: 'GET',
      params: { path },
      ...buildRouterOptions(options),
    },
  );
}

/**
 * Get sandbox status for a session
 */
export async function getWorkspaceStatus(
  sessionId: string,
  options?: { [key: string]: any },
) {
  // Call the batch endpoint with single session ID
  const result = await request<API.Result<Record<string, { active: boolean; containerName?: string; image?: string }>>>(
    `/api/router/agent/workspace/status`,
    {
      method: 'GET',
      params: { sessionIds: sessionId },
      ...buildRouterOptions(options),
    },
  );
  // Extract single session result from batch response
  if (result?.data?.[sessionId]) {
    return { ...result, data: result.data[sessionId] };
  }
  return result;
}

/**
 * Batch get sandbox status for multiple sessions
 * Returns a map of sessionId -> { active: boolean, ... }
 */
export async function batchGetWorkspaceStatus(
  sessionIds: string[],
  options?: { [key: string]: any },
) {
  return request<API.Result<Record<string, { active: boolean; containerName?: string; image?: string }>>>(
    `/api/router/agent/workspace/status`,
    {
      method: 'GET',
      params: { sessionIds: sessionIds.join(',') },
      ...buildRouterOptions(options),
    },
  );
}

/**
 * Upload a file to workspace
 */
export async function uploadWorkspaceFile(
  sessionId: string,
  file: File,
  path: string = '/workspace',
  options?: { [key: string]: any },
) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('path', path);

  const apiKey = getRouterApiKey();
  return request<API.Result<{ fileName: string; path: string; size: number }>>(
    `/api/router/agent/workspace/${sessionId}/upload`,
    {
      method: 'POST',
      data: formData,
      headers: {
        'X-Api-Key': apiKey,
      },
      skipAuthorization: true,
      ...(options || {}),
    },
  );
}

/**
 * Get download URL for a workspace file
 * Can be used with window.open() or <a> tag
 */
export function getWorkspaceFileDownloadUrl(
  sessionId: string,
  path: string,
): string {
  const apiKey = getRouterApiKey();
  const encodedPath = encodeURIComponent(path);
  return `/api/router/agent/workspace/${sessionId}/download?path=${encodedPath}&X-Api-Key=${apiKey}`;
}

/**
 * Download a file from workspace (programmatic download)
 */
export async function downloadWorkspaceFile(
  sessionId: string,
  path: string,
): Promise<Blob> {
  const apiKey = getRouterApiKey();
  const encodedPath = encodeURIComponent(path);

  const response = await fetch(
    `/api/router/agent/workspace/${sessionId}/download?path=${encodedPath}`,
    {
      method: 'GET',
      headers: {
        'X-Api-Key': apiKey,
      },
    },
  );

  if (!response.ok) {
    throw new Error(`Download failed: ${response.status} ${response.statusText}`);
  }

  return response.blob();
}
