// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * GET /api/admin/skill-drafts — the review queue.
 *
 * `status` defaults to PENDING server-side, so the caller has to send it explicitly to see decided rows.
 * `sessionId` narrows the queue to the one conversation that drafted them, which is what the session page's
 * own panel shows.
 */
export async function pageSkillDrafts(
  params: {
    pageNum?: number;
    pageSize?: number;
    status?: API.SkillDraftStatus;
    name?: string;
    sessionId?: string;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.SkillDraftPage>>('/api/admin/skill-drafts', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** GET /api/admin/skill-drafts/{id} — full content plus the digest an approval has to send back */
export async function getSkillDraft(id: number, options?: { [key: string]: any }) {
  return request<API.Result<API.SkillDraftDetail>>(`/api/admin/skill-drafts/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * POST /api/admin/skill-drafts/{id}/approve.
 *
 * Actionable refusals come back on a 200 envelope inside `data.outcome`, not as an error code — that is
 * where `currentDigest` and the previous decision's author live.
 */
export async function approveSkillDraft(
  id: number,
  body: API.SkillDraftApproveRequest,
  options?: { [key: string]: any },
) {
  return request<API.Result<API.SkillDraftDecision>>(`/api/admin/skill-drafts/${id}/approve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}

/** POST /api/admin/skill-drafts/{id}/reject — the reason is required and is what the agent is told */
export async function rejectSkillDraft(
  id: number,
  body: API.SkillDraftRejectRequest,
  options?: { [key: string]: any },
) {
  return request<API.Result<API.SkillDraftDecision>>(`/api/admin/skill-drafts/${id}/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}
