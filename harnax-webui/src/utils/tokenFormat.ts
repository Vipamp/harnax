/**
 * Token 数的 M/K 口径。首页总览与会话页的上下文占用读数共用这一份，为的是同一个数在两个屏幕上长同一个样。
 *
 * token 监控页（`pages/token-monitor/index.tsx` 的 `formatToken`）里还有一份同规则的本地副本，本域没有引用它：
 * 挪走它会推倒 iOS 侧 `TokenFigures` 按那份的行号写下的锚点，收益只是少一份旧副本。
 */

/**
 * 1e6 走 M、1e3 走 K，各留两位小数；一千以下没有千分位可分，直接给整数。
 * 非有限值与 0 一律当 `0` 显示，不把 NaN 推到页面上。但「这个数不存在」不等于 0：那种形状要由调用方自己给
 * 一句话（例如「尚未记录」），别把本函数当缺省值用。
 */
export function formatTokenCount(value: number | undefined | null): string {
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
