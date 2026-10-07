/**
 * 首页总览的数字口径。全部是纯函数：卡片、榜、趋势三处共用同一套格式化，
 * 环比这条容易写错的边界规则也只有一个地方能改（设计 §4.2）。
 */

/** 千分位。后端给的是 Long，JSON 里就是数字；非有限值一律当 0 显示，不把 NaN 推到页面上。 */
export function formatCount(value: number | undefined | null): string {
  if (value === undefined || value === null || !Number.isFinite(value)) {
    return '0';
  }
  return Math.round(value).toLocaleString();
}

/**
 * Token 的 M/K 口径，与 token-monitor 页面的 formatToken 同一条规则，抽出来与卡片共用（设计 §4.6）。
 * 1e6 走 M、1e3 走 K，各留两位小数；一千以下没有千分位可分，直接给整数。
 */
export function formatTokens(value: number | undefined | null): string {
  if (value === undefined || value === null || !Number.isFinite(value) || value === 0) {
    return '0';
  }
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(2)}M`;
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(2)}K`;
  }
  return Math.round(value).toLocaleString();
}

/** 环比的说法，四种：涨、跌、持平、新增。页面文案由 i18n 按这个枚举取（设计 §4.5）。 */
export type DeltaTone = 'up' | 'down' | 'flat' | 'new';

export type Delta = {
  tone: DeltaTone;
  /**
   * 绝对值百分比，一位小数，例如 '12.5%'。
   * 基线为 0 时是 null：那一刻没有百分比可说，画成 100% 或 Infinity% 都是假的。
   */
  percentText: string | null;
};

/**
 * 今日至今 对 昨日同时段 的差值（设计 §4.2 的四种边界形状）：
 * - 昨 0 且今 0 → 持平，无百分比
 * - 昨 0 且今 > 0 → 新增，无百分比（不是 +Infinity%，也不是 100%）
 * - 其余按 (今 - 昨) / 昨 的百分比，一位小数；方向跟的是这个四舍五入之后的数，
 *   所以抹到 0.0% 的差值是持平，不是「上升 0.0%」
 * - 任一输入不是有限数（后端字段缺失、脏数据）→ 持平且无百分比，宁可不说也不说错
 * - 任一输入为负（计数不可能是负的，出现即说明上游出错）→ 同上；负的昨值会把涨幅除成跌幅
 */
export function deltaOf(current: number, previous: number): Delta {
  // An input that is not a finite number is not a measurement; saying nothing beats printing "NaN%".
  if (!Number.isFinite(current) || !Number.isFinite(previous)) {
    return { tone: 'flat', percentText: null };
  }
  if (current < 0 || previous < 0) {
    return { tone: 'flat', percentText: null };
  }

  if (previous === 0) {
    if (current === 0) {
      return { tone: 'flat', percentText: null };
    }
    return { tone: 'new', percentText: null };
  }

  // The tone follows the rounded percentage, not the raw one: today's tokens against yesterday's can differ
  // by one part in ten thousand, and "上升 0.0%" is a sentence that contradicts itself on the page.
  const percentText = `${Math.abs(((current - previous) / previous) * 100).toFixed(1)}%`;
  if (percentText === '0.0%') {
    return { tone: 'flat', percentText };
  }
  if (current > previous) {
    return { tone: 'up', percentText };
  }
  return { tone: 'down', percentText };
}

/**
 * 环比一行的颜色。必须写十六进制字面量：这里会拼透明度后缀（`#10b98115`），
 * CSS 变量拼上去无效（EntityCard 踩过）。
 */
export function deltaTone(tone: DeltaTone): string {
  switch (tone) {
    case 'up':
      return '#10b981';
    case 'down':
      return '#f59e0b';
    case 'new':
      return '#0ea5e9';
    default:
      return '#94a3b8';
  }
}

/**
 * 榜里每一行的展示名。
 *
 * 榜是按行 id 分组的，所以同一个名字可以合法地出现两次——一个租户把同一个模型名挂在两个 provider
 * 下就是两行。两行一模一样的标签读起来像渲染重复了，所以只给真正撞名的那几行补上限定词。
 *
 * - 空名返回空串：那是归属行已删除的消耗，由调用方渲染「（已删除）」
 * - 名字在本榜出现多于一次，且这一行带得上限定词 → `名字（限定词）`
 * - 限定词缺失或为空 → 原样返回，不编造区分度（模型行被删时 provider 一并没了，就是这种）
 */
export function rankLabels(items: API.DashboardRankItem[]): string[] {
  const rows = items ?? [];
  const counts = new Map<string, number>();
  rows.forEach((item) => {
    if (item.name) {
      counts.set(item.name, (counts.get(item.name) ?? 0) + 1);
    }
  });

  return rows.map((item) => {
    if (!item.name) {
      return '';
    }
    const qualifier = item.qualifier?.trim();
    if ((counts.get(item.name) ?? 0) > 1 && qualifier) {
      return `${item.name}（${qualifier}）`;
    }
    return item.name;
  });
}
