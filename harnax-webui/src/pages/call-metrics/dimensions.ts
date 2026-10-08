/**
 * The subject dimensions the page can group by, and what each one reads.
 *
 * The split matters in two places: only the two detail-backed dimensions are bounded by the retention window,
 * and only they answer at the call's own instant — the aggregate ones answer at the hour their `stat_hour`
 * fell in. Both read from this list rather than restating it, so the note above the table and the precision of
 * the last-call column cannot drift apart from each other.
 */
const AGGREGATE_DIMENSIONS: string[] = ['tool', 'mcp', 'cli'];

/** Which dimensions each tab offers: the tab's own subject first, then the two views that cross it. */
export const TAB_DIMENSIONS: Record<string, string[]> = {
  tool: ['tool', 'agent', 'session'],
  mcp: ['mcp', 'agent', 'session'],
  cli: ['cli', 'agent', 'session'],
};

export function readsAggregate(groupBy: string): boolean {
  return AGGREGATE_DIMENSIONS.includes(groupBy);
}
