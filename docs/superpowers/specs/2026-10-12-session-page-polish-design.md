# harnax-webui 会话页视觉与动效打磨设计

日期：2026-10-12　状态：待复核
适用工程：`harnax-webui`（Umi Max + Ant Design Pro）
方向稿：`/tmp/session-polish/index.html`（本地交互演示，不进仓库；本文已把全部关键值转写入 §3，方向稿丢失不影响实施）

## 0. 结论先行

会话页（`/` 全屏聊天壳）功能已基本完成，本轮把它从「功能可用」推到「高级感＋动感」。风格取**克制底＋三处点睛动效**：中性色面＋hairline 细边作底座，渐变与高饱和只出现在首屏、发送/流式、消息侧标识三处；错峰编排只用于首屏与消息行入场。

1. **首屏编排**——空会话第一屏从「渐变图标＋文字 chips」换成「活标识（缓转光环＋呼吸核）＋错峰入场＋四张能力引导卡」，点卡即发送示例问题；无会话态与列表空态换用同一套 orb 语言。
2. **消息与流式**——消息行上浮入场（240ms）；工具卡收敛为「摘要行＋状态点＋可折叠体」；流式＝思考点呼吸 → 逐字＋闪烁光标；发送钮在流式中变方形停止钮＋呼吸环。
3. **输入区去堆叠**——8 个文字按钮一行改为「动作 chips＋会话设置菜单」：图片／压缩上下文／停止沙箱／清空记录 4 枚图标 chips ＋「会话设置」菜单收纳 深度思考／显示思考过程／联网搜索／开启计划／工具执行权限；发送钮改渐变小圆钮。
4. **统一动效体系**——`global.less` 增加 3 档时长＋1 条缓动的运动 token；会话页样式内实现 `prefers-reduced-motion` 降级。

只动 `src/pages/session/` 的呈现层与 i18n 文案；请求、SSE 协议、权限判定、命令通道全部不碰。

## 1. 范围

### 1.1 在范围内

1. 首屏两种形态：无会话态（右侧空态）、会话空态（ChatWindow 无消息）的编排与引导。
2. 消息流：用户／助手／团队成员气泡、工具调用卡（含合并卡与确认卡）、计划卡、流式输出、时间戳、回到底部按钮。
3. 输入区：chips 重组、「会话设置」菜单、发送/停止钮、图片预览。
4. 会话列表：分组、行信息层级、选中态、列表空态。
5. 顶栏：保持全部功能入口，视觉对齐新语言。
6. 运动 token 段加入 `src/global.less`；会话页样式内的 reduced-motion 降级。
7. 新增文案的 zh-CN／en-US i18n key（§4 清单）。

### 1.2 明确不做

| 不做 | 理由 |
|---|---|
| 移动端适配 | 已拍板暂不考虑；仅保证不破坏 `ChatWindow.less` 既有移动端断点块（约 2136 行起） |
| 新组件库／Tailwind／字体文件 | 沿用既有口径；全部改动基于现有 `--vip-*`／`--glass-*` 变量 |
| 后端与协议改动 | 列表第二行用现有 `sessionDescription`／`updateTime`，不新增接口或字段 |
| 行为类既有缺陷（切会话不断流、离开页面不 INTERRUPT、无重连等） | 属行为修复，与本轮的呈现层改造解耦，另行处理 |
| 工具调用时长显示 | 消息数据里没有时长字段（§2）；方向稿的「完成 · 1.2s」仅示意，落地只显示状态词 |
| 新增输入提示行「Enter 发送…」 | 与输入框 placeholder 现有文案重复，不引入 |
| 方向稿的演示控制条与演示逻辑 | 仅作视觉参照 |
| 图片上传接口、slash 命令表等 | 与本轮无关 |

## 2. 现状事实

锚点均为当前工作树（kotlin-dev）实测位置。

| 项 | 事实 | 锚点 |
|---|---|---|
| 页面结构 | 左会话列表 300px（内联样式）＋顶栏＋ChatWindow＋五类弹层（新建／详情／工作区／产物／技能） | `src/pages/session/index.tsx`（513 行） |
| 消息与输入区 | 全部在 CSS Modules，样式 45KB；消息渲染、空态、输入区、工具卡组件族都在此 | `src/pages/session/components/ChatWindow.tsx`（3807 行）、`ChatWindow.less` |
| 空态现状 | 72px 渐变方块图标＋标题＋副标＋4 枚文字建议 chips（`SUGGESTIONS` 四条问题文案），点击即发送 | `ChatWindow.tsx:3412-3426`、`:579-584`；`.emptyState` 族 `ChatWindow.less:140-203` |
| 消息行入场 | `.messageRow` 已有 `fadeInUp .3s ease-out both`（所有行重挂即重播） | `ChatWindow.less:206-211` |
| 助手气泡 | 毛玻璃（`--glass-bg`＋`blur 12px`）；用户气泡渐变；圆角 18px 不对称 | `ChatWindow.less:246-281` |
| 工具卡现状 | `ToolCallCard`＝头部「Call Tool: 名 + 状态词」＋展开体；`MergedToolCard`＝合并摘要；`ToolConfirmCard`＝确认按钮组；三态映射 calling／completed／interrupted | `ChatWindow.tsx:327-329`（状态表）、`:417-422`（卡结构） |
| 工具调用无时长字段 | 消息段只有 `toolName`／`toolId`／`toolResult`，事件与回放都不带耗时；全文件无 elapsed/duration 计算 | `ChatWindow.tsx:58-68`；针对 `elapsed|durationMs|costMs` 零命中 |
| 输入区现状 | 一行 8 个文字按钮：图片、深度思考、联网搜索、开启计划、工具权限（下拉 5 档）、压缩上下文、停止沙箱、清空记录（后三者危险样式）；发送＝antd Button，流式中变 danger 停止 | `ChatWindow.tsx:3583-3789` |
| 无会话态现状 | 56px 渐变方块＋两行文案，无输入区 | `index.tsx:448-470` |
| 列表空态现状 | antd `Empty`「暂无会话」 | `index.tsx:281-283` |
| 列表行现状 | 标题＋团队 Tag＋悬浮操作钮；选中态硬编码 `rgba(99,102,241,…)`（旧靛蓝色值） | `index.tsx:301-319` |
| 列表字段 | `SessionItem` 有 `title`／`sessionDescription`／`updateTime`／`createTime`／`teamId`，**无最后一条消息摘要** | `src/services/ant-design-pro/typings.d.ts:134-156` |
| 令牌现状 | `--vip-*` 亮/暗两套＋`--glass-*`；有 `--vip-primary-rgb`、`--vip-shadow-*`、`--vip-radius-*`、`--vip-gradient-primary`；**没有运动 token**（时长各文件硬编码） | `global.less` `:root`（7-90 行）、`html.dark`（93-146 行） |
| 消息列几何 | 聊天内边距 `24px 28px 12px`，无 max-width（宽屏下气泡无限拉伸） | `ChatWindow.less:74` |
| dayjs | 已用于全站绝对时间格式化；无 relativeTime 插件 | `src/pages/memory/drafts.tsx:18` 等 |

## 3. 设计

### 3.0 视觉语言总则

- **三处品牌时刻**（渐变/高饱和只允许出现在这三处）：① 首屏——orb、背景光斑、引导卡四色图标；② 发送/流式钮——渐变圆钮与呼吸环；③ 消息侧标识——用户气泡渐变底、助手头像渐变块。状态 pill 的低透明语义底不属高饱和，不受此限；其余一切面为中性色＋hairline 细边＋低阴影。
- **毛玻璃收缩**：助手气泡改实底柔面（亮档 `--vip-bg-layout`，暗档 `--vip-bg-elevated`，比聊天底面各深/浅一档）；`--glass-*` 留给本轮范围外的既有弹层，不在此扩张使用。
- **动效纪律**：交互反馈走 §3.5 运动 token；入场动画只播一次；循环动画只允许出现在「有语义的活着的东西」上（orb 光环与呼吸核、工具调用中的状态点、流式光标）；`prefers-reduced-motion: reduce` 时全部降级。
- **不新增滚动容器结构**：全部改动落在既有 DOM 骨架与其类名上，不重排组件树。

### 3.1 首屏编排（会话空态，ChatWindow）

结构（自上而下，居中列，整体 `padding-bottom: 6vh`）：

```
.welcome（居中列）
├─ 背景光斑 480×300，radial-gradient(closest-side, rgba(primary,.10), transparent 70%)，位于顶部 12%
├─ orb 76px：光环（conic-gradient 慢转 7s 线性无限）+ 核心（品牌渐变底＋白色 spark 图标 22px）
├─ h1 22-23px/700「今天想让它帮你做什么？」
├─ 副标 13.5px 三级色「从下面选一个开始，或直接输入你的问题」
└─ 引导卡 2×2 网格（宽 min(560px, 100%)，gap 10px）
```

编排时间轴（进入空态即重播；会话切换重建节点自然重播）：

| 元素 | 动画 | 时长/缓动 | 延迟 |
|---|---|---|---|
| 背景光斑 | 淡入 | 1.2s / ease-out-ish | 0 |
| orb | 上浮入场（`scale .8→1` ＋ `translateY 8px→0` ＋淡入） | .7s / token ease | 0 |
| h1 | 上浮 12px＋淡入 | .55s / token ease | .12s |
| 副标 | 同上 | .55s / token ease | .2s |
| 卡 1-4 | 同上 | .55s / token ease | .3s / .38s / .46s / .54s |
| 输入卡 | 光晕脉冲一次（35% 时 `0 0 0 4px rgba(primary,.12)` ＋ shadow-md，回到静置） | 1.6s / token ease | .55s |
| 光环旋转 | `rotate 0→360°` | 7s / linear / **无限** | 0 |
| 核心呼吸 | `scale 1→1.045→1` | 3.2s / ease-in-out / **无限** | 0 |

要点：
- orb 光环用 `conic-gradient(from 0deg, rgba(primary,0), rgba(primary,.55), rgba(primary,0) 55%)`，`::after` 用页面底色内缩 2px 挖空成环；核心 `inset: 9px`、`box-shadow: 0 6px 22px rgba(primary,.35)`。
- 环形与核心的无限动画是「它在等你」的语义，保留；不做循环的次数限制。
- `prefers-reduced-motion` 下：编排全部直出（`animation-duration:.001s; animation-iteration-count:1`），光环与呼吸核也停止（见 §3.5）。
- 引导卡：图标块 30px 圆角 9 四色（品牌／警告橙／青／紫），标题 13.5px/600＋示例问题 12px 三级色两行式；hover 上浮 2px＋边框 `rgba(primary,.35)`＋shadow-md＋图标 `scale 1.08 rotate -3°`；press `scale .99`；**点击＝把该卡示例问题填入输入框并发送**（复用现有 `doSend` 路径，行为与现建议 chips 一致）。
- 四张卡（能力真实性约束：不承诺尚不存在的能力；编排/定时能力未上线，故不出现「定时任务」）：

| 卡 | 标题 | 点击发送的示例问题 |
|---|---|---|
| 1 | 写代码 | 写一个 Python 排序算法，并逐行解释 |
| 2 | 读文件 | 读一下工作区里的文件，帮我总结要点 |
| 3 | 查资料 | 联网搜索今天的科技新闻，整理成三条要点 |
| 4 | 制定计划 | 先帮我列一个实现计划，确认后再动手 |

- 原 `SUGGESTIONS` 四条与 `emptyTitle`／`emptySubtitle` 文案被本组取代（i18n 处置见 §4）。

### 3.2 无会话态、列表与列表空态（index.tsx，新样式落 `index.less`）

新增 `src/pages/session/index.less` 承载外壳新样式（现列表/顶栏为内联样式，新增样式不再堆内联）。

**无会话态（右侧）**：小号 orb（56px，结构与空态同款、参数等比缩小）＋标题「右侧还没有可进入的会话」＋副标「点左侧「新建」开一个会话」＋禁用态输入卡（占位文案「先新建一个会话，再开始对话…」）。禁用输入卡**可点击，点击打开新建会话弹窗**（把提示位变成引导动作）。

**会话列表**：
- 行：第一行＝标题（14px；选中 600／常态 400）＋团队 Tag；第二行＝meta 行：左侧 `sessionDescription`（非空才显示，单行省略）＋右端时间。
- 时间格式：今天内 `HH:mm`，今年内 `MM-DD`，跨年 `YYYY-MM-DD`（dayjs 绝对格式，不引入 relativeTime 插件）。
- 选中态：左侧 3px 品牌色条＋底 `rgba(var(--vip-primary-rgb), .06)`＋边 `rgba(var(--vip-primary-rgb), .18)`（替换现在硬编码的 99,102,241 值）。
- 行 hover：底色 `--vip-bg-layout` 档微亮。
- 分组：按 `updateTime` 分「今天」「更早」两组，组标签 12px 三级色；解析失败归「更早」。
- 列表空态：点状 orb（小圆球三点）＋「还没有会话」＋「点「新建」开一个」，替换 antd `Empty`。

**顶栏**：保留全部入口与行为（工作区／产物／本会话技能／上下文 pill）；仅将渐变图标块与标题间距、按钮字号对齐新语言，无结构变化。

### 3.3 消息流（ChatWindow）

- **消息行入场**：`.row` 上浮 12px＋淡入，`--vip-motion-base`（240ms）＋ token ease，替换现有 `fadeInUp .3s`。行为保持：历史重挂（切会话）整屏一起升入。
- **头像**：统一 30-34px 圆角 9-10 方块；助手＝品牌渐变＋机器人图标；用户＝中性深色底＋用户图标（不再用第二块饱和蓝，把渐变让给用户气泡）；团队成员＝现有 `--vip-info` 渐变（语义不变）。
- **用户气泡**：渐变底保留；圆角 `16px 16px 5px 16px`；`box-shadow: 0 4px 14px rgba(primary,.22)`。
- **助手气泡**：实底柔面（§3.0），圆角 `16px 16px 16px 5px`，`width: fit-content`，内部段落间距 8px。
- **时间戳**：11px 四级色，位于气泡列下方，用户侧右对齐（现状保持，仅对齐微调）。
- **工具卡（ToolCallCard／MergedToolCard／ToolConfirmCard 三件同族换皮，数据与行为不变）**：
  - 头部一行：24px 圆角 7 工具图标块（`rgba(primary,.1)` 底）＋ 工具名 13px/600 ＋ 参数摘要（如路径/命令，12px 等宽、三级色、单行省略）＋ 右侧状态 pill ＋ 折叠箭头；
  - 状态 pill：调用中＝`rgba(primary,.1)` 底＋6px 呼吸点（`breath 1.4s ease-in-out` 无限）＋「调用中」；完成＝`rgba(success,.1)` 底＋「完成」；已中断＝`rgba(warning,.1)` 底＋「已中断」。**不显示时长**（§1.2）；
  - 折叠体：`grid-template-rows 0fr→1fr` 过渡（240ms）替代现在的条件挂载瞬切；体内为等宽 12px/1.7 `pre`、软底、细上边线；
  - 确认卡按钮组沿用现有整批「允许／拒绝」行为，换新语言按钮样式（主＝渐晕描边、危险＝error 语义）。
- **计划卡**（isPlan 系列）与团队成员气泡（member 卡）：结构不动，间距/圆角/状态色对齐 3.0 语言；成员头行 hover 有底色反馈，状态 pill 语义色不变（running 品牌／awaiting 警告／done 成功／failed 错误）。
- **流式输出**：
  - 思考点：三点 6px 四级色，`breath 1.2s ease-in-out` 无限，第 2/3 点延迟 .15s/.3s；
  - 逐字光标：2×15px 品牌色竖条，`blink 1s steps(1)` 无限，位于流式文本末端；
  - 替换现有 `inlineLoading` 三点样式，统一为一套呼吸点语言。
- **回到底部按钮**：36px 圆形、实底细边＋shadow-sm，出现/消失带 `--vip-motion-fast`（150ms）淡入，位置保持右下悬浮。

### 3.4 输入区（ChatWindow）

- **首层 chips 行**（28px 高、圆角 8、12.5px 文字＋14px 图标；hover 软底、press `scale .95`；激活＝品牌色底 `rgba(primary,.09)`＋品牌色文字）——动作与配置分离：可执行动作常驻 chips，配置类开关收进「会话设置」菜单：
  1. 图片（保数量角标与 vision 禁用提示，行为不变）
  2. 压缩上下文（`compacting` 时转圈；`loading || compacting` 禁用；tooltip 保留）
  3. 停止沙箱（中性 chip；前置 `getWorkspaceStatus` 检查＋Modal 确认，行为不变）
  4. 清空记录（中性 chip；Modal 确认弹窗，行为不变）
  5. **会话设置**（`SettingOutlined`，下拉菜单带标题行；激活条件＝`enableThink || enableSearch || enablePlan || permissionMode !== 'DEFAULT'`）——收纳：
     - 深度思考（勾选态 `enableThink || modelThinkingMode === 2`；不支持／强制开启时提示不变，点击切换并发送既有 ENABLE/DISABLE thinking 命令＋失败回滚）
     - 显示思考过程（勾选态 `showThinking`，点击即切换；取代原「深度思考」chip 的右键菜单）
     - 联网搜索（勾选态 `enableSearch`；不支持时警告不变，点击切换并发送既有 ENABLE/DISABLE search 命令＋失败回滚）
     - 开启计划（勾选态显示 `enablePlan`，点击发送既有 ENABLE/DISABLE plan 命令，行为不变）
     - 工具权限（显示当前档位，5 档单选，选择即发 PERMISSION 命令＋失败回滚，行为不变）
- **发送钮**：34px 圆形、`--vip-gradient-primary`、`box-shadow 0 4px 14px rgba(primary,.32)`；hover `scale 1.06`＋阴影加深、press `scale .94`；空输入禁用 `opacity .45`。
- **流式中的发送钮**：变为软底方形停止块（11px 圆角 3 方块）＋外圈 1.6s 呼吸环（`scale .9→1.35`、透明度 `0.9→0`，`border 2px rgba(primary,.4)`）；点击停止行为不变。
- **输入卡本体**：圆角 `--vip-radius-xl`、细边；`:focus-within` 时边 `rgba(primary,.55)`＋`0 0 0 4px rgba(primary,.10)` 光晕；空态首屏的脉冲见 §3.1。
- **消息列与输入卡统一 `max-width: 860px` 居中**（现状宽屏无限拉伸）；左右 padding 保持 28px。

### 3.5 运动 token 与降级（global.less ＋ 会话页样式）

`global.less` 的 `:root` 追加（`html.dark` 不覆盖，运动与主题无关）：

```less
/* 动效 */
--vip-motion-fast: 150ms;   /* hover/press 等即时反馈 */
--vip-motion-base: 240ms;   /* 位移、展开、气泡入场 */
--vip-motion-slow: 420ms;   /* 大面淡入 */
--vip-motion-ease: cubic-bezier(0.22, 1, 0.36, 1);
```

首屏编排的错峰节奏与 rise 550ms 属编排参数（一次性），在空态样式内就地定义，不进 token。

其余各处的 hover/press/颜色过渡一律取 `--vip-motion-fast`，位移类过渡取 `--vip-motion-base`（§3 中未单独标时长的交互反馈均按此短式；循环语义动画的时长不属此列）。

`prefers-reduced-motion: reduce` 降级**限定在会话页自身范围**实现（ChatWindow.less 与 index.less 内，不做全仓 blanket 覆盖，避免影响 antd 弹层等既有 motion 行为）。做法：在两份样式内各加一个 `@media (prefers-reduced-motion: reduce)` 块，对下列对象对应的动画/过渡类统一施加 `animation-duration: .001s !important`、`animation-iteration-count: 1 !important`、`transition-duration: .001s !important`（类名随实现定名，覆盖对象逐条对应验收）：

覆盖对象＝编排容器及其子元素、消息行入场、orb 光环、呼吸核、思考点、流式光标、发送钮呼吸环。纯颜色变化保留（时长收到 .001s 即等于直出）。

### 3.6 样式归属

| 文件 | 改动 |
|---|---|
| `src/pages/session/index.less` | **新增**：列表、顶栏、无会话态、禁用输入卡样式（现内联样式仅改选中态与行结构两处，其余不动） |
| `src/pages/session/components/ChatWindow.less` | 修改：空态编排/引导卡、气泡、工具卡、流式、输入区、reduced-motion 块 |
| `src/pages/session/components/ChatWindow.tsx` | 修改：空态 JSX（引导卡数据）、输入区 chips/会话设置菜单 JSX、工具卡三件换皮（仅 className 与结构微调） |
| `src/pages/session/index.tsx` | 修改：列表行结构（第二行 meta）、分组、空列表态、无会话态、禁用输入卡 |
| `src/global.less` | 追加 §3.5 运动 token 四行 |
| `src/locales/{zh-CN,en-US}/pages.ts` | §4 i18n 清单 |

## 4. i18n 清单

新增（zh-CN＝中文值 / en-US＝英文值，共 18 条 ×2）：

| key | zh-CN | en-US |
|---|---|---|
| `pages.session.welcome.title` | 今天想让它帮你做什么？ | What should it do for you today? |
| `pages.session.welcome.subtitle` | 从下面选一个开始，或直接输入你的问题 | Pick a starter below, or type your own question |
| `pages.session.guide.code.title` | 写代码 | Write code |
| `pages.session.guide.code.example` | 写一个 Python 排序算法，并逐行解释 | Write a Python sorting algorithm and explain it line by line |
| `pages.session.guide.file.title` | 读文件 | Read files |
| `pages.session.guide.file.example` | 读一下工作区里的文件，帮我总结要点 | Read the files in the workspace and summarize the key points |
| `pages.session.guide.search.title` | 查资料 | Research |
| `pages.session.guide.search.example` | 联网搜索今天的科技新闻，整理成三条要点 | Search the web for today's tech news and sum it up in three key points |
| `pages.session.guide.plan.title` | 制定计划 | Make a plan |
| `pages.session.guide.plan.example` | 先帮我列一个实现计划，确认后再动手 | Draft an implementation plan first, and wait for my confirmation |
| `pages.session.group.today` | 今天 | Today |
| `pages.session.group.earlier` | 更早 | Earlier |
| `pages.session.noSession.title` | 右侧还没有可进入的会话 | No session to open yet |
| `pages.session.noSession.hint` | 点左侧「新建」开一个会话 | Create one with "New" on the left |
| `pages.session.emptyList.title` | 还没有会话 | No sessions yet |
| `pages.session.emptyList.hint` | 点「新建」开一个 | Hit "New" to create one |
| `pages.session.inputPlaceholder.noSession` | 先新建一个会话，再开始对话… | Create a session first, then start chatting… |
| `pages.session.sessionSettings` | 会话设置 | Session Settings |

删除（均只被本轮替换的元素引用，已全仓核对无他处使用）：`pages.session.quickSuggestion.{javaSort,springBootAutoConfiguration,pythonWebServer,sqlOptimization}`、`pages.session.emptyTitle`、`pages.session.emptySubtitle`、`pages.session.selectSession`、`pages.session.createNew`、`pages.session.noSessions`、`pages.session.thinkingMenuTitle`、`pages.session.hideThinking`。改名：`pages.session.moreOptions` → `pages.session.sessionSettings`。

## 5. 验收

1. **构建门禁**：`max build` 通过；改动文件逐文件 `npx @biomejs/biome lint`（禁 `--write`）；`jest` 保持基线（`login.test.tsx` 既定 2 红不修）。
2. **视觉走查**（本地镜像 ＋ 无头浏览器截图，桌面 1440×900，共 9 帧）：① 空态-入场中（亮）② 空态-稳定（亮）③ 空态-稳定（暗）④ 对话中-工具卡收起 ⑤ 对话中-工具卡展开 ⑥ 流式-思考点 ⑦ 流式-逐字光标 ⑧ 无会话态 ⑨ 列表空态。逐帧对照 §3 参数。
3. **reduced-motion**：以 `prefers-reduced-motion: reduce` 模拟截图，确认编排直出、无无限动画残留。
4. **行为回归**：发送/停止、图片角标与禁用、chips 行构成（图片／压缩上下文／停止沙箱／清空记录／会话设置）与各禁用条件、「会话设置」菜单五项（深度思考／显示思考过程／联网搜索／开启计划／工具执行权限）原有行为（含失败回滚与确认弹窗）、设置类开关使「会话设置」chip 呈激活态、引导卡点击发送、空态重播、切会话消息渲染。

## 6. 已裁定与待复核

已裁定（沿用既有拍板，不再复议）：整页全量范围；克制底＋点睛动效风格；不引入新依赖；桌面端优先；引导卡不承诺未上线能力（故无「定时任务」卡）。

待复核：本文其余全部内容，重点为 §3.1 引导卡四张的文案、§3.2 列表第二行与分组的取舍、§3.4「会话设置」菜单的收纳范围。
