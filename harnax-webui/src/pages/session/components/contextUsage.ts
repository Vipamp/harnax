/**
 * Pure helpers behind the session page's context readout and compaction entry.
 *
 * Kept out of the components so the three judgements that actually matter — when a reading is a reading,
 * how the percentage is rounded for one narrow header slot, and whether a compaction command really
 * changed anything — are testable without rendering.
 */

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

/** The readout says where its numerator came from; the billed count wins whenever the router has one. */
export function contextUsageBasis(usage: API.ContextUsage): 'billed' | 'estimated' {
  return usage.lastCallInputTokens == null ? 'estimated' : 'billed';
}

/**
 * Auto-compaction fires once the numerator reaches `triggerTokens`, so the readout is coloured accordingly —
 * a context at that point will be compacted by the next turn whether or not anyone asks.
 */
export function isAtAutoTrigger(usage: API.ContextUsage): boolean {
  const numerator = usage.lastCallInputTokens ?? usage.estimatedTokens;
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
