// Copy gate for the marketing site: banned claims, whitelisted numbers,
// paired qualifiers, bilingual structural parity, hreflang closure, contrast.
// Run: node harnax-site/tools/check.mjs [--report] [--selftest]
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
export const PAGES = ['index.html', 'capability.html', 'deploy.html', 'boundaries.html'];

export const SECTION_IDS = {
  'index.html': ['hero', 'visible', 'mechanism', 'forms', 'scale', 'boundary', 'articles'],
  'capability.html': ['sticky', 'schedule', 'skill', 'records'],
  'deploy.html': ['topology', 'steps', 'bill', 'egress'],
  'boundaries.html': ['not-said', 'narrowed', 'source'],
};

// { id, re } — matched case-insensitively against visible text only.
export const BANNED = [
  { id: 'open-source', re: '开源|\\bopen[ -]?source\\b' },
  { id: 'one-click', re: '一键部署|一键启动|开箱即用|\\bout of the box\\b' },
  { id: 'orchestration', re: 'kubernetes|\\bk8s\\b|\\bhelm\\b|kustomize' },
  { id: 'scale-claim', re: '百万并发|\\bSLA\\b|99\\.9' },
  { id: 'tracing-claim', re: '全链路追踪|distributed tracing|full[- ]chain tracing' },
  { id: 'absolutes', re: '绝对安全|百分百|绝对拦|\\b100%\\s*(safe|block)' },
  { id: 'zero-egress', re: '零公网请求|一个字节都不出|绝不联网|不联网' },
  { id: 'model-coverage', re: '主流模型全覆盖|全模型覆盖|所有大模型|every model' },
  { id: 'tenant-hard', re: '强隔离|硬隔离|\\bhard isolation\\b' },
  { id: 'money', re: '费用|账单|计费|金额|\\bbilling\\b|\\bcosts?\\b|\\bpricing\\b' },
  { id: 'customers', re: '客户|案例|\\bcase stud|\\btestimonial' },
  { id: 'wechat-personal', re: '个人微信|\\bweixin\\b|\\bwechat\\b' },
  { id: 'mcp-production', re: '生产级 MCP|production-grade MCP' },
  { id: 'social-proof', re: '评论区|用户反馈|\\bfeedback from users\\b' },
];

const NUM = new Set([
  // 4 = dashboard composition (7 卡 / 3 饼 / 4 线), an audited UI fact.
  '0', '1', '2', '3', '4', '5', '6', '7', '7.5', '9', '10', '11', '12', '14', '15', '20', '30', '35',
  '50', '60', '90', '100', '200', '500', '512', '600', '900', '1000', '1800', '443', '80', '300',
  '5000', '8448', '10000', '11434', '12.0', '22.5', '52.5', '1.5', '4.5', '2688', '0.5',
  // The audit ledger's own tally, quoted on the boundaries page: 69 = 56 + 11 + 2.
  '69', '56',
]);

// Shapes that are not product claims: dates, years, protocol codes, CSS lengths,
// algorithm names and dependency versions. Memory ceilings with an M suffix are per-service
// compose resource limits copied verbatim from docker-compose.yml; the aggregates (8448 / 2688)
// stay whitelisted so a total cannot be invented.
const IGNORE = [
  /\d{4}-\d{2}-\d{2}/g,
  /\b20\d{2}\b/g,
  /\bHTTP \d{3}\b/g,
  /\b\d+M\b/g,
  /\d+\s*期/g,
  /图\s*\d+/g,
  /\b(?:fig|figure|issue|no\.?)\s*\d+/gi,
  /\d+(?:\.\d+)?(px|rem|em|%|ms|s|w|h)\b/g,
  /\b(?:sha|aes|md5|jwt|sm)[\s-]?\d+\b/gi,
  /\b(?:oauth|mysql|postgres|redis|tls|quartz|jdk|kotlin|spring)[\s-]?\d+(?:\.\d+)?\b/gi,
  /\bv?\d+\.\d+\.\d+\b/g,
];

export const PAIR_RULES = [
  { id: 'takeover', trigger: '22\\.5|接管|takeover window', require: '纸面推导|on paper|not covered|未覆盖' },
  { id: 'sticky', trigger: '粘性|session affinity|sticky', require: '执行服务实例|执行实例|executor instance|gateway itself' },
  { id: 'failover', trigger: '故障转移|failover|转移', require: '连接类|connection error|已吐出|first event|尚未送达' },
  { id: 'private', trigger: '私有部署|自托管|self-host|private deployment', require: '无上报|no telemetry|配置项|configurable|被动' },
  { id: 'records', trigger: '五张表|five tables', require: '两张|two of them|自己连库|directly' },
  { id: 'crypto', trigger: '加密|encrypt', require: '明文|plaintext|密钥类字段|key material' },
  { id: 'models', trigger: '本地模型|Ollama|local model', require: '三类|three protocol|DashScope' },
  { id: 'channels', trigger: '渠道|channel|飞书|Feishu|钉钉|DingTalk|企微|WeCom', require: '飞书|Feishu' },
  { id: 'redis', trigger: 'Redis', require: 'noeviction|Sentinel' },
  { id: 'https', trigger: 'HTTPS|443|TLS', require: '证书需|证书自备|bring your own|no certificate|不放证书' },
];

export function textNodes(html) {
  // SVG geometry lives in tag attributes and is dropped with the tags below, so
  // keeping <svg> only exposes <text> labels — those are claims too.
  return html
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(style|script)\b[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]+>/g, ' ');
}

export function numbersOffWhitelist(html) {
  let t = textNodes(html);
  for (const re of IGNORE) t = t.replace(re, ' ');
  return [...new Set([...t.matchAll(/\d+(?:\.\d+)?/g)].map((m) => m[0]))].filter((n) => !NUM.has(n));
}

export function bannedHits(html) {
  const text = textNodes(html);
  return BANNED.filter((w) => new RegExp(w.re, 'i').test(text)).map((w) => w.id);
}

export function bands(html) {
  return [...html.matchAll(/<section\b[\s\S]*?<\/section>/g)].map((m) => m[0]);
}

export function pairViolations(html) {
  const bad = [];
  for (const rule of PAIR_RULES) {
    const trigger = new RegExp(rule.trigger, 'i');
    const require_ = new RegExp(rule.require, 'i');
    bands(html).forEach((band, i) => {
      if (trigger.test(band) && !require_.test(band)) bad.push(`${rule.id}#band${i}`);
    });
  }
  return bad;
}

const grab = (html, re) => [...html.matchAll(re)].map((m) => (m[1] !== undefined ? m[1] : m[0]));

export function parityIssues(zhHtml, enHtml) {
  const bad = [];
  // Both trees are mapped to one canonical path space: a language switch links across trees
  // from either side, so comparing zh's raw /en/x.html against en's stripped x.html could never match.
  const de = (h) => h.replace(/^\/en\//, '/');
  const zhIds = grab(zhHtml, /<section id="([^"]+)"/g);
  const enIds = grab(enHtml, /<section id="([^"]+)"/g);
  if (zhIds.join() !== enIds.join()) bad.push(`ids: [${zhIds}] vs [${enIds}]`);
  const zhA = grab(zhHtml, /<a\b[^>]*href="([^"]+)"/g).map(de).sort();
  const enA = grab(enHtml, /<a\b[^>]*href="([^"]+)"/g).map(de).sort();
  if (zhA.length !== enA.length) bad.push(`anchors: ${zhA.length}!=${enA.length}`);
  else if (zhA.join() !== enA.join()) bad.push('anchor-set differs');
  const zhH = grab(zhHtml, /<h([123])[^>]*>/g);
  const enH = grab(enHtml, /<h([123])[^>]*>/g);
  if (zhH.join() !== enH.join()) bad.push(`heading levels differ: ${zhH} vs ${enH}`);
  return bad;
}

function parseTokens(css) {
  const root = css.match(/:root\s*\{([\s\S]*?)\}/);
  if (!root) return {};
  const out = {};
  for (const m of root[1].matchAll(/--([\w-]+)\s*:\s*(#[0-9a-fA-F]{6})/g)) out[m[1]] = m[2];
  return out;
}

function rgb(color) {
  if (color.startsWith('#')) {
    const c = color.slice(1);
    return [parseInt(c.slice(0, 2), 16), parseInt(c.slice(2, 4), 16), parseInt(c.slice(4, 6), 16)];
  }
  const m = color.match(/rgba?\(([^)]+)\)/);
  const p = m[1].split(',').map((s) => parseFloat(s.trim()));
  return [p[0], p[1], p[2], p[3]];
}

function luminance([r, g, b]) {
  const f = (v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function over(fg, bg) {
  const a = fg.length === 4 ? fg[3] : 1;
  return [0, 1, 2].map((i) => Math.round(fg[i] * a + bg[i] * (1 - a)));
}

export function ratio(fgColor, bgColor) {
  const bg = rgb(bgColor), fg = rgb(fgColor);
  const L1 = luminance(over(fg, bg)), L2 = luminance(bg);
  return (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
}

// [foreground, background, min] — names resolve against :root tokens.
const COLOR_PAIRS = [
  ['--text', '--paper', 4.5],
  ['--text-2', '--paper', 4.5],
  ['--muted', '--bg', 4.5],
  ['--accent-ink', '--paper', 4.5],
  ['--primary', '--paper', 4.5],
  ['--primary-ink', '--bg', 4.5],
  ['--accent', '--ink', 3],
  ['--paper', '--ink', 4.5],
  ['rgba(255,255,255,0.72)', '--ink', 4.5],
];

function readPage(path) {
  const abs = join(ROOT, path);
  if (!existsSync(abs)) return null;
  return readFileSync(abs, 'utf8');
}

export function run() {
  const fails = [];
  const stats = { pages: 0, banned: 0, numbers: 0, pairs: 0, parity: 0 };

  const css = readPage('assets/site.css');
  if (!css) fails.push('missing html: assets/site.css');
  else {
    const tokens = Object.fromEntries(
      Object.entries(parseTokens(css)).map(([k, v]) => [`--${k}`, v]),
    );
    for (const [fg, bg, min] of COLOR_PAIRS) {
      const f = fg.startsWith('--') ? tokens[fg] : fg;
      const b = bg.startsWith('--') ? tokens[bg] : bg;
      if (!f || !b) { fails.push(`contrast tokens unresolved: ${fg}/${bg}`); continue; }
      const got = ratio(f, b);
      if (got < min) fails.push(`contrast ${fg}/${bg} ${got.toFixed(2)} < ${min}`);
    }
  }

  for (const page of PAGES) {
    const zh = readPage(page);
    const en = readPage(`en/${page}`);
    for (const [label, html] of [[page, zh], [`en/${page}`, en]]) {
      if (!html) { fails.push(`missing html: ${label}`); continue; }
      stats.pages++;
      const b = bannedHits(html);
      b.forEach((x) => fails.push(`${label} banned:${x}`));
      stats.banned += b.length;
      const n = numbersOffWhitelist(html);
      n.forEach((x) => fails.push(`${label} number-off-whitelist:${x}`));
      stats.numbers += n.length;
      const p = pairViolations(html);
      p.forEach((x) => fails.push(`${label} pair:${x}`));
      stats.pairs += p.length;

      const wantEn = `/en/${page}`;
      const wantZh = `/${page}`;
      const self = label.startsWith('en/') ? wantEn : wantZh;
      const other = label.startsWith('en/') ? wantZh : wantEn;
      const alts = [...html.matchAll(/<link\b[^>]*rel="alternate"[^>]*>/g)].map((m) => m[0]);
      const has = (lang, href) => alts.some((t) => t.includes(`hreflang="${lang}"`) && t.includes(`href="${href}"`));
      if (!has('zh-CN', wantZh)) fails.push(`${label} hreflang zh-CN -> ${wantZh}`);
      if (!has('en', wantEn)) fails.push(`${label} hreflang en -> ${wantEn}`);
      if (!has('x-default', wantZh)) fails.push(`${label} hreflang x-default -> ${wantZh}`);
      if (!html.includes(`<link rel="canonical" href="${self}">`)) fails.push(`${label} canonical != ${self}`);
      if (!html.includes('<html lang=')) fails.push(`${label} missing lang attribute`);
    }
    if (zh && en) {
      const issues = parityIssues(zh, en);
      issues.forEach((x) => fails.push(`${page} parity: ${x}`));
      stats.parity += issues.length;
    }
  }

  if (process.argv.includes('--report')) {
    console.log(`pages=${stats.pages} banned=${stats.banned} numbers=${stats.numbers} pairs=${stats.pairs} parity=${stats.parity}`);
    console.log(`rules: banned=${BANNED.length} pairs=${PAIR_RULES.length} whitelist=${NUM.size} numbers`);
  }
  if (!fails.length) console.log('copy gate: PASS');
  else {
    console.log('copy gate: FAIL');
    for (const f of fails) console.log('  - ' + f);
  }
  return fails.length ? 1 : 0;
}

// The gate must be able to fail on a known-bad sample, otherwise a green run means nothing.
export function selftest() {
  const fixture = [
    '<!doctype html><html lang="zh-CN"><head><title>Harnax · 全链路追踪</title></head><body>',
    '<section id="hero"><h2>集群调度接管窗口 22.5 秒</h2></section>',
    '<section id="scale"><p>支持开源部署，费用透明</p></section>',
    '<section id="numbers"><p>发现窗口 3 秒，起步 16 核</p></section>',
    '<section id="diagram"><svg viewBox="0 0 300 100"><rect x="7" width="16"/>' +
      '<text x="20" y="40">一键部署，起步 17 核</text></svg></section>',
    '</body></html>',
  ].join('\n');
  const want = [
    'banned:open-source',
    'banned:money',
    'banned:one-click',
    'pair:takeover#band0',
    'number-off-whitelist:16',
    'number-off-whitelist:17',
  ];
  const got = [
    ...bannedHits(fixture).map((x) => `banned:${x}`),
    ...numbersOffWhitelist(fixture).map((x) => `number-off-whitelist:${x}`),
    ...pairViolations(fixture).map((x) => `pair:${x}`),
  ];
  const missing = want.filter((w) => !got.includes(w));
  if (missing.length) {
    console.log('selftest: FAIL — gate did not catch ' + missing.join(', '));
    return 1;
  }
  console.log('selftest: PASS — caught ' + want.length + ' seeded violations');
  return 0;
}

if (process.argv[1] && process.argv[1].endsWith('check.mjs')) {
  process.exit(process.argv.includes('--selftest') ? selftest() : run());
}
