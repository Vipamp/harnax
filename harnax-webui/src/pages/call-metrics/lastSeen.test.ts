import { formatLastSeen } from './lastSeen';

/**
 * The last-call column is fed by two different tables. The per-tool view reads `tool_invocation_stats`, whose
 * column is `CAST(MAX(stat_date) AS DATETIME)` - a DATE, so the server always sends midnight. The agent and
 * session views read `tool_invocation_log` and carry a real instant. Showing seconds on the first view claims a
 * call happened at 00:00:00, so precision follows the dimension the rows were read with.
 */
describe('formatLastSeen', () => {
  it('drops the fabricated midnight seconds from the aggregate view', () => {
    expect(formatLastSeen('2026-10-08 00:00:00', 'tool')).toBe('2026-10-08');
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
