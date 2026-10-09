/**
 * Pure helpers behind the session page's context readout and compaction entry.
 *
 * Kept out of the components so the four judgements that actually matter — when a reading is a reading,
 * how the percentage is rounded for one narrow header slot, how a token count is abbreviated, and whether a
 * compaction command really changed anything — are testable without rendering.
 */

import { formatTokenCount } from '@/utils/tokenFormat';

/** Envelope as it arrives from the router: business failures come back as a code with a null data. */
export type ContextUsageResponseLike = {
  code?: number;
  data?: API.ContextUsage | null;
};

/**
 * A readout exists only when the endpoint returned a payload with a usable denominator. Both
 * "no instance holds this session" and "never bound to an instance" answer without data, and neither of
 * them means the context is empty — the caller must hide the readout rather than show zero.
 */
export function isContextUsageReadable(res?: ContextUsageResponseLike | null): boolean {
  const usage = res?.data;
  if (!usage) return false;
  return Number.isFinite(usage.ratio) && usage.contextWindow > 0;
}

/**
 * Whether the billed count describes the context the readout is measuring.
 *
 * An on-demand compaction rewrites the context and bills nothing of its own, so the newest bill is then the
 * price of a request that no longer exists, and the server says so with `billIsCurrent: false` until the next
 * real call outnumbers it. An answer that carries no such flag has voided nothing.
 */
function hasUsableBill(usage: API.ContextUsage): boolean {
  return usage.lastCallInputTokens != null && usage.billIsCurrent !== false;
}

/** The readout says where its numerator came from; the billed count wins while it still stands. */
export function contextUsageBasis(usage: API.ContextUsage): 'billed' | 'estimated' {
  return hasUsableBill(usage) ? 'billed' : 'estimated';
}

/**
 * The count `ratio` was taken over: the last billed call while that bill stands, the upstream estimate
 * otherwise — which is also what it is until the session has a billed call at all.
 */
export function contextUsageNumerator(usage: API.ContextUsage): number {
  return hasUsableBill(usage) ? (usage.lastCallInputTokens ?? usage.estimatedTokens) : usage.estimatedTokens;
}

/**
 * The readout's token rows speak the token pages' abbreviation, so a 200000 window does not land in the
 * tooltip as six ungrouped digits. Absent stays absent — `formatTokenCount` answers `0` for a number that is
 * not there, and a session with no bill yet has no bill to show, not a free one.
 */
export function contextUsageTokenText(value: number | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  return formatTokenCount(value);
}

/**
 * Auto-compaction fires once the numerator reaches `triggerTokens`, so the readout is coloured accordingly —
 * a context at that point will be compacted by the next turn whether or not anyone asks.
 */
export function isAtAutoTrigger(usage: API.ContextUsage): boolean {
  const numerator = contextUsageNumerator(usage);
  const trigger = usage.triggerTokens;
  return typeof trigger === 'number' && trigger > 0 && numerator >= trigger;
}

/** One header slot wide, so the number of decimals follows the magnitude instead of a fixed format. */
export function formatContextPercent(ratio: number): string {
  if (!Number.isFinite(ratio) || ratio <= 0) return '0%';
  const percent = ratio * 100;
  if (percent >= 10) return `${Math.round(percent)}%`;
  if (percent >= 1) return `${percent.toFixed(1)}%`;
  return `${Number(percent.toFixed(2))}%`;
}

/**
 * Below this share of the window, the session has nothing a compaction would be worth removing. The command
 * answers the same way, but only after a round trip — so the button asks this question of the local reading first.
 */
export const COMPACT_SKIP_RATIO = 0.1;

/**
 * Whether holding the command back is the honest answer. A session the router gave no reading for is not a
 * session with an empty context, and without a reading there is nothing to refuse on.
 */
export function isCompactionPointless(usage?: API.ContextUsage | null): boolean {
  return !!usage && Number.isFinite(usage.ratio) && usage.ratio < COMPACT_SKIP_RATIO;
}

/** Payload of one `/command` reply, narrowed to what the compaction branch needs. */
export type CommandReplyLike = {
  success?: boolean;
  message?: string;
  result?: { beforeMessages?: number; afterMessages?: number } | null;
};

/**
 * A compaction that reports success without having removed anything is the documented "too short to keep a
 * tail" outcome, not a completed compaction — announcing it as one would be a false report to the user.
 */
export function compactionOutcome(
  reply?: CommandReplyLike | null,
): 'done' | 'noop' | 'failed' {
  if (!reply || reply.success !== true) return 'failed';
  const { beforeMessages: before, afterMessages: after } = reply.result || {};
  if (typeof before !== 'number' || typeof after !== 'number') return 'done';
  return before === after ? 'noop' : 'done';
}
