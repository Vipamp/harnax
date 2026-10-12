import dayjs from 'dayjs';

export interface SessionGroup<T> {
  key: 'today' | 'earlier';
  items: T[];
}

/** 今天内 HH:mm、今年内 MM-DD、跨年 YYYY-MM-DD；空值与不可解析返回 null（列表行据此整段省略时间）。 */
export function formatSessionTime(value: string, now: dayjs.Dayjs): string | null {
  if (!value) return null;
  const t = dayjs(value);
  if (!t.isValid()) return null;
  if (t.isSame(now, 'day')) return t.format('HH:mm');
  if (t.isSame(now, 'year')) return t.format('MM-DD');
  return t.format('YYYY-MM-DD');
}

/** 今天/更早两组；缺失或解析失败的 updateTime 归「更早」；组内保序、空组省略。 */
export function groupSessions<T extends { updateTime?: string }>(
  items: T[],
  now: dayjs.Dayjs,
): SessionGroup<T>[] {
  const today: T[] = [];
  const earlier: T[] = [];
  for (const item of items) {
    const t = item.updateTime ? dayjs(item.updateTime) : null;
    if (t?.isValid() && t.isSame(now, 'day')) {
      today.push(item);
    } else {
      earlier.push(item);
    }
  }
  const groups: SessionGroup<T>[] = [];
  if (today.length) groups.push({ key: 'today', items: today });
  if (earlier.length) groups.push({ key: 'earlier', items: earlier });
  return groups;
}
