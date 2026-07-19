// utils/format.ts
// 格式化工具

/** 格式化时间：ISO / 时间戳 -> YYYY-MM-DD HH:mm */
export function formatDateTime(input?: string | number): string {
  if (!input) return '-';
  const d = new Date(input);
  if (isNaN(d.getTime())) return String(input);
  const pad = (n: number) => (n < 10 ? '0' + n : '' + n);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 相对时间：刚刚 / x分钟前 / x小时前 / x天前 */
export function formatRelativeTime(input?: string | number): string {
  if (!input) return '-';
  const d = new Date(input);
  if (isNaN(d.getTime())) return String(input);
  const diff = Date.now() - d.getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min}分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}小时前`;
  const day = Math.floor(hour / 24);
  if (day < 30) return `${day}天前`;
  return formatDateTime(input);
}

/** 格式化数字：千分位 */
export function formatNumber(n?: number): string {
  if (n === undefined || n === null) return '0';
  return n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** 大数字缩写：1.2k / 3.4M */
export function formatCompactNumber(n?: number): string {
  if (n === undefined || n === null) return '0';
  if (n < 1000) return String(n);
  if (n < 1000000) return (n / 1000).toFixed(1) + 'k';
  return (n / 1000000).toFixed(1) + 'M';
}

/** 格式化耗时（毫秒 -> 可读） */
export function formatDuration(ms?: number): string {
  if (!ms || ms < 0) return '-';
  if (ms < 1000) return `${ms}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  return `${m}分${Math.floor(s % 60)}秒`;
}

/** 金额格式化 */
export function formatMoney(n?: number): string {
  if (n === undefined || n === null) return '¥0.00';
  return '¥' + n.toFixed(4);
}

/** 截断文本 */
export function truncate(text?: string, len = 30): string {
  if (!text) return '';
  return text.length > len ? text.slice(0, len) + '...' : text;
}
