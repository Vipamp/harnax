import { formatLastSeen } from './lastSeen';

/**
 * The last-call column is fed by two different tables. The tool, MCP and CLI views read
 * `tool_invocation_stats`, whose `lastSeenAt` is `MAX(stat_hour)` — a real hour now, so the minutes are part of
 * the answer and the seconds never are. The agent and session views read `tool_invocation_log` and carry the
 * call's own instant, seconds included. Precision therefore follows the dimension the rows were read with.
 */
describe('formatLastSeen', () => {
  it('reads the aggregate-backed dimensions at the hour', () => {
    expect(formatLastSeen('2026-10-08 14:00:00', 'tool')).toBe('2026-10-08 14:00');
    expect(formatLastSeen('2026-10-08 14:00:00', 'mcp')).toBe('2026-10-08 14:00');
    expect(formatLastSeen('2026-10-08 14:00:00', 'cli')).toBe('2026-10-08 14:00');
  });

  it('keeps the real instant on the detail-backed views', () => {
    expect(formatLastSeen('2026-10-08 14:23:11', 'agent')).toBe('2026-10-08 14:23:11');
    expect(formatLastSeen('2026-10-08 14:23:11', 'session')).toBe('2026-10-08 14:23:11');
  });

  it('answers a missing stamp with a dash rather than Invalid Date', () => {
    expect(formatLastSeen(undefined, 'tool')).toBe('-');
    expect(formatLastSeen('', 'agent')).toBe('-');
    expect(formatLastSeen('not a time', 'tool')).toBe('-');
  });
});
