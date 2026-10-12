import dayjs from 'dayjs';
import { formatSessionTime, groupSessions } from './listModel';

const NOW = dayjs('2026-10-12T15:30:00');

describe('formatSessionTime', () => {
  it('shows HH:mm for a timestamp earlier today', () => {
    expect(formatSessionTime('2026-10-12T09:05:00', NOW)).toBe('09:05');
  });

  it('shows MM-DD for a timestamp earlier this year', () => {
    expect(formatSessionTime('2026-03-04T10:00:00', NOW)).toBe('03-04');
  });

  it('shows YYYY-MM-DD for a timestamp in another year', () => {
    expect(formatSessionTime('2025-12-31T23:59:00', NOW)).toBe('2025-12-31');
  });

  it('returns null for empty and unparseable values', () => {
    expect(formatSessionTime('', NOW)).toBeNull();
    expect(formatSessionTime('not-a-date', NOW)).toBeNull();
  });
});

describe('groupSessions', () => {
  it('splits into today first, earlier second, preserving input order', () => {
    const items = [
      { id: 'a', updateTime: '2026-10-12T09:00:00' },
      { id: 'b', updateTime: '2026-10-05T09:00:00' },
      { id: 'c', updateTime: '2026-10-12T11:00:00' },
    ];
    expect(groupSessions(items, NOW)).toEqual([
      { key: 'today', items: [items[0], items[2]] },
      { key: 'earlier', items: [items[1]] },
    ]);
  });

  it('treats a missing or unparseable timestamp as earlier', () => {
    const items = [
      { id: 'a' },
      { id: 'b', updateTime: 'garbage' },
      { id: 'c', updateTime: '2026-10-12T08:00:00' },
    ];
    expect(groupSessions(items, NOW)).toEqual([
      { key: 'today', items: [items[2]] },
      { key: 'earlier', items: [items[0], items[1]] },
    ]);
  });

  it('omits empty groups and returns nothing for an empty list', () => {
    expect(groupSessions([], NOW)).toEqual([]);
    const onlyEarlier = [{ id: 'a', updateTime: '2026-01-01T00:00:00' }];
    expect(groupSessions(onlyEarlier, NOW)).toEqual([{ key: 'earlier', items: onlyEarlier }]);
  });

  it('keeps a previous-day 23:59 timestamp out of today', () => {
    const items = [{ id: 'a', updateTime: '2026-10-11T23:59:00' }];
    expect(groupSessions(items, NOW)).toEqual([{ key: 'earlier', items }]);
  });
});
