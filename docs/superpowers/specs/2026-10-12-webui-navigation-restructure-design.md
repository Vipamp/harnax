# harnax-webui 导航结构改造设计（方案 3：全屏聊天＋设置壳）

日期：2026-10-12　状态：待复核
适用工程：`harnax-webui`（Umi Max + Ant Design Pro）　基线：`048b5a5a`（分支 kotlin-dev）

## 0. 结论先行

主页面改成**全屏聊天**（`/`，左「会话列表」＋右对话），其余页面全部收进**设置壳**（沿用 ProLayout，侧栏一级组改**手风琴**）：欢迎页保留、当设置里的「概览」；会话页从一级组菜单里隐去，旧地址 `/agent/session` 兼容重定向到 `/?id=`。

四个控件按壳分配：用户／语言／主题在两个壳底部都有；帮助只在设置壳底部；设置入口只在聊天壳底部（→ `/welcome`）。**仅桌面端**；移动端本期不做适配设计。

设计约束（评审每处改动的裁决依据）：

1. 除导航结构外，**零行为改动**——列表、会话、抽屉、跳转逻辑一律复用。
2. **零文案改动**——不新增、不修改 `src/locales/` 与任何文案键。
3. **`src/services/` 零 diff**；不做视觉体系重做（去 Card 外框、去 `PageContainer` 属结构必需，不算视觉改动）。

## 1. 背景与目标

现状（048b5a5a）：`/` 是 redirect，落到 `/welcome` 仪表盘；「会话」页藏在一级组 `agent` 下；全部页面共用一层带多级菜单的侧栏。用户诉求（原话口径）：「主页面只放会话列表和对话，其他的全部一级菜单和二级菜单，统一放到设置里面」。

目标形态：

1. `/` ＝ 全屏聊天：无设置壳、无 `PageContainer`、无 `Card`，左会话列表＋右对话。
2. 欢迎页 `/welcome` 保留（现菜单名「总览」），其余页面一起进设置壳。
3. 设置壳侧栏＝手风琴（同时只展开一个一级组）。
4. 会话页移出一级组菜单；旧地址 `/agent/session?id=…` 等价于 `/?id=…`。

## 2. 现状事实与锚点（已核实）

| 事实 | 锚点 |
|---|---|
| `/` 现为 `redirect` 到 `/welcome` | `config/routes.ts:226-229`（228 行 `redirect: '/welcome'`） |
| 「会话」页挂在 agent 组、菜单可见 | `config/routes.ts:49`（`path: '/agent/session'`，component `./session`） |
| `layout: false` 已有三例：登录、MCP 回调、404 | `config/routes.ts:16`、`233`、`238` |
| 桌面端 side 布局**不渲染顶栏** | `@ant-design/pro-layout/es/components/Header/index.js:93`：`layout === 'side' && !isMobile` 时直接 `return null` |
| 桌面端侧栏底部的操作区（actionAreaDom）＝`avatarProps`／`actionsRender` 的真实落点 | `SiderMenu/SiderMenu.js:139-203`、`245-249`（`showSiderExtraDom = !isMobile && layout !== 'mix'`） |
| 侧栏底部还有 `menuFooterRender` 槽（`.ant-pro-sider-footer`）与折叠钮 | `SiderMenu.js:214`、`280-288` |
| 折叠钮渲染在侧栏内底部，去顶栏不影响它 | `SiderMenu.js:182-194`、`266-288` |
| 手风琴可行：ProLayout 顶层 `openKeys`＋`onOpenChange` 受控直通 BaseMenu（受控可做单开归一） | `SiderMenu.js:118-128`（`onOpenChange` 作为 `handleOpenChange` 传入 `BaseMenu`） |
| `menu.autoOpen` 是死配置（pro-layout es 内零消费者） | `app.tsx:158-162` |
| 登录守卫两处：`getInitialState`（启动期）＋`onPageChange`（ProLayout 内；`layout: false` 路由不渲染 ProLayout，不触发） | `app.tsx:76-84`、`151-157`；`ProLayout.js:385-389` |
| 底部控件现值：用户＝`avatarProps`＋AvatarDropdown（含退出）；语言／主题／帮助＝`actionsRender` 四键（TenantSwitcher 恒 null） | `app.tsx:105-117`、`143-149` |
| 侧栏初始收起（`collapsed` 初值 `true`）＋`breakpoint: 'md'` | `app.tsx:97-104` |
| `menuHeaderRender: undefined`（侧栏顶部走默认 logo＋标题） | `app.tsx:194` |
| `/agent/session` 全仓页面级引用只剩 1 处：agent 列表「新窗口打开」 | `src/pages/agent/index.tsx:377` |
| `/welcome` 引用 3 处：登录页已登录跳转＋登录成功 fallback；Welcome 接口 401 | `login/index.tsx:346`、`459`；`Welcome.tsx:63` |
| 404 页返回按钮指向 `/`（改造后天然落在聊天壳，无需改） | `src/pages/404.tsx:12` |
| 会话页可复用：`?id=` 消费、列表、标题条、ChatWindow、抽屉 | `session/index.tsx:141`、`229-483` |
| TenantSwitcher 是死件（恒 `return null`） | `components/TenantSwitcher/index.tsx:87-92` |

## 3. 设计

### 3.1 两个壳

- **聊天壳**：路由 `/`，`layout: false`，全屏。内部＝左栏（标题行＋会话列表＋底部控件）＋右栏（标题条＋对话）。
- **设置壳**：其余全部路由（`/welcome`、`/agent/**`、`/context/**`、`/optimization/**`、`/monitor/**`、`/system/**` 等），继续走现有 ProLayout（side 布局），做三处定制（§3.3）。

### 3.2 路由改线（路由表 2 处＋引用 4 处）

| # | 位置 | 现状 | 改为 |
|---|---|---|---|
| 1 | `routes.ts:226-229` | `{ path: '/', redirect: '/welcome' }` | `{ path: '/', layout: false, component: './session' }`（聊天壳，不进菜单） |
| 2 | `routes.ts:47-51` | 菜单可见的 `agent` 组子项 | `hideInMenu: true`；打开旧地址时**组件级**重定向到 `/?id=…` |
| 3 | `login/index.tsx:346` | 已登录访问登录页 → `/welcome` | → `/` |
| 4 | `login/index.tsx:459` | 登录成功且无 `?redirect=` → `/welcome` | → `/` |
| 5 | `Welcome.tsx:63` | 接口 401 → `login?redirect=/welcome` | → `login?redirect=/` |
| 6 | `agent/index.tsx:377` | `window.open('/agent/session?id=…')` | `window.open('/?id=…')` |

改线要点：

- #2 用**组件级**重定向而非路由级字符串 `redirect`：字符串 `redirect` 带不住查询串，旧链接的 `?id=` 会丢。以「组件挂载即 `history.replace('/' + location.search)`」形式落地（具体形状由计划定，可新建一个极小的重定向组件或复用会话页头部）。
- 无需改动：`404.tsx` 的 `push('/')` 天然正确；`/welcome` 路由本体不动；其余路由与跳转不动。
- 聊天壳路由不进设置菜单（`layout: false` 路由不在菜单树内，由构建验证兜底）。

### 3.3 设置壳（三处定制）

1. **无顶栏（保持现状即达）**：桌面端 side 布局本就无顶栏（§2 锚点），无需额外处理；不新增顶栏。
2. **返回入口**：侧栏顶部用 `menuHeaderRender` 渲染「← 返回　设置」→ 点击回 `/`（替换默认的 logo＋标题区）。点击接线可用 `menuHeaderRender` 内容自带 handler 或 `onMenuHeaderClick`（桌面端 logo 区点击即触发），由计划定。
3. **手风琴**：受控 `openKeys`＋`onOpenChange`，语义为「展开一个一级组时收起其余组」；状态在 `layout` 运行期配置里管理（该处已允许 hooks）。顺手删除死配置 `menu.autoOpen`（§2 锚点，pro-layout 无消费者）。
4. **底部控件**：`menuFooterRender` 渲染 用户（AvatarDropdown，含退出）／语言（SelectLang）／主题（ThemeSwitcher）／帮助（Question）。
   - 易错点：桌面端 `avatarProps`／`actionsRender` 会经自带操作区**也**落在侧栏底部（§2 锚点），原样保留会与 `menuFooterRender` 重复渲染同一组控件。落地要求：桌面端不再传入这两个 prop（或传空），位置让给 `menuFooterRender`；这两个 prop 仅移动端分支保留现值（本期移动端不动）。
5. **不动**：侧栏初始收起、`breakpoint: 'md'`、折叠钮、`bgLayoutImgList`、dev `SettingDrawer`、`links`。

### 3.4 聊天壳（`pages/session` 容器改造）

只改壳，不动业务：

1. 去 `PageContainer`（`session/index.tsx:2、229、483`）与两张 Card 外框；改两栏满高（视口高）：左＝会话列表栏，右＝对话区（标题条＋ChatWindow＋空态）。
2. 内部全部复用、零行为改动：会话列表＋新建（现「创建」按钮，`PlusOutlined`；`handleCreateSession` → SettingsModal）、行内详情／删除、右键菜单（ChatWindow 的 `trigger={['contextMenu']}`）、标题条（ContextUsageTag／Workspace／Artifacts／Skills）、ChatWindow、SettingsModal／DetailModal／WorkspaceDrawer／TeamArtifactsDrawer／SessionSkillsDrawer。
3. 左栏底部＝用户／语言／主题／设置（→ `/welcome`）四个控件，复用 `components/RightContent` 现有组件。
4. **登录守卫自挂**：`layout: false` 下 ProLayout 不渲染、`onPageChange` 不触发（§2 锚点）——聊天壳挂载时自检 `currentUser`（`getInitialState` 产物），未登录跳 `/login?redirect=<当前地址含查询串>`。
5. `?id=` 深链：挂载时读 `id` 选中会话（现状 `session/index.tsx:141` 已做，保持）。
6. 移动端分支维持现状（本期不设计）。

### 3.5 控件去向

| 控件 | 聊天壳（左栏底部） | 设置壳（侧栏底部） | 说明 |
|---|---|---|---|
| 用户（头像＋退出） | ✔ | ✔ | 复用 AvatarDropdown |
| 语言 | ✔ | ✔ | SelectLang |
| 主题 | ✔ | ✔ | ThemeSwitcher |
| 帮助 | — | ✔ | Question |
| 设置入口 | ✔ → `/welcome` | — | 只在聊天壳底部 |
| 返回聊天 | — | ✔（侧栏顶部「← 返回　设置」） | 只在设置壳 |
| TenantSwitcher | 不迁 | 不迁 | 死件；后续待办见 §5 |

### 3.6 三处易错点（重点复看）

1. `/agent/session` → `/?id=` 必须走**组件级**重定向（保查询串）；聊天壳要接住 `?id=` 选中会话。
2. `layout: false` 下全局 `onPageChange` 守卫不生效 → 聊天壳需自挂守卫（未登录跳 login 并带回报地址）。
3. 设置壳桌面端要让出 ProLayout 自带侧栏操作区，否则底部控件双份渲染（§3.3-4）。

## 4. 验证

- 门禁：`npx max build`；改动文件逐文件 `npx @biomejs/biome lint <file>`；jest（`login.test.tsx` 2 红为既定基线，**勿修**）。
- 本地无头浏览器走查（桌面宽度）：登录 → 落 `/` 即聊天 → 左列表选会话 → 底部「设置」进 `/welcome`（设置壳）→ 侧栏手风琴单开 → 「← 返回　设置」回 `/` → `/?id=<id>` 深链选中同一会话 → 打开 `/agent/session?id=<id>` 等价重定向到 `/?id=<id>`。
- 不做：push、部署、视觉体系改动、`src/services/` 与 `src/locales/` 改动、移动端／窄屏走查。

## 5. 范围外与后续待办

- **租户切换（后续待办）**：`TenantSwitcher` 现为死件，本期不迁不走。后期需要支持切换租户时另行立项与设计（现状：组件恒 `return null`，`actionsRender` 里留着占位键）。
- **移动端适配（后续待办）**：本期只做桌面端；移动端分支代码维持现状、不作为验收项。
- **视觉体系**不在本期：token／主题皮肤／组件观感另行处理。

## 6. 涉及文件

`config/routes.ts`、`src/app.tsx`、`src/pages/user/login/index.tsx`、`src/pages/Welcome.tsx`、`src/pages/agent/index.tsx`、`src/pages/session/index.tsx`（＋可能新增一个极小的旧地址重定向组件，形状由计划定）。`404.tsx`、`TenantSwitcher`、`src/services/`、`src/locales/` 不动。
