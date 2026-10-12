# Harnax 官网实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `harnax-site/` 产出一个零构建、中英双语、四页的静态官网（首页分层叙事 + 能力 / 部署 / 边界三页），并由一个机器口径闸保证站内每条断言都在已审计事实范围内。

**Architecture:** 中文四页在站点根、英文四页在 `en/` 同名文件，两树共用 `assets/site.css` 与 `assets/site.js`；所有资源与站内链接写成绝对路径，可直接丢进任意静态服务器。证据一律为内联 SVG 示意图与机制数字，不放真实截图、不放仓库地址、不提开源。口径不靠人记：`tools/check.mjs` 用禁用词表、数字白名单、限定语成对规则与双语结构对账四道闸把住，无头 Chrome 出渲染证据。

**Tech Stack:** 原生 HTML5 + CSS + 原生 JS（ES2020，无框架无依赖）；校验与取证用 `node`（≥22）、`python3 -m http.server`、本机 `/Applications/Google Chrome.app` 的 headless 模式。不引入任何 npm 包。

## Global Constraints

- 不新增任何 npm 依赖，不新增构建步骤；`harnax-site/` 内产物必须能被浏览器直接消费。
- 站内所有链接与资源引用使用绝对路径（`/assets/site.css`、`/deploy.html`、`/en/deploy.html`），禁止 `../` 与 `./`。
- 中文页 `<html lang="zh-CN">`，英文页 `<html lang="en">`；同一页两种语言的结构、`id` 集合与链接集合必须一一对应。
- 禁词（两种语言各自零命中，词边界判定）：`开源`、`open source`、`open-source`、`一键部署`、`开箱即用`、`out of the box`、`Kubernetes`、`K8s`、`百万并发`、`SLA`、`全链路追踪`、`trace`、`tracing`、`绝对安全`、`百分百`、`零公网请求`、`一个字节都不出内网`、`主流模型全覆盖`、`多租户强隔离`、`费用`、`账单`、`cost`、`billing`、`计费`、`客户`、`案例`、`个人微信`、`生产级 MCP`、`评论区`、`用户反馈`。
- 数字白名单（站内文本出现的每个数字必须落在其中，完整清单见规格 §2.3）：模块 `11 / 14`；服务块 `10 / 9`；limits `12.0 / 8448`；reservations `4.5 / 2688`；团队 `20 / 900 / 1800 / 600 / 20 MiB / 3`；技能 `9 / 1000 / 5000 / 20 MiB / 200 MiB / 512`；沙箱 `100 / 30 分钟 / 50 MB / 5`；路由 `10 / 5 / 30 / 20–35 / 3 / 30 / 2`；调度 `15 / 60 / 22.5 / 52.5 / 60 / 5 分钟 / 1.5 / 2 / 90 天 / 7 天 / 6`；网关 `10000 / 50 / 5`；模型 `localhost:11434`。日期与年份形式（`YYYY-MM-DD`、`2026`）与 `HTTP 200` 一类协议码不视为站内数字，按形状放行。
- 成对限定语（写了前半句必须同节出现后半句）：见规格 §2.2 十条，`check.mjs` 逐条实现。
- 色彩 token 单一来源：`--ink:#0b1224`、`--ink-2:#11182f`、`--primary:#4b6af2`、`--primary-ink:#3d5ae0`、`--accent:#ff8a3d`、`--paper:#ffffff`、`--bg:#f7f8fa`、`--text:#191c1f`、`--text-2:#54596a`、`--muted:#696f78`、`--line:#e6e8ec`。深底只用于 Hero 与数字条；正文一律浅底。
- 代码注释与提交信息用英文；站内可见文案按语言各自成文。
- 工作树当前有他人未提交改动（`harnax-webui/src/pages/session/index.tsx` 等）。只允许 `git add <确切路径>`，禁止 `git add -A` / `git add .`；提交前必须 `git status --short` 复核暂存集只含本次任务的文件。

## File Structure

| 文件 | 职责 | 诞生于 |
|---|---|---|
| `harnax-site/tools/check.mjs` | 口径闸：禁词、数字白名单、成对限定语、双语结构对账、hreflang/canonical 闭合、对比度计算 | Task 1 |
| `harnax-site/assets/site.css` | 全站 token、栅格、组件样式、响应式与 `prefers-reduced-motion` | Task 2 |
| `harnax-site/assets/site.js` | 移动端导航开关、粘性目录高亮、淡入（三件事，仅此三件） | Task 2 |
| `harnax-site/assets/icon.png` | 站点标识，取自 `vibe_images/harnax-icon-flat-tension_1790739205415_e4a98366.png` | Task 2 |
| `harnax-site/index.html` | 首页七屏 | Task 3 |
| `harnax-site/capability.html` | 四机制展开 + 左侧粘性目录 | Task 4 |
| `harnax-site/deploy.html` | 拓扑表 / 上线顺序 / 运维账单四档 / 出站面 | Task 5 |
| `harnax-site/boundaries.html` | 没做清 / 要收窄对照 / 口径从哪来 | Task 6 |
| `harnax-site/en/{index,capability,deploy,boundaries}.html` | 英文镜像四页 | Task 7、Task 8 |
| `harnax-site/README.md` | 本地预览、托管方式、加一条文案要走哪道闸 | Task 9 |

DOM 契约（后续任务与 `check.mjs` 都依赖它，实现时逐字照抄，不得改名）：

```
页面 <section> 的 id 白名单——
index.html        : hero / visible / mechanism / forms / scale / boundary / articles
capability.html   : sticky / schedule / skill / records
deploy.html       : topology / steps / bill / egress
boundaries.html   : not-said / narrowed / source
共用 class ——
.hdr .hdr-nav .hdr-lang .btn-primary .btn-ghost
.band（每个 section 根元素） .band-inner（1160px 容器）
.cards .card .mech .mech-figure .mech-body .tiers .tier .facts .fact
.tbl（表格统一类） .note（诚实标注，左侧 3px 实线）
.ftr .ftr-col
成对标记 —— 限定语必须落在同一 .band 内，用 <aside class="note"> 承载。
```

---

### Task 1: 口径闸门脚本

先立闸，再写内容：此时站内还没有 HTML，四条检查必须红。

**Files:**
- Create: `harnax-site/tools/check.mjs`
- Test: 直接以 `node harnax-site/tools/check.mjs` 的退出码与输出为测试

**Interfaces:**
- Consumes: 无
- Produces: 命令行 `node harnax-site/tools/check.mjs` → 退出码 0 表示全绿；`--report` 打印每条规则的命中数。导出约定：`PAGES`（四页文件名数组）、`SECTION_IDS`（页→id 数组）、`BANNED`、`NUM_WHITELIST`、`PAIR_RULES`（`{trigger, require, both}` 三字段，`trigger`/`require` 为正则源字符串，`both` 为语言域 `zh|en|any`）。Task 9 的取证脚本会复用 `PAGES` 与 `SECTION_IDS`。

- [ ] **Step 1: 写脚本骨架，四条规则各自成函数**

```js
// harnax-site/tools/check.mjs
import { readFileSync, readdirSync, existsSync } from 'node:fs';
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

const NUM = new Set([
  '11', '14', '10', '9', '12.0', '8448', '4.5', '2688',
  '20', '900', '1800', '600', '3', '9', '1000', '5000', '200', '512',
  '100', '30', '50', '5', '15', '60', '22.5', '52.5', '1.5', '2', '1',
  '90', '7', '6', '10000', '11434', '443', '80', '300', '500',
  '1024', '768',
]);
// 形状放行：日期、年份、http 码、css 尺寸（带单位）、版本号
const IGNORE = [/\d{4}-\d{2}-\d{2}/g, /\b20\d{2}\b/g, /\bHTTP \d{3}\b/g, /\d+(px|rem|em|%|ms|s)\b/g];

export function numbersOffWhitelist(text) {
  let t = text;
  for (const re of IGNORE) t = t.replace(re, ' ');
  return [...new Set([...t.matchAll(/\d+(?:\.\d+)?/g)].map((m) => m[0]))]
    .filter((n) => !NUM.has(n));
}

export function bannedHits(text) {
  const out = [];
  for (const w of BANNED) if (new RegExp(w.re, 'i').test(text)) out.push(w.id);
  return out;
}

export function pairViolations(html) {
  // html 先按 <section ...>…</section> 切块；trigger 命中而 require 未命中即违规
  const bands = [...html.matchAll(/<section\b[\s\S]*?<\/section>/g)].map((m) => m[0]);
  const bad = [];
  for (const rule of PAIR_RULES) {
    for (const [i, band] of bands.entries()) {
      if (new RegExp(rule.trigger, 'i').test(band) && !new RegExp(rule.require, 'i').test(band)) {
        bad.push(`${rule.id}#band${i}`);
      }
    }
  }
  return bad;
}

export function parityIssues(zhHtml, enHtml) {
  const grab = (h, re) => [...h.matchAll(re)].map((m) => m[1]).sort();
  const bad = [];
  const zhIds = grab(zhHtml, /<section id="([^"]+)"/g);
  const enIds = grab(enHtml, /<section id="([^"]+)"/g);
  if (zhIds.join() !== enIds.join()) bad.push(`ids:${zhIds.join()}|${enIds.join()}`);
  for (const [re, name] of [[/href="(\/[^"]*)"/g, 'href'], [/<h2[^>]*>/g, 'h2']]) {
    const a = grab(zhHtml, re), b = grab(enHtml, re);
    if (a.length !== b.length) bad.push(`${name}:${a.length}!=${b.length}`);
    if (name === 'href' && a.join() !== b.map((x) => x.replace(/^\/en\//, '/').replace(/^\/$/, '/')).join()) {
      bad.push('href-set');
    }
  }
  return bad;
}
```

- [ ] **Step 2: 补上 `BANNED` 与 `PAIR_RULES` 两张数据表**

`BANNED` 按 Global Constraints 的禁词逐条落成 `{id, re}`，中文词用裸串、英文词用词边界（例：`{ id: 'open-source', re: '开源|\\bopen[ -]source\\b' }`、`{ id: 'k8s', re: 'kubernetes|\\bk8s\\b' }`、`{ id: 'cost', re: '费用|账单|计费|\\bcost\\b|\\bbilling\\b' }`）。

`PAIR_RULES` 十条，`trigger` 与 `require` 都写成能同时命中中英的正则（用 `|` 并列两种说法）：

```js
export const PAIR_RULES = [
  { id: 'takeover',  trigger: '22\\.5|接管',                       require: '纸面推导|on paper|not covered by' },
  { id: 'sticky',    trigger: '粘性|session affinity|sticky',      require: '执行服务实例|executor instance|gateway itself' },
  { id: 'failover',  trigger: '故障转移|failover',                 require: '连接类|connection error|已吐出|first event' },
  { id: 'private',   trigger: '私有部署|自托管|self-host|private', require: '无上报|no telemetry|配置项|configurable' },
  { id: 'records',   trigger: '五张表|five tables',                require: '两张|two of them|自己连库|directly' },
  { id: 'crypto',    trigger: '加密|encrypt',                      require: '明文|plaintext|plaintext column' },
  { id: 'models',    trigger: '本地模型|Ollama|local model',       require: '三类|three protocol|DashScope' },
  { id: 'channels',  trigger: '渠道|channel|飞书|Feishu|钉钉|DingTalk', require: '飞书|Feishu' },
  { id: 'redis',     trigger: 'Redis',                             require: 'noeviction|Sentinel' },
  { id: 'https',     trigger: 'HTTPS|443|TLS',                     require: '证书需|bring your own|证书自备|no certificate' },
];
```

- [ ] **Step 3: 写 main 与对比度计算**

```js
function contrast(hex) {
  const c = hex.replace('#', '');
  const lin = (i) => { const v = parseInt(c.slice(i, i + 2), 16) / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  const L = 0.2126 * lin(0) + 0.7152 * lin(2) + 0.0722 * lin(4);
  return L;
}
export function ratio(fg, bg) { const a = contrast(fg), b = contrast(bg); return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05); }
// 用法组合表（前景, 背景, 最低要求）
const COLOR_PAIRS = [['--text', '--paper', 4.5], ['--text-2', '--paper', 4.5], ['--muted', '--bg', 4.5],
                     ['--accent', '--ink', 3], ['--primary', '--paper', 3], ['--paper', '--ink', 4.5]];
```

main：读 8 份 HTML（缺失即计入失败）、逐份跑禁词 / 数字 / 成对；同页双语跑 `parityIssues`；`hreflang` 与 `canonical` 检查（每份英文页必须含指向同页中文版与 `x-default` 的两条 `<link rel="alternate">`，中文页必须含指向 `en/` 的对应一条）；CSS token 跑 `COLOR_PAIRS`；任一失败 `process.exit(1)` 并打印失败项 id。

- [ ] **Step 4: 跑一次，确认是红的且红得正确**

Run: `node harnax-site/tools/check.mjs --report`
Expected: 退出码 1，输出含 `missing html: harnax-site/index.html` 一类缺失项，而不是语法错误堆栈。

- [ ] **Step 5: 用夹具自证闸门不是恒绿**

```bash
mkdir -p /tmp/gate-fixture && cat > /tmp/gate-fixture/bad.html <<'EOF'
<section id="hero"><h2>集群调度接管窗口 22.5 秒</h2></section>
<section id="scale"><p>支持开源部署，费用透明</p></section>
EOF
node harnax-site/tools/check.mjs --selftest
```

Expected: `--selftest` 对上串断言出 `banned:open-source`、`banned:cost`、`pair:takeover` 三项失败；三项任一没报出来就是闸门写坏了（禁词漏、切块没生效），必须先修闸门再继续。夹具文件用 `cat` 整块写，不要用 `printf` 拼中文。

- [ ] **Step 6: Commit**

```bash
git add harnax-site/tools/check.mjs
git commit -m "feat(site): add copy gate for banned claims, number whitelist and bilingual parity"
```

---

### Task 2: 共享样式、交互脚本与标识资产

**Files:**
- Create: `harnax-site/assets/site.css`
- Create: `harnax-site/assets/site.js`
- Create: `harnax-site/assets/icon.png`（从 `vibe_images/` 复制）

**Interfaces:**
- Consumes: DOM 契约里的 class 名
- Produces: CSS 自定义属性 `--ink`…`--line`（Task 1 的 `COLOR_PAIRS` 按名读取）；断点 `1024px` / `768px`；`site.js` 依赖的 data 属性 `data-nav-toggle`、`data-band`、`data-reveal`

- [ ] **Step 1: 复制标识资产**

```bash
mkdir -p harnax-site/assets harnax-site/en
cp vibe_images/harnax-icon-flat-tension_1790739205415_e4a98366.png harnax-site/assets/icon.png
file harnax-site/assets/icon.png
```

Expected: 输出为 `PNG image data`，尺寸不小于 512×512。

- [ ] **Step 2: 写 `site.css`**

必含内容（按此顺序，token 在最前，Task 1 从 `:root` 块解析）：

```css
:root {
  --ink:#0b1224; --ink-2:#11182f; --primary:#4b6af2; --primary-ink:#3d5ae0;
  --accent:#ff8a3d; --paper:#ffffff; --bg:#f7f8fa; --text:#191c1f;
  --text-2:#54596a; --muted:#696f78; --line:#e6e8ec;
  --maxw:1160px; --gutter:24px; --r-lg:14px; --r-md:10px;
}
* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; scroll-behavior: smooth; }
body { margin:0; color:var(--text); background:var(--paper); font:400 16px/1.75
  -apple-system,"PingFang SC","Hiragino Sans GB","Microsoft YaHei","Segoe UI",Roboto,sans-serif; }
html[lang="en"] body { line-height:1.7; }
.band { padding:96px 0; }
.band-inner { max-width:var(--maxw); margin:0 auto; padding:0 var(--gutter); }
.band-alt { background:var(--bg); }
.band-dark { background:var(--ink); color:var(--paper); }
h1 { font-size:clamp(30px,4.4vw,52px); line-height:1.18; letter-spacing:-.01em; margin:0 0 18px; }
h2 { font-size:clamp(22px,2.6vw,32px); line-height:1.3; margin:0 0 12px; }
h3 { font-size:17px; margin:0 0 8px; }
p { margin:0 0 14px; } a { color:var(--primary-ink); text-decoration:none; }
.eyebrow { font:600 12px/1 ui-monospace,Menlo,monospace; letter-spacing:.14em; text-transform:uppercase; color:var(--accent); }
.lede { font-size:18px; color:var(--text-2); max-width:64ch; }
.hdr { position:sticky; top:0; z-index:20; background:rgba(255,255,255,.86); backdrop-filter:saturate(160%) blur(12px); border-bottom:1px solid var(--line); }
.hdr-inner { max-width:var(--maxw); margin:0 auto; padding:0 var(--gutter); height:64px; display:flex; align-items:center; gap:28px; }
.hdr-brand { display:flex; align-items:center; gap:10px; font-weight:700; color:var(--text); }
.hdr-nav { display:flex; gap:22px; margin-left:auto; }
.hdr-nav a { color:var(--text-2); font-size:15px; }
.hdr-lang a { color:var(--muted); } .hdr-lang a[aria-current="page"] { color:var(--text); font-weight:600; }
.btn-primary, .btn-ghost { display:inline-flex; align-items:center; gap:8px; height:44px; padding:0 22px;
  border-radius:999px; font-weight:600; font-size:15px; white-space:nowrap; }
.btn-primary { background:var(--primary); color:#fff; } .btn-primary:hover { background:var(--primary-ink); }
.btn-ghost { border:1px solid var(--line); color:var(--text); background:var(--paper); }
.band-dark .btn-ghost { color:var(--paper); border-color:rgba(255,255,255,.28); background:transparent; }
.cards { display:grid; grid-template-columns:repeat(4,1fr); gap:var(--gutter); }
.card { background:var(--paper); border:1px solid var(--line); border-radius:var(--r-lg); padding:24px; }
.card-num { font:600 12px/1 ui-monospace,Menlo,monospace; color:var(--accent); }
.mech { display:grid; grid-template-columns:5fr 7fr; gap:40px; align-items:start; }
.mech-figure { background:var(--paper); border:1px solid var(--line); border-radius:var(--r-lg); padding:20px; }
.mech-figure svg { width:100%; height:auto; display:block; }
.tiers { display:grid; grid-template-columns:repeat(3,1fr); gap:var(--gutter); }
.tier { border:1px solid var(--line); border-radius:var(--r-lg); padding:24px; background:var(--paper); }
.tier h3 { font-size:18px; } .tier ul { margin:0; padding-left:18px; color:var(--text-2); font-size:15px; }
.facts { display:grid; grid-template-columns:repeat(6,1fr); gap:20px; }
.fact b { display:block; font:700 26px/1.1 ui-monospace,Menlo,monospace; color:var(--paper); }
.fact span { font-size:13px; color:rgba(255,255,255,.72); }
.tbl { width:100%; border-collapse:collapse; font-size:15px; }
.tbl th { text-align:left; color:var(--text-2); font-weight:600; background:var(--bg); }
.tbl th, .tbl td { padding:12px 14px; border-bottom:1px solid var(--line); vertical-align:top; }
.note { border-left:3px solid var(--accent); background:var(--bg); padding:14px 18px; border-radius:0 var(--r-md) var(--r-md) 0;
  color:var(--text-2); font-size:14.5px; margin:18px 0 0; }
.toc { position:sticky; top:88px; align-self:start; display:grid; gap:8px; }
.toc a { color:var(--muted); font-size:14px; } .toc a[aria-current="true"] { color:var(--text); font-weight:600; }
.ftr { background:var(--ink); color:rgba(255,255,255,.78); padding:64px 0 40px; }
.ftr-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:var(--gutter); }
.ftr-col h4 { color:var(--paper); font-size:14px; margin:0 0 12px; }
.ftr a { color:rgba(255,255,255,.78); display:block; font-size:14px; margin-bottom:8px; }
[data-reveal] { opacity:0; transform:translateY(14px); transition:opacity .5s ease, transform .5s ease; }
[data-reveal].in { opacity:1; transform:none; }
.nav-toggle { display:none; }
@media (max-width:1024px) { .cards{grid-template-columns:repeat(2,1fr)} .facts{grid-template-columns:repeat(3,1fr)}
  .mech{grid-template-columns:1fr} .band{padding:64px 0} }
@media (max-width:768px) { .cards,.tiers,.ftr-grid{grid-template-columns:1fr} .facts{grid-template-columns:repeat(2,1fr)}
  .hdr-nav{position:absolute; top:64px; left:0; right:0; flex-direction:column; background:var(--paper);
  border-bottom:1px solid var(--line); padding:16px var(--gutter); display:none; }
  .hdr-nav.open{display:flex} .nav-toggle{display:inline-flex; margin-left:auto} }
@media (prefers-reduced-motion:reduce) { [data-reveal]{opacity:1; transform:none; transition:none} html{scroll-behavior:auto} }
```

- [ ] **Step 3: 写 `site.js`（只做三件事）**

```js
// Mobile nav, sticky-TOC highlight, and reveal-on-scroll. Nothing else.
(() => {
  const toggle = document.querySelector('[data-nav-toggle]');
  const nav = document.querySelector('.hdr-nav');
  if (toggle && nav) {
    toggle.addEventListener('click', () => {
      const open = nav.classList.toggle('open');
      toggle.setAttribute('aria-expanded', String(open));
    });
  }

  const tocLinks = [...document.querySelectorAll('.toc a')];
  const byId = new Map(tocLinks.map((a) => [a.getAttribute('href').slice(1), a]));
  if (byId.size) {
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        const link = byId.get(e.target.id);
        if (!link) continue;
        if (e.isIntersecting) {
          tocLinks.forEach((a) => a.removeAttribute('aria-current'));
          link.setAttribute('aria-current', 'true');
        }
      }
    }, { rootMargin: '-88px 0px -60% 0px' });
    document.querySelectorAll('[data-band]').forEach((s) => io.observe(s));
  }

  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const items = [...document.querySelectorAll('[data-reveal]')];
  if (reduce || !('IntersectionObserver' in window)) {
    items.forEach((el) => el.classList.add('in'));
  } else {
    const ro = new IntersectionObserver((entries, obs) => {
      for (const e of entries) if (e.isIntersecting) { e.target.classList.add('in'); obs.unobserve(e.target); }
    }, { threshold: 0.12 });
    items.forEach((el) => ro.observe(el));
  }
})();
```

- [ ] **Step 4: 语法校验**

Run: `node --check harnax-site/assets/site.js && node --check harnax-site/tools/check.mjs`
Expected: 两条都无输出、退出码 0。（`node --check` 对 ESM 会报 `Unexpected token 'export'` 吗？不会——若报，改用 `node harnax-site/tools/check.mjs --selftest` 作为脚本可加载性的判据，并把 `site.js` 留在 `--check` 上。）

- [ ] **Step 5: Commit**

```bash
git add harnax-site/assets
git commit -m "feat(site): add shared stylesheet, nav/toc/reveal script and icon asset"
```

---

### Task 3: 中文首页

**Files:**
- Create: `harnax-site/index.html`

**Interfaces:**
- Consumes: DOM 契约、`site.css` class、`site.js` 的 `data-nav-toggle` / `data-reveal` / `data-band`
- Produces: `#hero #visible #mechanism #forms #scale #boundary #articles` 七个 `section`，英文镜像页（Task 7）必须复用同一 id 序列

- [ ] **Step 1: 写 `<head>` 与页头**

```html
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Harnax · 把 AI 从一次调用，做成能长期运行的系统</title>
<meta name="description" content="Harnax 是自托管的多智能体运行平台：配置化装配、可中断可恢复的流式执行、沙箱隔离的工具执行、会话粘性路由、集群化定时触发与多渠道接入。">
<link rel="icon" href="/assets/icon.png">
<link rel="canonical" href="/index.html">
<link rel="alternate" hreflang="zh-CN" href="/index.html">
<link rel="alternate" hreflang="en" href="/en/index.html">
<link rel="alternate" hreflang="x-default" href="/index.html">
<link rel="stylesheet" href="/assets/site.css">
</head>
```

页头四项导航文案与去处：`你看得见的` → `#visible`、`运行期机制` → `/capability.html`、`部署形态` → `/deploy.html`、`边界` → `/boundaries.html`；语言开关 `中文 | EN`（当前语言 `aria-current="page"`）；主按钮 `部署形态` → `/deploy.html`。

- [ ] **Step 2: `#hero`**

照抄规格 §3 屏 1 的主标 / 副标 / 按钮文案，`class="band band-dark"`；右侧视觉用一张内联 SVG 静态链路图（IM → 网关 → 执行实例 → 容器 → 回群），不写任何真实主机名与端口以外的数字，节点文字用 `飞书群` / `会话网关` / `执行实例` / `容器内执行` / `结果回群`。

- [ ] **Step 3: `#visible` 四张场景卡**

`<section class="band" id="visible" data-band>`，`h2` 为「你看得见的四件事」，`.cards` 四张 `.card`，文案逐字取规格 §3 屏 2 第 1–4 条，卡头分别用 `01`–`04` 的 `.card-num`。

- [ ] **Step 4: `#mechanism` 四节机制**

`<section class="band band-alt" id="mechanism" data-band>`，`h2`「你看不见的四个机制」，内部四个 `.mech`：左 `.mech-figure` 一张内联 SVG，右 `.mech-body`（`h3` + 两段正文 + 必要时 `<aside class="note">`）。四张图与四段正文按规格 §3 屏 3 逐条落：

1. 状态归属图：三条泳道（对话内容 / MySQL、会话归属 / Redis、等确认那一步 / 进程内存），第三条画成锁在实例里；`note` 写「固定的是执行服务实例，网关本身多副本共享绑定」。
2. 两层锁：上下两把锁（`QRTZ_LOCKS` 行锁、`uk_task_trigger` 唯一键）＋ 时间轴标 `22.5–52.5 秒`；`note` 写「接管窗口是纸面推导，`ClusterSingleFireIT` 不覆盖接管与真实链路」。
3. 安检与投影收敛：入库前扫描（9 条规则）→ 停用态 → 人工确认 → 投影（sha256 清单，只删自己写过的）。
4. 五张表读回两张：用量表 / 网关日志打勾，另三张标「应用层只写不读，要看就自己连库」。

- [ ] **Step 5: `#forms` / `#scale` / `#boundary` / `#articles`**

`#forms` 用 `.tiers` 三张 `.tier`（单机 / 集群 / 模型可完全本地），文案取规格 §3 屏 4，Redis 那张的 `note` 必须写 `noeviction` 与 Sentinel 限定。`#scale` 用 `class="band band-dark"` 内 `.facts` 六枚 `.fact`，数字取规格 §3 屏 5，块尾一行小字声明「以上为 compose 自己写的资源上限，全仓无最低配置要求文档，不构成起步规格建议」。`#boundary` 四张 `.card`（没备份 / 没告警出口 / 没版本回滚与审核工作流 / 没 K8s，第四条措辞用「没有 Kubernetes 化」→ 触发禁词 `k8s` 但不触发 `Kubernetes`？注意：禁词表含 `Kubernetes`，这一屏必须改写成「未做容器编排化」）。`#articles` 列《AI 员工图鉴》十期标题 + 一句摘要 + 「第 7 期暂缓，并入第二季」。

- [ ] **Step 6: 页脚**

`.ftr` 四栏：产品（三页链接）／形态（三档锚点）／文章（十期）／口径（→ `/boundaries.html#source`），末行一行小字：「站内功能描述以 `harnax-marketing/02-宣传口径审计清单.md` 的审计结论为准；任何一条与实际行为不符，请当缺陷报出来。」

- [ ] **Step 7: 跑闸门，只看这一页该绿的项**

Run: `node harnax-site/tools/check.mjs --report`
Expected: 中文首页的 `banned:*`、`numbers:*`、`pair:*` 三项均无命中；此时英文四页仍缺失、parity 仍红，属于预期，须在 Task 7–8 后清零。

- [ ] **Step 8: Commit**

```bash
git add harnax-site/index.html
git commit -m "feat(site): add zh landing page with seven narrative bands"
```

---

### Task 4: 中文能力页

**Files:**
- Create: `harnax-site/capability.html`

**Interfaces:**
- Consumes: Task 3 的页头页尾结构、`.toc`、`.mech`
- Produces: `#sticky #schedule #skill #records`

- [ ] **Step 1: 页面骨架**

`<section class="band" id="sticky" data-band>` 起，四节各一个 `.mech`；页头下方加 `.toc`（四项：会话粘性 / 集群调度 / 技能生命周期 / 留痕），`href` 为 `#sticky` 等四个锚点；`<h1>` 用「四个运行期机制，以及它们各自欠你的账」。

- [ ] **Step 2: 四节内容按「问题 / 做法 / 代价」三段展开**

`#sticky`：问题给「可变状态不在库里，而在某个进程的内存与磁盘上」；做法给三张时钟（10 秒上报 / 5 秒复查 / 30 秒判死，发现窗口 20–35 秒）与粘性判定故意不看熔断的理由；代价给等确认那一步物理上搬不走、Redis 只有约 30 秒余温、半截输出不重放。
`#schedule`：做法给两层锁与 checkin 15 秒 / misfire 60 秒；区分对账 60 秒与回收 5 分钟；代价给接管为纸面推导、失败不补发不重试无告警、`concurrent=0` 的重叠检测是 check-then-act。
`#skill`：做法给 9 条规则、停用态入库、来源三型硬约束、投影 sha256 收敛与「只删自己写过的」；代价给无版本回滚、无审核队列、AI 自产技能开关默认关闭。
`#records`：做法给一次模型调用一行且流式非流式同一读数、网关日志刻意不带正文、用量看板 7 卡 3 饼 4 线；代价给三张留痕表只写不读、`fee` 恒为 0 所以站内不谈钱、无 traceId 只有 `session_id`、四张观测表无清理。

每节末尾一个 `<aside class="note">` 承担成对限定语。

- [ ] **Step 3: 跑闸门 + Commit**

```bash
node harnax-site/tools/check.mjs --report
git add harnax-site/capability.html
git commit -m "feat(site): add zh capability page with four runtime mechanisms"
```

Expected: 本页禁词 / 数字 / 成对三项绿；`capability.html` 的 parity 仍待 Task 7。

---

### Task 5: 中文部署页

**Files:**
- Create: `harnax-site/deploy.html`

**Interfaces:**
- Consumes: `.tbl`、`.tiers`
- Produces: `#topology #steps #bill #egress`

- [ ] **Step 1: `#topology` 服务块表**

`.tbl` 十行（`mysql` / `redis` / `minio` / `admin` / `router` / `scheduler` / `agent-service` / `channel-service` / `mcp-server` / `frontend`），列：服务 / 角色 / 是否发布到宿主 / 该块 limits。表上一句：「9 个服务块是系统本体，第 10 块是自带的天气示例 MCP，且不在构建脚本的六个镜像清单里。」表下一句 limits 合计 `12.0 核 / 8448M`，并按规格写清这不构成起步建议。Redis 那行的角色列必须含 `noeviction` 限定。

- [ ] **Step 2: `#steps` 上线顺序**

有序列表四步：① 预装 `mvn` / `node` / `docker`（构建期确实需要网络，不藏）；② `bash build.sh` 产出六个镜像；③ 放入自有证书（缺证书时入口起不来，而不是回落 HTTP）；④ `bash deploy-all.sh`，集群需 `--scale scheduler=N`。措辞一律用「一个脚本」，禁词表里的 `一键部署` 不得出现。

- [ ] **Step 3: `#bill` 运维账单四档**

`.tiers` 改四张（`.cards`）：必须改的默认值（出厂口令与三类密钥都是仓库里公开的字符串，只有一处有拒启动检查）／必须自己加的组件（证书、备份、Prometheus 抓取、日志轮转取决于宿主 Docker）／我们没做的（容器编排化、告警出口、观测表清理任务）／明确决定不做的（stdio 型 MCP 运行、AI 自产技能开关默认关闭）。

- [ ] **Step 4: `#egress` 出站面**

三类列表：模型（三类协议族，地址可指内网）／外部 MCP（url 任意，可指内网）／IM 平台（飞书、钉钉、企业微信，域名是源码常量，不启用即无流量）。补一句：「运行期无遥测、无版本检查、无许可证校验，指标只被动 pull；构建期需要网络。」这条就是 `private` 成对规则的 require 侧。

- [ ] **Step 5: 跑闸门 + Commit**

```bash
node harnax-site/tools/check.mjs --report
git add harnax-site/deploy.html
git commit -m "feat(site): add zh deployment page with ops bill and egress list"
```

---

### Task 6: 中文边界页

**Files:**
- Create: `harnax-site/boundaries.html`

**Interfaces:**
- Consumes: `.tbl`
- Produces: `#not-said #narrowed #source`

- [ ] **Step 1: `#not-said` 没做清**

`.tbl` 三列（项目 / 现状 / 你要怎么做）：备份与恢复、告警出口、版本回滚与审核工作流、容器编排化、观测表清理、登录失败锁定、费用核算（`fee` 恒 0，站内不谈钱）七行。

- [ ] **Step 2: `#narrowed` 要收窄对照**

`.tbl` 两列（容易被说成的样子 / 实际情况）：stdio 型 MCP 仅登记不运行、文件直发按渠道能力、迭代上限当前不可配、渠道页当前无能力开关、加密只覆盖密钥类字段、会话粘性固定的是执行实例、页面能读回的留痕只有两张。

- [ ] **Step 3: `#source` 口径从哪来**

一段：判据来自 2026-10-11 完成的分域源码审计，方法为静态比对而非端到端实测，两条核心链路（群内 `/approve` 闭环、技能上传→扫描→停用）仍待真机录屏；一句：任何一条站内描述与实际行为不符，按缺陷处理。更新日期用 `2026-10-12`（`IGNORE` 形状放行，不计入数字白名单判定）。

- [ ] **Step 4: 跑闸门 + Commit**

```bash
node harnax-site/tools/check.mjs --report
git add harnax-site/boundaries.html
git commit -m "feat(site): add zh boundaries page stating what is not done"
```

---

### Task 7: 英文首页与能力页

**Files:**
- Create: `harnax-site/en/index.html`
- Create: `harnax-site/en/capability.html`

**Interfaces:**
- Consumes: Task 3、Task 4 的 DOM 结构与 id；`check.mjs` 的 `parityIssues`
- Produces: 与中文页同 id、同 `href` 集合、同 `h2` 数量的英文镜像

- [ ] **Step 1: 复制结构，只换文本**

```bash
cp harnax-site/index.html harnax-site/en/index.html
cp harnax-site/capability.html harnax-site/en/capability.html
```

改 `<html lang="en">`；`canonical` 改 `/en/index.html`，`hreflang` 三条改成 `en` 自指 + `zh-CN` 指 `/index.html` + `x-default` 指 `/index.html`；站内所有 `href` 保持绝对路径且指向英文版自身（`/en/deploy.html`），导航集合必须与中文页一一对应，不得增删。

- [ ] **Step 2: 逐屏翻译，主标与副标按规格 §3**

H1：`Turn AI from one call into a system that keeps running`；副标：`Its own credentials, job description, tool belt, duty roster and teammates. Your only decision is which machine they live on.`；主按钮：`Deployment options` → `/en/deploy.html`。四张场景卡标题：`Assign work in the group` / `The night shift runs while you sleep` / `When one agent is not enough` / `It stops and waits for a signature`。四节机制标题：`Why a session stays on one machine` / `"Fired once" and "done once" are different problems` / `Skills are not prompt snippets` / `Five tables written, two readable`。每条成对限定语在英文侧同样出现（`on paper, not covered by tests`、`executor instance, not the gateway`、`connection errors before the first event` 等）。

- [ ] **Step 3: 英文页长度检查**

Run: `node harnax-site/tools/check.mjs --report`
Expected: `en/index.html` 与 `en/capability.html` 的禁词、数字、成对三项绿；两页对中文页的 parity 无差异项。若报 `h2:N!=M`，说明英文侧合并或拆了小标题，必须改回结构而不是改判定。

- [ ] **Step 4: Commit**

```bash
git add harnax-site/en/index.html harnax-site/en/capability.html
git commit -m "feat(site): mirror landing and capability pages in English"
```

---

### Task 8: 英文部署页与边界页

**Files:**
- Create: `harnax-site/en/deploy.html`
- Create: `harnax-site/en/boundaries.html`

**Interfaces:**
- Consumes: Task 5、Task 6 的结构与 id
- Produces: 英文镜像两页，hreflang 闭合

- [ ] **Step 1: `cp` 后改 head 与链接集合**，同 Task 7 Step 1 的三条规则。

- [ ] **Step 2: 英文文案**

`deploy.html`：H1 `Three ways to run it`；拓扑表列名 `Service / Role / Published to host / Limits`；步骤标题 `Order of operations`，措辞 `one script`（不得出现 `out of the box`）；账单四档标题 `What you must change`、`What you must add`、`What we did not build`、`What we deliberately turned off`；出站面 `Egress surface` 三类各一句，并保留 `no telemetry at runtime, metrics are pull-only`。
`boundaries.html`：H1 `What this platform does not do`；三节 `Not built`、`Claims that need qualifying`、`Where these statements come from`；`Not built` 表内 `fee is always zero` 一行的「你要怎么做」写 `Track usage by tokens, not money`。

- [ ] **Step 3: 全闸复跑**

Run: `node harnax-site/tools/check.mjs --report`
Expected: 退出码 0，八份页面全绿（禁词、数字、成对、parity、hreflang 闭合、对比度六类均无失败项）。

- [ ] **Step 4: Commit**

```bash
git add harnax-site/en/deploy.html harnax-site/en/boundaries.html
git commit -m "feat(site): mirror deployment and boundaries pages in English"
```

---

### Task 9: 渲染取证与托管说明

**Files:**
- Create: `harnax-site/README.md`
- Create: `harnax-site/tools/shoot.mjs`

**Interfaces:**
- Consumes: `check.mjs` 导出的 `PAGES`
- Produces: `harnax-site/.preview/` 下 16 张截图（8 页 × 桌面/移动）与断言结果

- [ ] **Step 1: 写取证脚本**

```js
// harnax-site/tools/shoot.mjs — headless Chrome evidence for all pages, both viewports.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdirSync } from 'node:fs';
import { PAGES } from './check.mjs';
const run = promisify(execFile);
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const OUT = 'harnax-site/.preview';
const PORT = process.env.PORT || '8781';
const paths = [...PAGES.map((p) => `/${p}`), ...PAGES.map((p) => `/en/${p}`)];
mkdirSync(OUT, { recursive: true });
for (const [tag, w, h] of [['desktop', 1440, 3200], ['mobile', 390, 2200]]) {
  for (const path of paths) {
    const name = `${(path.replace(/^\//, '').replace(/[/.]/g, '_' )).replace(/^en_/, 'en-')}-${tag}`;
    const url = `http://127.0.0.1:${PORT}${path}`;
    await run(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars',
      `--window-size=${w},${h}`, `--screenshot=${OUT}/${name}.png`, url]);
    const { stdout } = await run(CHROME, ['--headless=new', '--dump-dom', url]);
    if (!/<h1/.test(stdout)) throw new Error(`no h1 in ${path}`);
    for (const bad of ['TBD', 'TODO', 'Lorem', '{{']) if (stdout.includes(bad)) throw new Error(`${bad} in ${path}`);
    console.log('ok', name);
  }
}
```

- [ ] **Step 2: 起本地静态服务并跑取证**

```bash
(python3 -m http.server 8781 --directory harnax-site >/tmp/site-http.log 2>&1 &) ; sleep 1
node harnax-site/tools/shoot.mjs
```

Expected: 16 行 `ok …`，`harnax-site/.preview/` 下 16 个 PNG。服务进程留着下一步用，收尾时按 pid 停掉。

- [ ] **Step 3: 逐张眼校**

至少确认：桌面 1440 下四张场景卡与三档形态无折行溢出；英文侧标题未撑破卡片；深底 Hero 与数字条的文字对比肉眼可辨；移动端 390 下导航开关可用（`data-nav-toggle` 出现）；四张 SVG 在窄栏下等比缩放不裁切。发现问题回到 Task 2 改样式，不在页面里写局部补丁。

- [ ] **Step 4: 写 `README.md`**

三段：本地预览（绝对路径决定了必须走 HTTP，`python3 -m http.server --directory harnax-site` 一条）／托管方式（把 `harnax-site/` 整个目录作为静态根，或 nginx `root` 指过来；`/en/` 不需要 rewrite）／改一条文案要走哪道闸（`node harnax-site/tools/check.mjs --report`，数字必须在白名单里，新增数字要先回到规格补依据）。

- [ ] **Step 5: 全闸复跑 + Commit**

```bash
node harnax-site/tools/check.mjs && node --check harnax-site/assets/site.js
git status --short
git add harnax-site/README.md harnax-site/tools/shoot.mjs
git commit -m "docs(site): add preview/hosting notes and headless evidence script"
```

Expected: 闸门退出码 0；`git status --short` 里除进场时就存在的 `harnax-webui/src/pages/session/index.tsx` 与两份 `docs/superpowers/plans/*` 之外无新增未提交项；`harnax-site/.preview/` 若被 `git add` 盯上，先把 `.preview/` 追加进 `harnax-site/.gitignore` 再提交。

---

## Self-Review

规格覆盖核对（逐节 → 任务）：§1 页面清单 → T3–T8；§2.1 禁词 / §2.2 成对 / §2.3 白名单 → T1（数据表）+ 每个内容任务的 Step 尾闸；§3 屏 1–7 → T3；§4 三页 → T4/T5/T6；§5 视觉与文件 → T2 + T3 Step 1；§6 双语机制（绝对路径、hreflang、默认落地）→ T7/T8 Step 1；§7 验证五条 → T1（1、2）、T9（3、4）、T9 Step 3（5）；§8 不做的事 → 全站无对应任务，符合。

补记两处规格与计划的口径差：规格 §3 屏 6 写了「没有 Kubernetes」，但 §2.1 禁词含 `Kubernetes`，二者冲突——计划里 T3 Step 5 已定为改写成「未做容器编排化」，英文侧同样用 `no container-orchestration packaging`，规格那行以本节为准。规格 §2.2 渠道条要求出现「飞书、钉钉、企业微信」，与 §2.1 禁 `个人微信` 并存，T3 Step 4 与 T5 Step 4 的写法即为此。
