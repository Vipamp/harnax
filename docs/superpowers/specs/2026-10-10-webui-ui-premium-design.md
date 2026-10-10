# harnax-webui 界面高级化：主题层与外壳重做设计

日期：2026-10-10　状态：待复核
适用工程：`harnax-webui`（Umi Max + Ant Design Pro）

## 0. 结论先行

把观感从「玻璃底＋渐变＋彩色投影」换成「低饱和中性＋hairline 细边框＋紧字号＋高密度」，做法是**一份 TypeScript token 源、两投影、一套外壳组件重做**：

- 投影 A 喂 antd 的 `ThemeConfig`，管所有 antd 组件的内部皮肤；
- 投影 B 生成 CSS 自定义属性，**沿用 `src/global.less` 现有 45 个 `--vip-*` 与 10 个 `--glass-*` 变量名只换值，并补上 7 个「被读取却从未定义」的名字**（§2.3），合计 62 个名字，让 1324 处既有读取零改动换皮；
- 外壳组件（布局、卡片、筛选栏、页头、分页、登录、总览）逐个重做版式，业务行为一律不碰。

不引入新组件库、不引入 Tailwind、不引入字体文件、不做 1038 处内联样式的机械清扫。

## 1. 范围

### 1.1 在范围内

1. 主题 token 层（新增单一来源＋两投影）。
2. 布局外壳：侧栏、顶栏、内容区、面包屑、页脚、租户/主题入口。
3. 门面三屏：登录、欢迎总览、实体列表屏（智能体／MCP／技能／CLI 四屏共用一套卡片外壳）。
4. 上述外壳与三屏所依赖的共享组件：`EntityCard`、`ResponsiveCardGrid`、`SearchFilterBar`、`StyledProTable`、`DetailPageHeader`、`CardPagination`、`StatusSwitch`、`FormModal`、`RightContent`、`ThemeSwitcher`。

### 1.2 明确不做

| 不做 | 理由 |
|---|---|
| 更换组件库（shadcn／TDesign／MUI 等） | 现栈是 antd 5.29.3 ＋ pro-components 2.8.10，全仓 185 个手写文件按 antd API 写；换库等于重写，收益不比代价 |
| 引入 Tailwind 或任何原子 CSS 层 | 与 antd 的 cssinjs 运行时并存要重搭一层，且解决不了本设计要解决的三个主因（玻璃、渐变、彩色投影） |
| 逐处清扫 1038 处内联样式 | 绝大多数内联样式只读 `var(--vip-*)`，投影 B 换值后自动生效；清扫是无收益的大 diff |
| 引入 `@font-face` 与字体文件 | 见 §2.1，现配字体从未生效；本设计直接把 `fontFamily` 钉成系统栈，不新增外部依赖 |
| 改任何请求、表单校验、i18n、权限判断 | 视觉改造与行为改造必须可分离回退 |
| 会话聊天屏（`src/pages/session`）与监控两屏的深度重做 | 列入跟随策略（§7），本期只保证 token 换皮后不破版 |

## 2. 现状事实

统计口径统一为：`harnax-webui/src` 下的 `.ts`／`.tsx`／`.less`，跳过 `.umi`、`.umi-test`、`.umi-production`、`.umi-undefined`、`node_modules`；由 Node 脚本逐文件正则计数（`grep -r` 的 `--exclude-dir` 在本仓会漏放生成目录，已弃用）。手写文件共 185 个，其中 3 个是 `.d.ts`。各项正则：内联样式 `style=\{\{`；色字面量 `#[0-9a-fA-F]{3,8}\b` 且长度为 3／4／6／8；玻璃 `backdrop-filter\s*:|backdropfilter\s*:` 配 `gi`（含 `-webkit-` 前缀与 JSX 驼峰）；变量读取 `var\(--(?:vip|glass)-`；变量定义在 `src/global.less` 内按 `--(?:vip|glass)-[a-z0-9-]+\s*:` 计数。

### 2.1 版本与主题注入点

| 项 | 事实 | 锚点 |
|---|---|---|
| 构建框架 | `@umijs/max` 锁定 4.6.45（`package.json` 声明 `^4.0.7`） | `harnax-webui/package-lock.json` 的 `packages["node_modules/@umijs/max"]` |
| 组件库 | antd 锁定 5.29.3（声明 `^5.25.4`）；pro-components 2.8.10；antd-style 3.7.1；react 18.3.1 | 同上，键 `node_modules/antd` 等 |
| 唯一的 antd 主题入口 | `antd.configProvider.theme`，当前只设 `cssVar: true` 与 `fontFamily: 'AlibabaSans, sans-serif'` | `harnax-webui/config/config.ts` 的 `antd` 段 |
| 布局 token 入口 | `colorPrimary '#4f6ef7'`、`header.heightLayoutHeader: 64`、header／sider／pageContainer 一批 rgba 半透明值；**没有 `siderWidth`** | `harnax-webui/config/defaultSettings.ts` |
| 配置的字体从未生效 | 全仓零字体文件（`woff`/`woff2`/`ttf`/`otf` 命中 0）、零 `@font-face`，浏览器实际落到通用 `sans-serif` | `find harnax-webui -name '*.woff*' -o -name '*.ttf' -o -name '*.otf'`（排除 node_modules）结果为空 |

### 2.2 侧栏宽度与深色档的真实控制点

- 侧栏宽度由插件生成层硬编码：`harnax-webui/src/.umi/plugin-layout/Layout.tsx` 里 `siderWidth={256}` 与 `navTheme="dark"` 写死，而 `{...runtimeConfig}` 展开在其后。因此 `config/defaultSettings.ts` 改不动侧栏宽度，**只有 `src/app.tsx` 的 `export const layout`（`RunTimeLayoutConfig`）返回对象能覆盖**。
- 同一处还硬编码了 `navTheme="dark"`，实际生效值来自 `config/defaultSettings.ts` 的 `navTheme: 'light'`（经 runtimeConfig 覆盖）。这条决定了 §5.1 的改法必须走运行时配置，不能只改 less。
- 深色档由 class 驱动：`src/contexts/ThemeProvider.tsx` 用 `root.classList.add('dark')` / `remove('dark')` 切换 `<html>` 上的 `dark`。投影 B 必须继续挂在这个 class 上，不引入第二套开关。
- 侧栏初始为收起态：`src/app.tsx` 的 `layout` 里 `const [collapsed, setCollapsed] = useState(true)` 且 `breakpoint: 'md'`。本期不改这一行为，改的只是展开态宽度。

### 2.3 样式债务量化

| 指标 | 数值 | 说明 |
|---|---|---|
| `style={{` | 1038 处 / 102 文件 | 内联样式；多数只读变量，少数带字面色 |
| 十六进制色字面量 | 365 处 / 46 文件 | 其中 `.tsx` 251、`.ts` 5、`.less` 109 |
| `.less` 内的色字面量 | 109 处 / 3 文件 | `src/global.less` 85、`src/pages/session/components/ChatWindow.less` 22、`src/components/FormModal/FormModal.less` 2 |
| `--vip-*` 定义 | 去重 45 个，全部定义在 `src/global.less`（定义行 76 行，含深色覆盖） | 读取 1236 处 / 72 文件 |
| `--glass-*` 定义 | 去重 10 个，同文件（定义行 18 行） | 读取 88 处 / 6 文件 |
| 变量名账不平 | 定义 55 个／读取 53 个，交集只有 46 | **被读取但从未定义 7 个**：`--vip-fill-quaternary`、`--vip-bg-layout-rgb`、`--vip-bg-hover`、`--vip-bg-component`、`--vip-card-shadow`、`--vip-bg-card`、`--vip-primary-hover-rgb`——源码与 `harnax-webui/dist` 打包产物都搜不到任何一个的定义行，故这 7 个名字在 15 处读取（分布 7 文件）取值恒为空；另有 9 个定义了从未被读（`--vip-warning-hover`、`--vip-radius-xs`、`--vip-radius-xl`、`--vip-gradient-dark`、`--vip-gradient-success`、`--vip-gradient-warning`、`--glass-glow-success`、`--glass-glow-warning`、`--glass-glow-error`） |
| `createStyles` 使用 | 仅 4 文件 | `src/global.style.ts`、`src/components/RightContent/AvatarDropdown.tsx`、`src/components/HeaderDropdown/index.tsx`、`src/pages/user/login/index.tsx` |
| 手写深色规则 | `src/global.less` 共 3154 行，其中 `html.dark` 选择器 28 处、`html:not(.dark)` 9 处 | 深色靠手写对子维持 |
| `backdrop-filter` 声明 | 57 处 / 7 文件 | `src/global.less` 38、`src/pages/session/components/ChatWindow.less` 5、`src/pages/user/login/index.tsx` 6、`src/components/FormModal/FormModal.less` 2、`src/components/EntityCard/index.tsx` 2、`src/components/SearchFilterBar/index.tsx` 2、`src/pages/welcome/sections.tsx` 2（含 `-webkit-` 前缀与 JSX 驼峰写法） |
| `animation:`／`@keyframes` | 40 处 / 5 文件 | `src/pages/session/components/ChatWindow.less` 20、`src/global.less` 14、`src/components/EntityCard/index.tsx` 2、`src/pages/mcp/index.tsx` 2、`src/pages/token-monitor/index.tsx` 2 |
| 路由规模 | `config/routes.ts` 挂组件 28 条、带 `name` 27 条、`hideInMenu: true` 7 条、登录 1 条 `layout: false` | 除登录外 27 条共用同一层外壳 |
| 外部装饰图 | `src/app.tsx` 的 `bgLayoutImgList` 引 3 张 alipay CDN 图（移动端为 `[]`） | 唯一的外部运行时依赖 |

### 2.4 门面三屏现值

**外壳**：顶栏 64px、半透明白底；侧栏 256px、`colorMenuBackground: 'transparent'` 靠背后的装饰图与玻璃感撑住；菜单项默认高（antd `heightMenuHeight` 缺省 40 上下）；选中态 `rgba(238,241,254,0.6)` 浅靛底。

**登录**（`src/pages/user/login/index.tsx`）：左侧文案区含标语、描述、徽标与三枚特性标签；右侧卡片最大宽 420px、圆角 24px、`rgba(255,255,255,.7)` 玻璃底＋`blur(24px)`；标题 28px；两个 Tab 带 2px 下划线；输入框为 antd `size="large"`（实高 40px、圆角 6px）；主按钮实高 48px、圆角 12px、`linear-gradient(135deg,#1677ff,#36cbcb)` 加 `0 8px 24px rgba(22,119,255,.3)` 彩色投影；「其他登录方式」三枚圆形图标只挂了 hover 样式、没有点击处理，属于装饰性死 UI。

**总览**（`src/pages/Welcome.tsx` ＋ `src/pages/welcome/`）：单请求 `GET /api/admin/dashboard/overview`，组合问候条、今日四卡、趋势卡、排行卡、资产盘点条、待办条；共享 `glassCardStyle`（圆角 12、`var(--glass-bg)`、`blur(16px)`、`var(--glass-border)`、`var(--glass-shadow)`，定义在 `src/pages/welcome/sections.tsx`）；资产格用 `xs=12 sm=6 lg=3` 即一排八格。趋势卡只有近 14 天调用次数与费用两条序列，没有小时级数据。

**列表屏**（`src/components/EntityCard/index.tsx` ＋ `src/components/ResponsiveCardGrid/index.tsx`）：卡片圆角 20px、`minHeight: 280px`、玻璃底＋`blur(12px)`；hover 时 `translateY(-8px) scale(1.02)`、`transition: all 0.5s`、顶部 5px 渐变条带扫光、图标块由淡渐变翻成实心渐变；入场动画 `vipSlideUp` 按 `index * 60ms` 错峰；图标块尺寸随视口分六档（40／44／48／52／52／56）；统计区是 `repeat(auto-fit, minmax(64px,1fr))` 的渐变格；页脚是 1px 上边框加一条 hover 时展开到 100% 的渐变光泽线；操作区是三个图标按钮。每行卡片数由 `ResponsiveCardGrid` 现算：`minCardWidth = cardHeight * minAspectRatio`，`src/pages/agent/index.tsx` 传的是 `cardHeight={260} minAspectRatio={1.4} gutter={[16,16]}`，即最小卡宽 364px。

> `EntityCard` 有一条写在源码注释里的硬约束：`tagColor` 必须是十六进制字面量，因为组件内部按 `${tagColor}15` 这类模板拼接透明度，CSS 变量拼不进去。任何改造要保留这个契约，或连同拼接方式一起改。

### 2.5 为什么必须「两投影」而不是别名或分层

两条都在锁定版本的依赖源码里核过：

1. **嵌套 `ConfigProvider` 只能「同键改值」，做不到「一处定义两处生效」。** `node_modules/antd/lib/config-provider/hooks/useTheme.js` 里 `token: Object.assign({}, parentThemeConfig.token, themeConfig.token)`，`components` 段则逐组件 `Object.assign({}, 父, 子)`——都是浅合并。子层只写 `colorPrimary` 确实能保住父层其余键，但每一层各自持有的是一整套键位表，全局那一侧的 `--vip-*` 仍需另一份手写值。两份手写值就是漂移源，而本设计要求的是「改一个数，antd 与 1324 处读取同时变」。
2. **不能在全局 less 里用 `--x: var(--ant-x)` 别名。** `node_modules/@ant-design/cssinjs/lib/util/css-variables.js` 的 `serializeCSSVar` 返回 `"." + hashId + "{" + ... "}"`，即 `--ant-*` 只挂在带 hash 的类选择器下，不在 `:root`；全局表上取 `var(--ant-color-primary)` 取不到值。

所以正确形状只有一个：**同一份 TS token 源，编译期同时产出 antd 的 `ThemeConfig` 与全局 CSS 变量块**，两边不可能漂移，也不需要运行时读取 antd 的产物。

## 3. 设计判据

以下六条是评审每一处改动的裁决依据，任一条与实现冲突时以实现让路。

1. **中性优先。** 界面 90% 的像素由中性色承担：版面底、容器底、分隔线、正文四级灰。彩色只出现在主色动作、四档语义色（成功／警告／错误／信息）与选中态。
2. **hairline 而非投影。** 层级靠 1px 边框与底色差表达。除浮层（下拉、弹窗、气泡）保留一条柔和投影外，卡与行一律不带投影，不带彩色投影。
3. **单色与零渐变。** 主色不再做双色渐变，图标块、按钮、进度条、标识条一律单色或主色的低透明度档。渐变作为装饰的部分全部撤除。
4. **无玻璃。** 撤掉 `backdrop-filter`。玻璃是本工程「廉价感」与截图／打印失真的主要来源，且它让深浅两档无法用同一组 token 描述。
5. **紧字号、等宽数字。** 标题字号降一档、字距收紧（`letter-spacing: -.01em` 级），正文 13～14px；所有数字与编号走等宽字形（`font-variant-numeric: tabular-nums` ＋ 等宽字体栈），读数不再左右跳动。
6. **动效只留必要反馈。** 撤掉入场错峰、hover 位移缩放、扫光与光泽线；保留 140ms 级的颜色／边框过渡与浮层淡入。

## 4. Token 层设计

### 4.1 单一来源

新增 `harnax-webui/src/theme/tokens.ts`，导出一份纯数据：

```ts
export const palette = {
  light: { /* §4.5 左列 */ },
  dark:  { /* §4.5 右列 */ },
  radius: { ctl: 6, card: 8, panel: 12, pill: 999 },
  font: {
    base: '-apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Segoe UI", Roboto, sans-serif',
    mono: 'ui-monospace, "SF Mono", SFMono-Regular, Menlo, Consolas, monospace',
  },
  motion: { dur: 140, ease: 'cubic-bezier(.25,.46,.45,.94)' },
};
```

约束：`tokens.ts` 不 import antd、不 import React、不读运行时；它必须能被 Node 直接 `require`，供 §4.3 的生成脚本与 §9 的对账测试使用。

### 4.2 投影 A：antd ThemeConfig

`src/theme/antd-theme.ts` 从 `tokens.ts` 组装 `ThemeConfig`：`token` 段给 `colorPrimary`、`colorBgLayout`、`colorBgContainer`、`colorTextBase`、`colorBorder`、`borderRadius`、`fontFamily`、`fontSize`、控制高度与阴影；`components` 段按组件给 `Layout`、`Menu`、`Card`、`Table`、`Tabs`、`Button`、`Input`、`Tag`、`Segmented`、`Statistic`、`Pagination`。

注入方式：把 `harnax-webui/config/config.ts` 的 `antd.configProvider.theme` 换成引用该模块的构建期常量（Umi 配置文件在 Node 侧执行，可直接 import 项目内 TS 模块）。`cssVar: true` 保留——antd 自身组件仍受益于变量化。

`config/defaultSettings.ts` 的 `token` 段（header／sider／pageContainer）同步改为从 `tokens.ts` 取值，并把 `navTheme` 保持 `light`。

### 4.3 投影 B：CSS 变量块

新增 `harnax-webui/scripts/gen-theme-vars.mjs`，读 `tokens.ts`，产出 `harnax-webui/src/theme/vars.css`：

- `:root { … }` 放浅色档，`html.dark { … }` 放深色档；
- **键名沿用 `src/global.less` 现有的 45 个 `--vip-*` 与 10 个 `--glass-*`，一个都不删**，值全换；再补上 §2.3 那 7 个「被读取却从未定义」的名字，共 62 个键。这样 1236 ＋ 88 处既有读取（74 文件）全部自动换皮，一行读取都不用改；
- 新增语义名（`--hnx-accent`、`--hnx-ink-1..4`、`--hnx-line`、`--hnx-surface-*` 等）供新代码使用；旧名以「指向新名」的方式定义，例如 `--vip-primary: var(--hnx-accent)`，两者同源；
- `--glass-*` 一族保留名字但值改为不透明表面：`--glass-bg` → 容器底色，`--glass-border` → hairline 边框色，`--glass-shadow` → §4.5 的浮层投影或 `none`，`--glass-blur`／`--glass-blur-heavy` → `0px`，`--glass-glow-*` → 对应语义色的 8% 透明底（用于状态点，不再是发光）；
- 该文件由 `npm run build`／`npm run start` 前置生成（挂进 `prestart`／`prebuild`），并把生成物纳入版本库，保证 CI 与本地一致；
- `src/global.less` 顶部改为 `@import 'theme/vars.css'` 等价的引入方式（less 里用 `@import (plain)`），并**删掉 `src/global.less` 中原有的 94 行变量定义（`--vip-*` 76 行 ＋ `--glass-*` 18 行）与 85 处色字面量**，避免两处定义同名变量。

### 4.4 深色档

深色仍由 `src/contexts/ThemeProvider.tsx` 在 `<html>` 上加 `dark` class 驱动，投影 B 的 `html.dark` 块整体换值。`src/global.less` 里 28 处 `html.dark` ＋ 9 处 `html:not(.dark)` 手写对子按两类处置：

- 只是换底色／文字色／边框色的，删除，交给变量；
- 涉及结构（如隐藏装饰图、改布局）的保留，但改成读变量后的最小差量。

目标：`src/global.less` 的深色手写块从 37 处降到不超过 8 处，且不含任何十六进制字面量。

### 4.5 目标 token 表

| 语义 | 浅色 | 深色 |
|---|---|---|
| 版面底 `--hnx-bg-layout` | `#f7f8fa` | `#0e1013` |
| 容器底 `--hnx-bg-container` | `#ffffff` | `#16181d` |
| 浮层底 `--hnx-bg-elevated` | `#ffffff` | `#1d2026` |
| 悬停底 `--hnx-bg-hover` | `#f4f5f7` | `#1f232a` |
| 弱化底 `--hnx-bg-mute` | `#f1f2f5` | `#23272f` |
| 主色 `--hnx-accent` | `#4f6ef7` | `#4f6ef7` |
| 主色悬停 | `#3d5ae0` | `#6280ff` |
| 主色文字（深色档提亮） | `#4f6ef7` | `#8ea4ff` |
| 主色浅底 | `rgba(79,110,247,.08)` | `rgba(126,148,255,.14)` |
| 主色浅边 | `rgba(79,110,247,.24)` | `rgba(126,148,255,.32)` |
| 一级文字 | `#191c20` | `#e7e9ee` |
| 二级文字 | `#54596a` | `#a8aebe` |
| 三级文字 | `#8a9099` | `#7c8496` |
| 四级文字 | `#b7bcc5` | `#5a6172` |
| 边框 hairline | `#e6e8ec` | `#262a33` |
| 更浅分隔线 | `#f0f1f4` | `#1f232a` |
| 强边框 | `#d7dae0` | `#333845` |
| 成功 | `#16a34a` | `#3dd68c` |
| 警告 | `#d97706` | `#ffb224` |
| 错误 | `#e5484d` | `#ff6369` |
| 信息 | `#0e7490` | `#67e8f9` |
| 圆角（控件／卡片／面板／胶囊） | 6／8／12／999 | 同左 |
| 浮层投影 | `0 8px 28px rgba(16,24,40,.12), 0 2px 6px rgba(16,24,40,.06)` | `0 10px 30px rgba(0,0,0,.5), 0 2px 6px rgba(0,0,0,.35)` |
| 卡片投影 | `0 1px 2px rgba(16,24,40,.05)` | `0 1px 2px rgba(0,0,0,.4)` |
| 动效 | 140ms，`cubic-bezier(.25,.46,.45,.94)` | 同左 |

主色沿用现有 `#4f6ef7`，不二次改品牌识别；变化在于它不再做渐变、不再配彩色投影，且只出现在动作与选中态。

### 4.6 旧变量处置一览

| 旧名族 | 数量 | 处置 |
|---|---|---|
| `--vip-primary*`（含 `-hover`/`-active`/`-light`/`-lighter`/`-rgb`） | 6 | 换值指向主色族；`-lighter` 由渐变浅档改为主色 8% 底 |
| `--vip-bg-*`（`layout`/`container`/`elevated`/`primary-light`/`success-light`/`warning-light`） | 6 | 换值指向中性底与语义浅底；`-hover`／`-layout-rgb` 不在这一族（见末行） |
| `--vip-text-*`（`primary`/`secondary`/`tertiary`/`quaternary`/`primary-rgb`） | 5 | 换值指向四级灰 |
| `--vip-border*`（含 `-light`/`-secondary`） | 3 | 换值指向 hairline 三档 |
| `--vip-success/warning/error/info` 及各自 `-hover`/`-rgb` | 10 | 换值指向 §4.5 语义色 |
| `--vip-radius-*`（`xs`…`full`） | 6 | 收拢为 6／8／12／999 四档，旧名各自指向最近的一档 |
| `--vip-shadow-*`（`sm`/`md`/`lg`/`primary`） | 4 | `primary` 撤彩色投影改中性；其余按 §4.5 两档投影 |
| `--vip-gradient-*`（`primary`/`success`/`warning`/`dark`/`purple`） | 5 | **保留名字，值改为单色**，避免读取处出现无效值 |
| `--glass-bg`/`-bg-heavy`/`-border`/`-shadow` | 4 | 换值指向不透明表面与 hairline |
| `--glass-blur`/`-blur-heavy` | 2 | 置 `0px`，并删掉读它的 `backdrop-filter` 声明 |
| `--glass-glow-primary`/`-success`/`-warning`/`-error` | 4 | 换成语义色 8% 底（状态点用），不再是发光 |
| 被读取但从未定义（7 个） | 7 | 投影 B 逐一给出定义：`--vip-fill-quaternary` → 四级填充底、`--vip-bg-hover` → 悬停底、`--vip-bg-component` → 容器底、`--vip-bg-card` → 容器底、`--vip-bg-layout-rgb` → 布局底的 RGB 三元组、`--vip-primary-hover-rgb` → 主色悬停的 RGB 三元组、`--vip-card-shadow` → `--sh-1`。这一步会让当前取空值的 15 处读取第一次真正生效，落地要逐处看版式 |

以上合计 55 个现有定义名 ＋ 7 个补名 ＝ 62 个键，§9.3 的对账断言按这 62 个逐一核。

## 5. 外壳重做清单

### 5.1 布局外壳

| 项 | 现值 | 目标 | 落点 |
|---|---|---|---|
| 侧栏宽度 | 256（生成层硬编码） | 220 | 在 `src/app.tsx` 的 `layout` 返回对象里加 `siderWidth: 220`；`config/defaultSettings.ts` 改不动它 |
| 顶栏高度 | 64 | 56 | `config/defaultSettings.ts` 的 `token.header.heightLayoutHeader`，同时给 antd `Layout.headerHeight` |
| 侧栏底 | `transparent` ＋ 背后装饰图与玻璃 | 容器底不透明，右边界 1px hairline | pro 侧 `token.sider.colorMenuBackground` → 容器色；antd 侧 `components.Layout.siderBg` 同步 |
| 菜单选中态 | `rgba(238,241,254,.6)` 浅靛底 | 主色 8% 底、无投影、左侧 2px 主色条 | `components.Menu.itemSelectedBg` 与 `itemSelectedColor`；左侧条走 `itemBorderRadius` 归零后的一条 less 规则 |
| 菜单项高度 | antd 缺省（约 40） | 34 | `components.Menu.itemHeight` |
| 分组标题 | 与菜单项同色同字号 | 11px 等宽大写、字距 `+.04em`、四级灰 | `components.Menu.groupTitleFontSize` 与 `groupTitleColor` |
| 顶栏底 | `rgba(255,255,255,.72)` 半透明 | 容器底不透明＋下 hairline | `token.header.colorBgHeader` 与 `components.Layout.headerBg` |
| 背景装饰图 | 3 张 alipay CDN 图 | 撤除（`bgLayoutImgList: []`） | `src/app.tsx`；少一个外部运行时依赖 |
| 侧栏收起初始态 | `collapsed: true` | 不变 | 行为保持，避免用户习惯被打断 |
| 内容区内边距 | 24／24 | 20 | `token.pageContainer` 两值 |
| 面包屑 | 页内标题重复承载 | 面包屑承载页面名，页内不再重复大标题 | 外壳与页头协同（见 §5.3 的 `DetailPageHeader`） |

### 5.2 `EntityCard`（四屏共用）

| 子项 | 现值 | 目标 |
|---|---|---|
| 卡片 | 圆角 20、玻璃底、`blur(12px)`、`--glass-shadow` | 圆角 8、容器底不透明、1px hairline、`--sh-1` |
| hover | `translateY(-8px) scale(1.02)`、`transition all .5s`、边框换成 `${tagColor}40` | 只把边框换成强边框色，140ms 过渡；不位移不缩放不加投影 |
| 入场动画 | `vipSlideUp` 按 `index * 60ms` 错峰 | 撤除 |
| 顶部标识条 | 5px 三色渐变＋扫光 | 撤除；状态由开关与语义点承载 |
| 图标块 | 六档尺寸 40…56、淡渐变、hover 翻实心渐变、彩色投影、圆角 16 | 固定 28px 单色图标（主色或语义色），圆角 6，无底块；名称左对齐顶格 |
| 名称 | `clamp(14px,1.5vw,15px)`、`letterSpacing -.01em` | 14.5px／600、`letter-spacing: -.01em`，单行省略 |
| 类型标签 | 圆角 8、渐变底、`padding 3px 12px`、字重 600、hover 翻白字 | 中性 chip：圆角 6、弱化底、hairline、二级灰、等宽 11px；只有状态用四档语义色 |
| 统计区 | `auto-fit minmax(64px,1fr)` 渐变格、数字 22～26px／字重 800 | 撤格；改一行页脚读数：`工具 12 · MCP 3 · 技能 2 · CLI 1 · 会话 48`，数字等宽、标签三级灰 |
| 描述 | 固定高 40／60px、两行或三行截断 | 12.5px／1.6、两行截断，最小高 40px 保留（对齐需要） |
| 页脚 | 1px 上边框＋hover 展开的渐变光泽线；创建人、创建时间、三个图标按钮 | 去光泽线；左段 `编号 · 创建人`（等宽 11px，四级灰），创建时间移到详情页；右段操作收进 hover 才显形（`opacity` 0→1，140ms） |
| 开关 | `backgroundColor: tagColor` | 主色；关闭态用强边框色 |
| 卡高与每行数 | `minHeight 280`；`cardHeight 260 / minAspectRatio 1.4 / gutter [16,16]` → 最小卡宽 364 | `minHeight 200`；`ResponsiveCardGrid` 传 `cardHeight={200} minAspectRatio={1.2} gutter={[16,16]}` → 最小卡宽 240；按 1440 视口、侧栏 220、内容内边距 20 推导容器宽约 1180，`floor((1180+16)/(240+16))` = 4 列一排（推导值，落地要实测每行卡片数） |

`tagColor` 契约（§2.4）处置：prop 与「必须是十六进制字面量」的约束保留；带透明度后缀的拼接目标只留在两处——状态点与开关，其余随渐变、彩色投影、渐变格一起撤除。新代码优先传四档语义色，不传品牌色。

### 5.3 其余共享组件

| 组件 | 目标 |
|---|---|
| `SearchFilterBar` | 去玻璃与投影；控件高 32、圆角 6；筛选项与创建按钮同一基线，右对齐 |
| `StyledProTable` | 表头 11px 等宽大写、四级灰；行高 44；分隔线用更浅档；hover 只换底色；去斑马纹 |
| `DetailPageHeader` | 标题 18px／600；返回箭头与标题同基线；页内不再重复面包屑里的页面名 |
| `CardPagination` | 现有 9 处色字面量清零；页码 28px 方块、圆角 6、等宽数字；当前页主色实底白字 |
| `StatusSwitch` | 状态点 6px 圆点＋文字，四档语义色；撤发光与渐变 |
| `FormModal` | 弹窗保留浮层投影；圆角 12；`src/components/FormModal/FormModal.less` 的 2 处色字面量清零；表单项控件高 32 |
| `RightContent`／`ThemeSwitcher` | 图标按钮 30px、hairline 描边、无投影；深浅切换图标不换语义 |
| `HeaderDropdown`／`AvatarDropdown` | 下拉面板圆角 10、容器底不透明、一条浮层投影 |

## 6. 门面三屏

### 6.1 登录（`src/pages/user/login/index.tsx`）

改：左右分栏由「粉彩渐变带＋15 颗粒子＋玻璃卡」换成「左侧近白版面＋右侧容器底，中间 1px hairline 分栏」；logo 砖与主按钮的第二条蓝绿渐变（`#1677ff → #36cbcb`）改单色并去 24px 彩色投影；输入框 40 → 38、主按钮 48 → 38、圆角 24/20/16/12 → 8/6、表单列宽 420 → 322；标语与描述降为二级／三级灰、去掉渐变徽标条；「其他登录方式」三枚无 `onClick` 的装饰图标撤除；5 处 `backdrop-filter` 全删。

不碰：`Form` 字段与校验规则、验证码组件、`handleSubmit`、租户与登录态写入、`useIntl` 文案 key、深浅切换与移动端降级逻辑。

验收：无 `backdrop-filter`；无渐变；控件高 38；表单列宽 322；提交链路与改造前逐字一致。（§2.3 记的 6 处即这 5 条声明，其中一条带 `WebkitBackdropFilter` 配对写法。）

### 6.2 欢迎总览（`src/pages/Welcome.tsx` ＋ `src/pages/welcome/`）

改：问候条、今日四卡、趋势卡、排行卡、资产盘点条、待办条六块统一挂在一个容器里，块间 1px hairline 分隔，取代「每块一张玻璃卡」；`glassCardStyle` 换成 `cardStyle`（圆角 8、容器底、hairline、`--sh-1`）；今日四卡的数字统一一级墨色，彩色只留给同比 delta；趋势卡保留 14 天调用次数与费用两档切换，网格线降到更浅档、坐标数字等宽；资产盘点条由 `lg={3}` 八格改为 hairline 分隔的一行四格两排；待办条的按钮组撤装饰图标。

不碰：`GET /api/admin/dashboard/overview` 单请求、时间窗由后端推的口径、刷新入口、排行数据装配（`src/pages/welcome/metrics.ts`）、全部 i18n key。

验收：本屏 `backdrop-filter` 归零；卡片圆角只有 8 与 12 两档；四卡数字基线对齐；不新增任何请求。

### 6.3 实体列表屏（智能体／MCP／技能／CLI）

改：见 §5.2 的 `EntityCard` 目标值；页面标题栏的装饰图标去掉，页面名由面包屑承载；`ResponsiveCardGrid` 参数按 §5.2 换档。

不碰：列表请求与分页参数、`hasOperationPermission` 权限判断、`DeleteButton` 的二次确认、启停回调、四屏的数据装配。

验收：四屏共用同一份卡片外壳，改一处四屏同变；卡片最小宽 240、`minHeight` 200（与 §5.2 的参数一致），每行卡片数在 1440 与 1920 两档实测并回填；hover 不产生位移。

## 7. 其余屏的跟随策略

token 换皮（§4.3）覆盖不到的，是各屏自己写的字面色与装饰。按字面量密度排优先级，逐屏一轮改完，每屏一次提交：

| 顺位 | 文件 | 色字面量 | 处理 |
|---|---|---|---|
| 1 | `src/pages/token-monitor/index.tsx` | 40 | 图表配色改成语义色族常量，去渐变填充 |
| 2 | `src/pages/user/login/index.tsx` | 39 | §6.1 |
| 3 | `src/pages/mcp/index.tsx` | 22 | 列表屏，走 §5.2 |
| 4 | `src/pages/mcp/detail.tsx` | 18 | 详情外壳＋`OAuthPanel` |
| 5 | `src/pages/session/components/ChatWindow.tsx` ＋ `src/pages/session/components/ChatWindow.less` | 14 ＋ 22（另有 20 处动画、5 处玻璃） | 单列一轮，本期只做玻璃与色清零，不动气泡版式 |
| 6 | `src/pages/skill/detail.tsx`、`src/components/CardPagination/index.tsx`、`src/pages/skill/components/RepositoryList.tsx` | 9／9／8 | 随所属屏 |

判据：改完一屏，该屏的 `#[0-9a-fA-F]{3,6}` 命中数与 `backdrop-filter` 命中数都要归零（图表数据色除外，它必须留在 TS 常量里而不是样式里）。

## 8. 分期与顺序

| 期 | 内容 | 产出 | 依赖 |
|---|---|---|---|
| P0 | `src/theme/tokens.ts` ＋ 两投影 ＋ 生成脚本 ＋ 删 `src/global.less` 里的同名定义 | 全仓换皮，深浅两档由 token 派生 | 无 |
| P1 | 布局外壳（§5.1） | 侧栏 220、顶栏 56、菜单 34、装饰图撤除 | P0 |
| P2 | `EntityCard` ＋ `ResponsiveCardGrid` ＋ §5.3 的共享组件 | 四屏列表统一 | P0 |
| P3 | 登录与总览（§6.1、§6.2） | 门面两屏 | P0、P1 |
| P4 | 其余屏按 §7 顺位逐屏 | 字面量与玻璃清零 | P0 |

P0 必须先落地并单独过一次全量构建：它一行组件都不改就能让 1324 处读取换皮，是判断「token 层是否自洽」的最早时机，也是唯一能整体回退的点。

## 9. 验收闸

1. **类型与构建**：`npm run tsc` 零错；`npm run build`（`max build`）成功。
2. **Lint**：本轮改过的文件逐个跑 `npx @biomejs/biome lint <file>`。**禁止 `biome check --write`**——它会在全仓铺下上千行无关改动。
3. **变量对账**：新增一个 Node 测试，读 `src/theme/tokens.ts` 与 `src/theme/vars.css`，断言 §4.6 的 62 个键在 `:root` 与 `html.dark` 两块都存在且值与 token 表一致；再断言 `src/global.less` 里不再出现 `--vip-*`／`--glass-*` 定义行。
4. **样式债务**：按 §2 的正则口径重跑统计（这份脚本随 P0 落到 `harnax-webui/scripts/ui-style-debt.mjs`，新增，口径变了要同步改它），门禁为——玻璃声明数从 57 降到 5（P0～P3 覆盖 52 处，余 5 处全在 `src/pages/session/components/ChatWindow.less`，由 §7 第 5 位那轮清零）；`src/global.less` 的色字面量从 85 降到 0；`animation:`／`@keyframes` 在 P0～P3 涉及文件内归零。
5. **对比度**：对三屏的每类文字与它的实际底做 WCAG AA 扫描（正文 4.5:1、大字与图标 3:1），深浅两档各扫一次。底要按布局树推出来的那一层取，不能拿 token 名义值代替。
6. **视觉对拍**：以 `prod_doc/prototypes/webui-shell-premium.html` 为基准，三屏 × 深浅 × 改造前后共 12 张图，逐屏眼校＋像素直方图分箱核主色档；无头通道不稳定时用离屏 WebKit 探针，且注意带 `backdrop-filter` 的帧在该探针上会快照失败。
7. **行为不变**：三屏的请求序列、表单校验结果、i18n key 集合、权限判定分支与改造前逐字一致；`git diff` 里不得出现 `src/services/`、`src/locales/`、`handleSubmit` 相关改动。

## 10. 风险与回退

| 风险 | 影响 | 处置 |
|---|---|---|
| 删 `src/global.less` 里 94 行旧定义时漏掉某个只在深色块里定义的变量 | 该变量在浅色档变 `unset`，表现为透明底或继承色 | §9.3 的对账测试先跑；再把 62 个名字逐一在 `:root` 与 `html.dark` 各定义一次，不允许只在一档定义 |
| 补上 7 个从未定义的名字后，当前取空值的 15 处读取第一次真正生效 | 这几处版式会肉眼可见地变（`src/components/FormModal/FormModal.less`、`src/global.less`、`src/pages/session/components/ChatWindow.less`、`src/pages/mcp/detail.tsx`、`src/pages/mcp/components/OAuthPanel.tsx`、`src/pages/api-key/components/RawKeyModal.tsx`、`src/pages/channel/components/UpdateForm.tsx`） | 给值前逐处确认这些读取想要的语义；只要有一处看不出意图就先把那处读取改成显式新名（§4.3 的 `--hnx-*`）再给值，别拿旧名硬凑 |
| antd `components` 段覆盖不全，某组件仍带旧观感 | 局部不一致 | 逐项列在 §5.3，按组件补 `components` 段而不是在 less 里打补丁 |
| `siderWidth` 走运行时配置，生成层版本升级可能改展开顺序 | 侧栏宽度回到 256 | §9.6 的对拍里侧栏宽度是可测项；同时在 `src/app.tsx` 的 `layout` 里加一行注释说明这条覆盖是必需的 |
| `EntityCard` 的 `tagColor` 拼接透明度契约被破坏 | 图标与开关颜色变 `NaN` 色／无效值 | 保留 prop 类型与注释；新增一处单测断言传入非法值时不产生无效样式 |
| 会话聊天屏改动面大（20 处动画、5 处玻璃、36 处字面量） | 破版风险最高 | 排到 §7 第 5 位单独一轮，本期不动其气泡版式 |
| 回退 | — | P0 一次提交、P1～P4 每期一次提交；P0 可整体 revert 而不牵动任何组件文件 |

## 11. 基准样机

`prod_doc/prototypes/webui-shell-premium.html` 是本设计的可视基准：四节内容（设计变量、登录、布局外壳＋总览、列表屏），顶栏两枚开关切「新版／现状复刻」与「深／浅」，复刻帧的每个数值逐条抄自上述锚点。它不接后端、不进构建，实现期作为对拍基准（§9.6）。

已核到的实测点：复刻登录卡 420×700／圆角 24、主按钮 48／圆角 12、输入 40／圆角 6；新版深色档 `--bg-layout #0e1013`、容器 `#16181d`、hairline `#262a33`、一级文字 `#e7e9ee`；新版列表行高 47。
