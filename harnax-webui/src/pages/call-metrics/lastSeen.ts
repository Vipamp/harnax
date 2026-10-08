import dayjs from 'dayjs';
import { readsAggregate } from './dimensions';

/**
 * Precision follows the table the rows were read from: the three aggregate-backed dimensions answer at the
 * hour (`MAX(stat_hour)`, whose seconds never carry information), the two detail-backed ones at the call's own
 * instant, seconds included.
 */
export function formatLastSeen(value?: string, groupBy?: string): string {
  if (!value) return '-';
  const stamp = dayjs(value);
  if (!stamp.isValid()) return '-';
  return readsAggregate(groupBy ?? '') ? stamp.format('YYYY-MM-DD HH:mm') : stamp.format('YYYY-MM-DD HH:mm:ss');
}
