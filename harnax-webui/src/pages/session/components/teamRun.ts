/**
 * Team member run state shared by the live stream and the history replay.
 *
 * A member speaks on the lead's SSE channel stamped with its own `source`, and on reload its
 * conversation comes back from a child session (`team-<root>-m<agentId>`) merged into the same history
 * list. Both paths have to agree on what a member bubble is and when it is finished, so that contract
 * lives here instead of inside the 3000-line chat component.
 */

/** Mirrors the backend `EventSource`: which team member run produced an event or a message. */
export interface TeamEventSource {
  teamId: number;
  teamName: string;
  memberAgentId: number;
  memberAgentName: string;
  childRunId: string;
  childSessionId: string;
}

export type MemberRunStatus = 'running' | 'awaiting_confirm' | 'done' | 'failed';

/** Lifecycle of one member bubble, shown in its folded summary line. */
export interface MemberRunInfo {
  status: MemberRunStatus;
  startedAt: number;
  endedAt?: number;
  toolCount: number;
  /** The task the lead delegated, taken from the `team_delegate` call arguments. */
  task?: string;
}

/** The only lead tool that starts a member run; its result is what ends one. */
export const TEAM_DELEGATE_TOOL = 'team_delegate';

/** A run nobody has closed yet is still working — that is what keeps a bubble expanded. */
export function isRunOpen(status: MemberRunStatus): boolean {
  return status === 'running' || status === 'awaiting_confirm';
}

/** Duration in a form short enough for one summary line; under a minute is the common case. */
export function formatRunDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '';
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`;
}

/**
 * First meaningful line of a delegated task, cut for one summary row.
 *
 * Cut by character rather than by measured width on purpose: this line is a hint, the full task stays
 * in the lead's own `team_delegate` card, and an exact-width cut would need a font metric to be right.
 */
export function firstTaskLine(task: string | undefined, maxChars = 48): string {
  if (!task) return '';
  const line = task
    .split('\n')
    .map((l) => l.trim())
    .find((l) => l.length > 0);
  if (!line) return '';
  return line.length > maxChars ? `${line.slice(0, maxChars)}…` : line;
}

/**
 * `team_delegate` arguments carry the member id and the task the frontend joins a run by.
 *
 * Both paths hand this the same payload: the live `CallToolEvent.arguments` and, on reload, the lead's
 * persisted `toolUseLog.input`. The wire sends the id as a string and the model sometimes quotes it, so
 * this is the one place that tolerates that rather than scattering `Number(...)` over three SSE loops.
 */
export function parseDelegateCall(args: unknown): { memberAgentId: number; task?: string } | null {
  if (!args || typeof args !== 'object') return null;
  const raw = args as Record<string, unknown>;
  const idValue = raw.member_agent_id;
  if (idValue === undefined || idValue === null) return null;
  const memberAgentId = Number(String(idValue).replace(/["'\s]/g, ''));
  if (!Number.isFinite(memberAgentId)) return null;
  const task = raw.task;
  return { memberAgentId, task: typeof task === 'string' ? task : undefined };
}

/** One persisted history log, only as far as the team replay reads it. */
export interface HistoryLog {
  role?: string;
  toolUseLog?: Array<{ name?: string; input?: unknown }>;
}

/**
 * Tasks this lead turn delegated, in call order.
 *
 * A member's own logs carry only what it produced; the task it was given lives in the lead's
 * `team_delegate` call, which is the same join the live stream uses. Delegation is blocking and the
 * call is recorded before the member runs, so reading it off the lead's turn as the replay scans puts
 * every bubble next to its own task — a member that was delegated twice simply overwrites its entry.
 */
export function delegatedTasksOf(log: HistoryLog): Array<{ memberAgentId: number; task: string }> {
  if (log.role !== 'ASSISTANT') return [];
  const calls: Array<{ memberAgentId: number; task: string }> = [];
  for (const tool of log.toolUseLog || []) {
    if (tool.name !== TEAM_DELEGATE_TOOL) continue;
    const call = parseDelegateCall(tool.input);
    if (call?.task) calls.push({ memberAgentId: call.memberAgentId, task: call.task });
  }
  return calls;
}

/** Whatever the render layer needs from a lead tool card to nest a member run inside it. */
export interface DelegateCard {
  type: string;
  toolName?: string;
  toolId?: string;
  content?: string;
}

/** The member a `team_delegate` card delegated, read back from its arguments as persisted on the segment. */
function delegateMemberOf(argsJson: string | undefined): number | null {
  if (!argsJson) return null;
  try {
    return parseDelegateCall(JSON.parse(argsJson))?.memberAgentId ?? null;
  } catch {
    return null;
  }
}

/**
 * Claim the card this member run belongs to, in call order.
 *
 * A run and a card are the same delegation, but nothing on the wire links them: `childRunId` names the
 * member's child session (and is reused by the next delegation to that member), while the card is one
 * call in the lead's turn. Delegation blocks the lead, so the earliest card of this member that no run
 * has taken yet is the right one — the same order both the live stream and the replay produce.
 */
export function claimDelegateCard(
  segments: DelegateCard[],
  memberAgentId: number,
  claimed: Set<string>,
): string | undefined {
  for (const seg of segments) {
    if (seg.type !== 'tool_call' || seg.toolName !== TEAM_DELEGATE_TOOL || !seg.toolId) continue;
    if (claimed.has(seg.toolId)) continue;
    if (delegateMemberOf(seg.content) !== memberAgentId) continue;
    claimed.add(seg.toolId);
    return seg.toolId;
  }
  return undefined;
}
