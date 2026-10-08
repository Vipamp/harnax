// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * Get router API key from localStorage (stored during login).
 *
 * `buildRouterOptions` lives unexported in both `chat.ts` and `workspace.ts`, so this file keeps its own
 * same-shape private copy rather than editing either of them.
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
 * GET /api/router/agent/session-skills/{sessionId} — the skills this session has enabled in its own zone.
 *
 * Data is an empty list when the session enabled nothing, so "no rows" is an answer rather than a refusal; a
 * session with no running sandbox is refused with its own code and is reported apart from an empty list.
 */
export async function listSessionSkills(sessionId: string, options?: { [key: string]: any }) {
  return request<API.Result<API.SessionSkillRow[]>>(
    `/api/router/agent/session-skills/${encodeURIComponent(sessionId)}`,
    {
      method: 'GET',
      ...buildRouterOptions(options),
    },
  );
}

/**
 * POST /api/router/agent/session-skills/{sessionId}/{name}/enable — one human confirmation makes the draft
 * usable from the next turn of this session.
 *
 * Every refusal comes back as its own code (403 unsafe draft, 409 the session's ten-skill ceiling, 410 no
 * running sandbox, 404 nothing drafted under that name, 500 the container refused the copy), so the caller can
 * tell the human which of them they are looking at instead of showing one generic failure.
 */
export async function enableSessionSkill(
  sessionId: string,
  name: string,
  options?: { [key: string]: any },
) {
  return request<API.Result<API.SessionSkillEnableResult>>(
    `/api/router/agent/session-skills/${encodeURIComponent(sessionId)}/${encodeURIComponent(name)}/enable`,
    {
      method: 'POST',
      ...buildRouterOptions(options),
    },
  );
}
