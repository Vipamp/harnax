import dayjs from 'dayjs';

/**
 * Precision follows the dimension the rows were read with: `tool` reads the daily aggregate, whose last instant
 * is a DATE cast to DATETIME and always arrives as midnight, while `agent` / `session` read the detail table and
 * carry the call's own instant.
 */
export function formatLastSeen(value?: string, groupBy?: string): string {
  if (!value) return '-';
  const stamp = dayjs(value);
  if (!stamp.isValid()) return '-';
  return groupBy === 'tool' ? stamp.format('YYYY-MM-DD') : stamp.format('YYYY-MM-DD HH:mm:ss');
}
