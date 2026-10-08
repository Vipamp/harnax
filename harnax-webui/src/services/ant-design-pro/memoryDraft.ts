// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/**
 * GET /api/admin/memory-drafts — the queue of memory merges waiting for their owner.
 *
 * `status` defaults to PENDING server-side, so a caller that wants decided rows has to ask for them. Rows
 * carry neither text: a candidate is a whole rewrite of one file, and two documents are not comparable in a
 * table cell, so the queue is a work list and the detail screen is where the decision gets made.
 */
export async function pageMemoryDrafts(
  params: {
    pageNum?: number;
    pageSize?: number;
    status?: API.MemoryDraftStatus;
    agentName?: string;
    sessionId?: string;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.MemoryDraftPage>>('/api/admin/memory-drafts', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/**
 * GET /api/admin/memory-drafts/{id} — both texts plus the digest an approval has to send back.
 *
 * `baseMd` is absent when the agent had no long-term layer yet, which is the common case for a first merge
 * and not an error.
 */
export async function getMemoryDraft(id: number, options?: { [key: string]: any }) {
  return request<API.Result<API.MemoryDraftDetail>>(`/api/admin/memory-drafts/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * POST /api/admin/memory-drafts/{id}/approve.
 *
 * Actionable refusals come back on a 200 envelope inside `data.outcome`, not as an error code — that is where
 * the new digest and the version the layer moved to live, and the screen needs them to say what to do next.
 */
export async function approveMemoryDraft(
  id: number,
  body: API.MemoryDraftApproveRequest,
  options?: { [key: string]: any },
) {
  return request<API.Result<API.MemoryDraftDecision>>(`/api/admin/memory-drafts/${id}/approve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}

/**
 * POST /api/admin/memory-drafts/{id}/reject — the reason is required.
 *
 * Rejecting closes the candidate and leaves both layers alone, so the same material comes back next merge
 * window. The reason is the only thing that carries across that round trip.
 */
export async function rejectMemoryDraft(
  id: number,
  body: API.MemoryDraftRejectRequest,
  options?: { [key: string]: any },
) {
  return request<API.Result<API.MemoryDraftDecision>>(`/api/admin/memory-drafts/${id}/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}
