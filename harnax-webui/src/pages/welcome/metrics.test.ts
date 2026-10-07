import { deltaOf, deltaTone, formatCount, formatTokens, rankLabels } from './metrics';

/**
 * 环比的四种边界形状（设计 §4.2）。零基线是新工作区最常见的状态，所以这几条必须钉住：
 * 「昨 0 今 0」是持平，「昨 0 今 >0」是新增，两者都没有百分比 —— 画成 +Infinity% 或 100% 都是假的。
 */
describe('deltaOf', () => {
  it('reads a zero-on-both-sides day as flat, with no percentage to show', () => {
    expect(deltaOf(0, 0)).toEqual({ tone: 'flat', percentText: null });
  });

  it('reads a first day as new, and refuses to invent a percentage out of a zero baseline', () => {
    expect(deltaOf(1, 0)).toEqual({ tone: 'new', percentText: null });
    expect(deltaOf(999_999, 0)).toEqual({ tone: 'new', percentText: null });
    // The shape that used to leak: 1/0 is Infinity, and formatting it would print "Infinity%" or "NaN%".
    const firstDay = deltaOf(1, 0);
    expect(firstDay.percentText).toBeNull();
    expect(`${firstDay.tone}${firstDay.percentText ?? ''}`).not.toMatch(/Infinity|NaN/);
  });

  it('reports the remaining cases as a percentage with one decimal', () => {
    expect(deltaOf(1125, 1000)).toEqual({ tone: 'up', percentText: '12.5%' });
    expect(deltaOf(800, 1000)).toEqual({ tone: 'down', percentText: '20.0%' });
    // One decimal, rounded: a third more than the baseline is 33.3%, not 33.33333%.
    expect(deltaOf(4, 3)).toEqual({ tone: 'up', percentText: '33.3%' });
    expect(deltaOf(2, 3)).toEqual({ tone: 'down', percentText: '33.3%' });
  });

  it('keeps the sign out of the number and lets the tone carry the direction', () => {
    expect(deltaOf(1200, 1000)?.percentText).not.toMatch(/^[+-]/);
    expect(deltaOf(900, 1000)?.percentText).not.toMatch(/^[+-]/);
  });

  it('calls an equal day flat at 0.0% rather than up or down', () => {
    expect(deltaOf(1000, 1000)).toEqual({ tone: 'flat', percentText: '0.0%' });
  });

  it('reports a day that fell to nothing as a 100% drop, not as "new"', () => {
    expect(deltaOf(0, 500)).toEqual({ tone: 'down', percentText: '100.0%' });
  });

  it('calls a difference too small to show as anything but 0.0% flat, in both directions', () => {
    // Counters this large are normal for the token card. Saying "上升 0.0%" on the page would be a sentence
    // that argues with its own number, so the direction follows the rounded value.
    expect(deltaOf(3_200_000, 3_199_000)).toEqual({ tone: 'flat', percentText: '0.0%' });
    expect(deltaOf(3_199_000, 3_200_000)).toEqual({ tone: 'flat', percentText: '0.0%' });
    // The neighbour that does have a visible direction: 0.1% survives the rounding.
    expect(deltaOf(3_203_200, 3_200_000)).toEqual({ tone: 'up', percentText: '0.1%' });
    expect(deltaOf(3_196_800, 3_200_000)).toEqual({ tone: 'down', percentText: '0.1%' });
  });

  it('degrades to flat instead of printing NaN when a number never arrived', () => {
    expect(deltaOf(Number.NaN, Number.NaN)).toEqual({ tone: 'flat', percentText: null });
    expect(deltaOf(Number.POSITIVE_INFINITY, 1)).toEqual({ tone: 'flat', percentText: null });
    expect(deltaOf(5, Number.NaN)).toEqual({ tone: 'flat', percentText: null });
    // A negative baseline is not a measurement either; it must not come back as a negative percentage.
    expect(deltaOf(5, -1)).toEqual({ tone: 'flat', percentText: null });
  });
});

describe('deltaTone', () => {
  it('gives every tone a hex literal, since these get an alpha suffix concatenated onto them', () => {
    const tones = ['up', 'down', 'flat', 'new'] as const;
    for (const tone of tones) {
      const color = deltaTone(tone);
      expect(color).toMatch(/^#[0-9a-fA-F]{6}$/);
      // 'var(--x)' would produce 'var(--x)15', which is not a colour at all.
      expect(color).not.toContain('var(');
    }
    expect(new Set(tones.map(deltaTone)).size).toBe(tones.length);
  });
});

describe('formatCount', () => {
  it('groups thousands', () => {
    expect(formatCount(1234567)).toBe((1234567).toLocaleString());
    expect(formatCount(0)).toBe('0');
  });

  it('rounds a fractional count rather than showing the tail', () => {
    expect(formatCount(1200.6)).toBe((1201).toLocaleString());
  });

  it('turns a missing or unusable number into a plain zero', () => {
    expect(formatCount(undefined)).toBe('0');
    expect(formatCount(null)).toBe('0');
    expect(formatCount(Number.NaN)).toBe('0');
  });
});

describe('formatTokens', () => {
  it('follows the token-monitor rule: M above a million, K above a thousand', () => {
    expect(formatTokens(2_500_000)).toBe('2.50M');
    expect(formatTokens(1_000_000)).toBe('1.00M');
    expect(formatTokens(1_500)).toBe('1.50K');
    expect(formatTokens(1_000)).toBe('1.00K');
    expect(formatTokens(999)).toBe('999');
  });

  it('shows a hard zero for no consumption', () => {
    expect(formatTokens(0)).toBe('0');
    expect(formatTokens(undefined)).toBe('0');
    expect(formatTokens(null)).toBe('0');
    expect(formatTokens(Number.NaN)).toBe('0');
  });
});

/**
 * 榜的标签（设计 §3.2）。榜是按行 id 分组的，同一个显示名合法地出现两次——部署库上就见过两行
 * qwen3.7-flash 挂在两个 provider 下。撞名才补限定词，没撞名不许画蛇添足。
 */
describe('rankLabels', () => {
  it('leaves distinct names exactly as they came back', () => {
    const rows = [
      { name: 'qwen3.7-max', qualifier: '阿里云', tokens: 10 },
      { name: 'gpt-5', qualifier: 'OpenAI', tokens: 5 },
    ];
    expect(rankLabels(rows)).toEqual(['qwen3.7-max', 'gpt-5']);
  });

  it('qualifies the colliding rows and only those', () => {
    const rows = [
      { name: 'qwen3.7-flash', qualifier: '阿里云', tokens: 250_710 },
      { name: 'qwen3.7-flash', qualifier: '内部网关', tokens: 180_000 },
      { name: 'gpt-5', qualifier: 'OpenAI', tokens: 900 },
    ];
    expect(rankLabels(rows)).toEqual(['qwen3.7-flash（阿里云）', 'qwen3.7-flash（内部网关）', 'gpt-5']);
  });

  it('invents no discriminator when the qualifier is missing or blank', () => {
    // 模型行被删时 provider 一并没了，后端这时候给的就是空 qualifier：两行只能长得一样。
    const rows = [{ name: 'qwen3.7-flash', tokens: 10 }, { name: 'qwen3.7-flash', qualifier: '', tokens: 5 }, { name: 'qwen3.7-flash', qualifier: '   ', tokens: 1 }];
    expect(rankLabels(rows)).toEqual(['qwen3.7-flash', 'qwen3.7-flash', 'qwen3.7-flash']);
  });

  it('returns an empty label for a deleted-owner row and keeps it out of the collision count', () => {
    // 空名是归属行已被删除的那笔消耗，「（已删除）」由 RankList 负责渲染；把它们计入撞名会让
    // 一个真实名字莫名带上限定词。
    expect(rankLabels([{ name: '', qualifier: '阿里云', tokens: 6_400 }])).toEqual(['']);
    expect(rankLabels([{ name: '', tokens: 1 }, { name: '', tokens: 2 }])).toEqual(['', '']);
  });

  it('handles an empty or absent list without throwing', () => {
    expect(rankLabels([])).toEqual([]);
    expect(rankLabels(undefined as unknown as API.DashboardRankItem[])).toEqual([]);
  });
});
