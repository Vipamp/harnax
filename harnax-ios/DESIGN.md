# Harnax iOS App 产品设计（v1）

## 1. 目的与结论摘要

本方案由三层文档构成，互不重复：本文件（`DESIGN.md`）定义产品范围、信息架构、接口契约、模块划分、里程碑与验收标准，并给出横切口径；同目录 `FEATURES.md` 是逐条能力清单，承担范围界定与验收对账；同目录 `specs/` 的五份文件是各页面的逐字段全量清单，每条结论都带 `路径:行号` 锚点指向 Web 侧与后端源码。同目录 `ui-mockup/index.html` 是 24 屏界面草图（浏览器直接打开），只定信息架构、控件形态与状态表现，不是视觉终稿。

读法：拍范围与优先级看 `FEATURES.md`，写某个页面前看 `specs/` 对应那一份，两者有冲突时以本文件的横切约定为准、以代码为最终事实。

一句话结论：**iOS App 是 Harnax 管理台的移动形态，覆盖 Web 管理台除「用户管理」「租户管理」外的全部页面，对话能力全量对齐 Web 的 ChatWindow，客户端直接复用 Web 已在用的 `/api/admin/**` 与 `/api/router/**` 两套接口面，不为移动端另开接口面。**

三点最值得注意的判断：

- **对话是本项目的主要成本**，不是一个聊天页。Web 侧 `harnax-webui/src/pages/session/components/ChatWindow.tsx` 单文件 3704 行，承载五类可见分段（文本／思考／工具调用／确认卡／计划卡，工具结果并入调用卡不独立成段）、工具确认往返、计划面板、slash 命令、权限模式、附件与团队多路合并。里程碑按它单独排一期。
- **零后端改造可以开工，但不能一直零改造**。v1 用现成的免验证码登录口 `POST /api/admin/auth/cli-login`，并用后端既有的 `POST /api/admin/auth/refresh-token` 做临期续期（这个接口 Web 侧反而从未调用过）。v1.1 的后端项是 `cli-login` 限流与验证码状态共享存储，它们是切回带验证码登录口的前置条件（见 §5）。
- **流式链路有三个必须复刻的硬约定**：无 `[DONE]` 结束哨兵、`KeepAliveEvent` 既不渲染也不能当结束、团队成员的确认回执必须带 `childRunId` 原路返回。这三条写错都不会报错，只会静默出错（见 §6）。

## 2. 决策记录

| # | 决策点 | 结论 | 依据 |
|---|---|---|---|
| D1 | 产品定位 | 移动管理台，不是员工对话端 | 渠道侧（飞书/微信/企微/钉钉）已在手机上，移动对话增量有限；移动管理才有缺口 |
| D2 | 功能范围 | Web 管理台子集 + 对话全量 | 用户裁定 |
| D3 | 技术栈 | SwiftUI 原生，iOS 17 起 | 只做 iOS；`URLSession` 原生支持 SSE，Keychain、Face ID、APNs 都是系统能力 |
| D4 | 接口口径 | 统一复用 `/api/admin/**`，不区分移动端与 Web | 移动专用接口面覆盖过窄且即将下线；小程序已验证 admin 面对非浏览器客户端可用 |
| D5 | 登录口 | v1 用 `cli-login` 加既有续期接口；v1.1 切回 `login`，前置是 `cli-login` 限流与验证码状态共享存储 | 见 §5 的权衡表 |
| D6 | 排除项 | 用户管理、租户管理、修改密码、个人资料 | 用户裁定；后两项在 Web 侧同样不存在 |
| D7 | 保留项 | 用户管理与租户管理两页不进 v1；切租户能力保留（属登录态能力，不属管理页），按 O1 定案的形态落地——只对拥有多个租户的账号出现在「我的」页，不进主导航 | `POST /api/admin/auth/switch-tenant` 会签发新令牌，`X-Tenant-ID` 受成员校验；候选列表来自 `GET /api/admin/auth/tenants`，该接口按 `user_tenant` 关联行返回、对管理员也不放宽 |
| D8 | 交付目标 | 有付费开发者账号，可真机与 TestFlight | 用户裁定；APNs 推送因需后端改造后置 |
| D9 | 图表 | 用 Swift Charts 重画，不引第三方 | 仅一个页面用到 3 饼 4 折，系统框架够用 |
| D10 | 平台边界 | iPad 采用同一套自适应布局，不做独立形态 | 避免范围膨胀 |

## 3. 产品范围

### 3.1 v1 页面清单

Web 侧路由定义见 `harnax-webui/config/routes.ts`，iOS 与之一一对应：

| 域 | Web 路由 | iOS 形态 | 备注 |
|---|---|---|---|
| 智能体 | `/agent/manager` | 列表 + 5 步向导 | 含工具/MCP/技能/CLI 四类绑定与环境参数 |
| 智能体 | `/agent/team` | 列表 + 3 步向导 | 基本信息／主管技能／成员智能体（`harnax-webui/src/pages/team/components/TeamWizard.tsx:261-269`），成员编排与排序 |
| 智能体 | `/agent/session` | 会话列表 + 对话全屏 | 本项目最大单体，见 §6 与第 13 章 |
| 智能体 | `/agent/task` | 列表 + 编辑 + 日志 | 含 cron 预设、立即执行、日志停止 |
| 上下文 | `/context/model` | 供应商卡片 → 模型两级 | 含连通性测试与 thinkingMode |
| 上下文 | `/context/tool` | 只读列表 | 内置工具表 |
| 上下文 | `/context/mcp` | 列表 + 编辑 + 详情 | 详情含工具列表与 OAuth 面板 |
| 上下文 | `/context/skill` | 仓库列表 + 技能列表 | 含同步、安装、上传 |
| 上下文 | `/context/skill/detail/:id` | 详情（正文 / 文件树两态） | Markdown 渲染 + 代码高亮 |
| 上下文 | `/context/cli` | 列表 + 详情抽屉 | 含 kill switch 与关联检查 |
| 系统 | `/system/channel` | 列表 + 条件字段表单 | 5 类型 × 4 接入模式矩阵，含微信扫码 |
| 系统 | `/system/api-key` | 列表 + 编辑 | 一次性原始 Key 展示；Web 侧该页对非管理员不可见（O5） |
| 系统 | `/system/env-variable` | 列表 + 编辑 | 敏感值掩码 |
| 系统 | `/system/token-monitor` | 筛选 + 统计卡 + 图表 | 唯一图表页 |
| 登录态 | `/login` | 登录页 + 服务器地址设置 | 见 §5 |
| 兜底 | — | 我的（登录态、租户切换、主题、语言、退出） | 不含资料编辑与改密 |

Web 侧 `/context/mcp/detail/:id` 与 `/context/skill/detail/:id` 是 `hideInMenu` 的子路由，iOS 用 push 导航承载；`/mcp/oauth/callback` 是无布局的回跳页，iOS 上改造成 URL scheme 回调（见第 13 章 MCP 节）。`/welcome` 是数据全硬编码的演示页，`/context/channel` 是保活重定向，两者都不进 iOS。

### 3.2 明确不进 v1（记账项）

| 项 | 原因 | 何时重议 |
|---|---|---|
| 用户管理 `/system/user`、租户管理 `/system/tenant` | 用户裁定。补充事实：这两页**不是已下线**，由 `harnax-webui/src/access.ts:13` 的 `canAccessUserManagement`（即 `currentUser.isAdmin === 1`）控制，对管理员可见可写 | 若 iOS 要给管理员用 |
| 修改密码、个人资料编辑 | 用户裁定；Web 侧同样没有自助改密入口（前端无任何相关调用点） | 需先补后端自助接口 |
| APNs 推送 | 需要后端新增设备注册与触达接口，与 D4 的零改造前提冲突 | v1.1，且是移动端价值最大的一项 |
| 离线缓存与写操作排队 | 收益不明确，成本主要在冲突合并 | 有真实弱网反馈后 |
| Android | 决策 D3 选了原生 | 有跨端需求时重议技术栈 |
| 移动专用接口面（`/api/admin/mp/**`） | 无客户端在用，计划下线 | 见 §4.3 |

## 4. 接口口径

### 4.1 两套基址，不是同源

Web 侧所有请求写绝对路径 `/api/...`，靠 Nginx 同源反代分流到两个服务。原生 App 没有这个前提，**iOS 必须自己持有两个基址**：

| 面 | 承载 | 职责 | 鉴权头 |
|---|---|---|---|
| Admin | `harnax-admin` | 全部资源 CRUD、登录、统计 | `Authorization: Bearer <JWT>` + `X-Tenant-ID` + `Accept-Language` |
| Router | `harnax-session-router` | 对话、流式、确认、命令、历史、workspace | `X-Api-Key`（缺失时后端也接受 Bearer，客户端统一用 API Key） |

服务器地址在登录前录入并持久化，两个基址独立可改。本地开发环境是 Nginx 自签证书，真机需要 ATS 例外或安装信任描述文件——这是 §14 的一个前置风险项。

### 4.2 响应与分页约定

外层统一包装 `ResultVo{code, message, data, timestamp}`，`code == 200` 为成功，业务失败也走 HTTP 200 携带非 200 的 `code`。分页查询参数为 `pageNum`（从 1 起）、`pageSize`（默认 10），另有 `keyword`、`status`；分页响应为 `{pageNum, pageSize, total, pages, records}`。业务错误的文案直接取 `message`，该字段已按请求头 `Accept-Language` 本地化。注意：Web 侧存在一套按 `showType` 分四级提示的实现，但后端从不返回该字段，四级实际恒落到同一个 toast，其中「跳转登录」分支还是空实现——iOS **不复刻这套分级**，提示统一按 §9 处理。

401 一律清凭据并回到登录页。iOS 侧不做「本地按 `expiresAt` 预判过期」这套逻辑，改为**在即将过期时主动续期**，具体见 §5.3。

### 4.3 移动专用接口面下线

现状：该面 5 个 Controller 只覆盖认证、Agent 只读、会话 CRUD、聊天历史、个人资料，管理域零覆盖；其唯一客户端是已计划废弃的 uni-app 工程。下线顺序不可颠倒：

1. 先移除旧移动端工程（代码与文档），否则其引用会断；
2. 再删 Admin 侧该面的 Controller、Service、DTO 与对应实体、Mapper 及其单测与集成测试；
3. 数据库层：历史迁移文件一律不改（已应用的迁移连注释都不能动），建表语句留档，按「变更折进基线并清库重建」的既有约定收敛；
4. 文档层：调用链路文档中「移动端走该面」的条目改为「iOS 走 Admin 与 Router 两面」，否则下一个客户端还会照着开新面。

## 5. 鉴权与凭据模型

### 5.1 为什么 v1 用免验证码登录口

Web 登录链路是：取验证码 → 前端 SHA-256 → 提交。后端把验证码当硬闸门，三条校验各自抛错（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AuthServiceImpl.kt:62-69`），库里存的是 `BCrypt(SHA-256(明文))`，Web 登录直接拿前端 hash 比对（同文件 `:51`）。

这条路 iOS 技术上能一比一复刻（验证码值是完整 data URL，见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/CaptchaServiceImpl.kt:203`，Swift 侧剥前缀后解码成 `UIImage` 即可），但在移动形态上有三处不适配：

1. **登录频率被放大**。后端有 `POST /api/admin/auth/refresh-token`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenController.kt:31`），但 Web 全仓无调用点，只按登录时下发的 `expiresAt` 本地判过期然后踢回登录页。浏览器一天登一次，感知不到；App 进程被系统随时回收，感知很强。
2. **图形码是 120×40 px 的四字符位图**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/CaptchaServiceImpl.kt:29-33`），小屏读数吃力、放大即糊、白底图与暗色模式冲突、VoiceOver 不可读。
3. **验证码状态在进程内存**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/CaptchaServiceImpl.kt:26-27` 用的是并发 Map，代码注释自己写明应改 Redis）。Admin 一旦多副本，生成与校验落到不同实例必然失败。走这条口会把该部署约束继承进 iOS。

反向事实必须记清：`cli-login` **更安全面更弱**。它免验证码（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:45-49`），从取用户到返回令牌只有一次口令比对，无失败计数、无锁定、无速率限制（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AuthServiceImpl.kt:192-236`），且收明文口令由服务端补 hash。

所以 D5 的排序是：**v1 用 `cli-login` 换取零后端改造与可用体验，v1.1 补完续期与限流后切回 `login`**。

### 5.2 两个登录口的差异只有一处

返回体是完全同一个 `LoginResponse`：`accessToken`、`tokenType`、`expiresIn`、`expiresAt`、`userInfo`、`tenants`、`currentTenantId`、`routerApiKey`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AuthServiceImpl.kt:237-243` 与 `:118-126` 逐字段一致；字段定义见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/LoginResponse.kt`）。身份查找两边都是按用户名，`/api/admin/auth/login-methods` 声明的手机号与邮箱在两条口上都不生效——iOS 的登录页只放用户名一个输入框，与后端行为对齐。

唯一差异是口令口径：Web 口收前端 hash，CLI 口收明文。切回 `login` 时 iOS 需改为 `CryptoKit.Insecure.SHA256` 输出小写 hex，与服务端期望一致。

`routerApiKey` 是自愈的：账号无永久 Key 时登录过程会当场创建（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AuthServiceImpl.kt:107-114`），客户端不需要预置。

### 5.3 凭据存储与续期

- 全部凭据进 Keychain，`kSecAttrAccessibleAfterFirstUnlock`，保证后台刷新可用；`accessToken`、`routerApiKey`、两个基址、`currentTenantId` 分条目存。
- 冷启动用 `GET /api/admin/auth/me` 恢复登录态与租户，不靠本地缓存判身份。
- v1 即接续期：`POST /api/admin/auth/refresh-token` 返回 `{accessToken, tenantId, expiresIn}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenController.kt:56-62`），**不含 `expiresAt`**，客户端需用 `now + expiresIn` 自行换算后写入 Keychain；该接口继承当前租户上下文。
- 续期要求**旧令牌仍然有效**（该接口靠鉴权上下文取当前用户，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenController.kt:35`），因此只能在临期时主动触发，过期后无路可走、只能重登。阈值取剩余有效期低于三分之一。
- 续期只换 JWT，`routerApiKey` 是账号级永久凭据，不参与续期。
- 续期失败一律回到登录页；登录页优先用生物识别解锁已存凭据，避免重复输入。
- 客户端本地对登录失败做指数退避（起始 1s，上限 60s，5 次后要求重新输入），补偿 `cli-login` 无服务端限流的缺口。
- 退出：调 `POST /api/admin/auth/logout` 后清 Keychain；Web 侧只清本地存储，iOS 保持一致语义。

### 5.4 租户

租户不是可选项，但也不是唯一来源。后端解析租户走一条四级回退链（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/TenantResolver.kt:20-26`）：带 `X-Tenant-ID` 且通过成员校验的请求上下文 → 令牌里的 `tenantId` 声明 → 账号行上的租户 → 兜底常量 1。**只有第一级带成员校验**，漏带请求头不会报错，而是静默回落到令牌里的租户。

切换租户的接口 `POST /api/admin/auth/switch-tenant` **会签发一个带新租户声明的新令牌**并返回 `{accessToken, tenantId}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:164-177`），但 Web 侧全仓没有任何调用点：`harnax-webui/src/components/TenantSwitcher/index.tsx:56-71` 的切换只改本地存储里的当前租户 ID 然后刷页面，租户列表也另取。等于说这个接口与 §5.3 的续期接口一样，是后端已有、Web 从未启用的能力。

iOS 应该是第一个真正调用它的客户端，理由是自洽性：换令牌后租户声明跟着令牌走，漏带请求头也只是回到正确的工作区；照 Web 的做法只改本地状态，则令牌里的声明永远停在登录时那一个租户（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AuthServiceImpl.kt:217`），一旦某个请求漏带头就静默漂回去。落地做法：切换成功后**同时替换 Keychain 里的 JWT 与本地租户 ID**，并让所有页面重载。

两个接口的口径不一致，且不一致的方向对 iOS 有约束：`switch-tenant` 对管理员放行任意租户（非成员且 `isAdmin != 1` 才拒，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:159-162`），但候选列表接口 `GET /api/admin/auth/tenants` 只把调用者自己的 `user_tenant` 关联行映射出来、对管理员也不放宽（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/UserTenantServiceImpl.kt:31-47`）。因此 iOS 的选择器只能列出「有成员行的租户」，管理员也拿不到全量——要跨进没有关联行的租户，只能靠后端补一个放宽的列表接口。

但要注意一条既有产品裁定：Web 侧的租户切换是**刻意整体关闭**的，不是窄屏才隐藏。`TenantSwitcher` 组件在渲染前有一条无条件 `return null`，注释写明「所有版本都隐藏租户切换组件（多租户由系统自动管理，不需要用户手动切换）」（`harnax-webui/src/components/TenantSwitcher/index.tsx:91-92`），组件本身也不发任何租户请求，列表数据来自登录响应。因此「iOS 是否提供手动切租户」等于重开这条裁定；O1 已定案（见 §15）：iOS 提供，但只放在「我的」页、仅对拥有多个租户的账号可见，不进主导航。

## 6. 流式对话链路

### 6.1 帧与解码

`POST <router>/api/router/agent/chat/stream`，请求头 `Accept: text/event-stream` + `X-Api-Key`，请求体是三类多态请求之一，用 `type` 判别（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:17-26`）：

- `CHAT`：`sessionId`、`message`、`imageUrls`、`requestId`、`userId`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:50-58`）
- `CONFIRM`：`sessionId`、`isConfirmed`、`toolResults[]`、`toolInfoList[]`、`childRunId?`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:136-145`）
- `COMMAND`：`sessionId`、`command`、`args`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:74-79`）

已认证时服务端用鉴权上下文覆盖请求体里的 `userId`，客户端传与不传都不影响归属，但 `sessionId` 必须正确。

响应是事件流，每帧 `data: {json}`，JSON 顶层字段 `eventType` 为判别键，八个分支定义在 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:18-32`：

| eventType | 关键字段 | iOS 渲染规则 |
|---|---|---|
| `TextEvent` | `message`、`isLast` | 追加到当前文本段；`isLast` 收尾 |
| `ThinkingEvent` | `message`、`isLast` | 独立思考段，默认折叠 |
| `CallToolEvent` | `toolId`、`toolName`、`arguments` | 工具调用段，按 `toolId` 与结果配对 |
| `ToolResultEvent` | `toolId`、`message`、`success` | 回填到对应调用段 |
| `ToolConfirmEvent` | `pendingCallTools[]`（含 `isDangerous`） | 阻断式确认卡，等待用户回执 |
| `EndEvent` | `attachments[]` | 收尾，落附件 |
| `ErrorEvent` | `code`、`message` | 流内错误，按 `code` 给文案 |
| `KeepAliveEvent` | 无内容 | **不渲染，不视为结束** |

每条事件都带 `tokenUsage`（`inputTokens`/`outputTokens`/`totalTokens`/`costTime`/`timestamp`）与可选 `source`。

### 6.2 三条容易写错的硬约定

1. **没有 `[DONE]` 哨兵**。流结束只由 `EndEvent`/`ErrorEvent` 或连接关闭表达，拒绝也走流内 `ErrorEvent` 而非 HTTP 错误码。解码器不能等哨兵。
2. **`KeepAliveEvent` 存在的原因就是「静默流会被各跳掐断」**：会话路由侧 120 秒、渠道侧 180 秒（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:178-185` 的注释记录了这组数字）。成员停在确认上时不产生任何事件，靠心跳撑住。iOS 若把它当结束，会在用户还没答完时就拆掉界面；若把它当内容渲染，会出现空气泡。
3. **团队成员共享一条根会话与一条 SSE 通道**，成员事件与主管事件混在一起，而 `agentId` 与显示名区分不开同一成员的两次运行。确认回执必须把 `source.childRunId` 原样带回（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:47-60`、`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:131-134`）。`source` 非空即表示该事件来自成员运行，气泡归属与合并规则据此判定。

### 6.3 客户端实现要求

- 用 `URLSession` + `bytes` 异步序列自读行缓冲，自己按空行分帧；必须处理粘包与半包，以及单帧内多行 `data:`。
- 每个待发请求关联一个可取消任务；「停止」除了本地取消，还要按 §13 会话章的命令映射发对应的服务端命令。
- 断线不做自动重连（重放语义未定义），但必须把界面从「流式中」状态释放出来，并允许用户重新发消息。
- 后台保持：确认等待可能长达分钟级，需要 `beginBackgroundTask` 兜住，否则切后台即断。

## 7. iOS 端架构

Swift Package Manager 本地多包，App target 只做装配。分层依据是「能被独立理解与独立测试」，不是「层次数量」。

```
HarnaxApp                App target：场景、导航、依赖装配
  └─ HarnaxFeatures      各页面 Feature（每个域一个子目录，仅 UI + ViewModel）
       └─ HarnaxChat     对话领域：事件 reducer、分段模型、确认状态机
       └─ HarnaxKit      共享 UI 组件、设计令牌、i18n、格式化
       └─ HarnaxAPI      AdminClient / RouterClient / SSEParser / 请求头注入 / 错误映射
            └─ HarnaxCore 契约模型（Codable）、枚举、分页、ResultVo、租户、凭据存取协议
```

依赖只允许向下，`HarnaxCore` 不 import SwiftUI。

关键约定：

- **状态**用 `@Observable`，一页一个 ViewModel；不引入第三方状态库。ViewModel 只依赖协议（`AdminClienting`、`RouterClienting`、`TokenStoring`），便于用假实现单测。
- **契约模型手写 Codable**，字段名与后端 DTO 逐字对齐（后端别名即契约的地方尤其注意），每个模型配 JSON fixture 测试。不用代码生成器，避免生成物与后端漂移时无人负责。
- **列表统一用游标式加载封装**（内部仍是 `pageNum`/`pageSize`），分页、下拉刷新、错误重试一处实现，13 个列表页共用。
- **表单**用一组可组合的行控件（文本 / 数字 / 开关 / 单选 / 多选 / 键值表 / JSON 编辑器 / 文件），条件字段矩阵由一个声明式描述驱动，Channel 那种 5×4 组合靠新增描述而不是新代码解决。
- **能力门控**：对话输入区的开关由后端返回的模型能力字段驱动（推理支持、思考模式取值、联网、视觉），客户端不得自行假设某模型可思考。

## 8. 跨域共享组件

这四件事在 Web 侧散落多处，iOS 收敛为共享组件，是本项目复用收益最高的部分：

1. **关联拦截**：删除或停用被引用的资源时，Web 侧的口径并不统一，iOS 按各自的真实形态复刻而不是造一个通用前置查询——智能体删除直接提交、由后端在 message 里列出引用它的团队/会话/渠道；团队删除先读 `related-sessions`，但那次读只用于选确认文案，真拒绝仍来自后端；技能停用是唯一由客户端算的门，用列表行自带的两个智能体/团队计数置灰，而 `requireUnbound` 在服务端还多查一路「CLI 包内置技能」，这一路只能靠 message 呈现。共同点是后端拒绝原因必须原样展示，不能吞成通用报错（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillServiceImpl.kt:252`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:212`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:153`）。
2. **刷新受影响会话**：保存智能体、停用 CLI 之后会拉出受影响的会话清单让用户多选再提交（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:94` 提供清单、`:104` 提供提交口）。这是跨实体的隐性副作用，做成一个 sheet 复用，文案必须说清「不做这一步配置不会生效到既有会话」。
3. **环境参数绑定表**：工具、MCP、CLI 三类共用一套键值绑定编辑，含默认值与校验。注意既有裁定：MCP 与 CLI 不回填包默认值。
4. **流式列表 + 详情**：模型两级、技能仓库与技能、MCP 与工具列表，形态相同，共用一套容器。

## 9. 错误处理

- 三层：传输层（网络不可达、超时、证书）→ 协议层（HTTP 非 2xx、401）→ 业务层（`ResultVo.code != 200`）。
- 业务错误优先按后端 `message` 直出（消息已按 `Accept-Language` 本地化，客户端不要自己再造一套错误码字典），只在需要结构化动作时才按 `code` 分支——例如流内 `ErrorEvent`。
- 401 单独通道：清凭据、保留当前页面路径以便登录后回跳。
- 流式错误不能只弹提示：必须同时把消息状态从「流式中」落地为「已中断」，并把待确认卡置灰，否则用户会对着一个永远转圈的界面。
- 全局不做静默吞异常；`ResultVo` 非 200 且未被页面处理时，统一冒到顶栏横幅。

## 10. 非功能要求

| 项 | 要求 |
|---|---|
| 语言 | 简体中文与英文两份，与 Web 侧覆盖范围对齐；所有面向用户的字符串走本地化键，禁止内联 |
| 主题 | 浅色 / 深色 / 跟随系统三档。**每一屏都必须两档同时验收**，不是 M5 才补：颜色只从 `HarnaxKit` 的语义令牌取（背景／层级／主文／次文／分隔／成功／警示／危险／品牌），页面与组件里禁止出现字面量色值与 `Color.white`／`.primary` 之类的裸系统色；对话代码块、图表、确认卡三处各有独立双档配色 |
| 无障碍 | 全部交互控件有可读标签与轨迹；工具确认卡的风险等级不能只靠颜色表达；图表提供数值列表替代 |
| 最低版本 | iOS 17，换取 `@Observable`、Swift Charts、`URLSession.bytes` 的干净写法 |
| 性能 | 长对话列表用惰性栈；流式增量合并节流到一帧一次，避免每 token 触发全列表刷新 |
| 隐私 | 凭据只进 Keychain；日志禁止打印令牌与 API Key；崩溃采集不带对话正文 |

## 11. 测试策略

验收标准是「契约正确 + 关键流程可跑通」，不是覆盖率数字。

1. **契约层（最重要）**：每个 `ResultVo` 包裹的响应、每个分页体、每个流式事件都存真实 JSON fixture，解码断言逐字段。fixture 从跑通的环境采集，采集脚本与时间戳记在测试注释里。
2. **SSE 解析**：粘包、半包、多行 `data:`、空帧、`KeepAliveEvent` 不产出可见段、连接中断释放状态——每项都是纯函数单测。
3. **分段 reducer**：文本增量拼接、思考段折叠、工具调用与结果按 `toolId` 配对、确认卡生成与回执、成员事件按 `childRunId` 分组。这块是纯逻辑，必须是全项目最厚的一组测试。
4. **ViewModel**：用假客户端断言「点了什么 → 发了什么 → 界面到什么状态」，含业务错误与 401 路径。
5. **UI**：关键页快照测试（对话分段、Channel 条件表单、Token 监控），**每个快照都出浅深两张**，两档任一不对即红；再加一条令牌闸——扫描源码里除 `HarnaxKit` 令牌定义文件外的字面量色值，命中即失败。端到端只保一条真机链路——登录 → 建会话 → 发消息 → 收到流式文本 → 触发工具确认 → 批准 → 收到结果 → 结束。
6. **不建 UI 测试金字塔**：本项目页面多而表单重，投入产出比低；把预算压在契约与 reducer 上。

## 12. 里程碑

| 阶段 | 交付 | 出口判据 |
|---|---|---|
| M0 骨架 | 五个包、双基址配置、`cli-login`、Keychain、`auth/me` 恢复、请求头注入、`ResultVo` 与分页封装、**`HarnaxKit` 语义令牌双档（浅／深）与主题切换开关** | 真机能登录、能拉到当前用户与租户、能列出智能体；同屏在系统浅深两档下无一处硬编码色导致的反色错误 |
| M1 上下文域 | 模型、工具、MCP（含 OAuth 回跳改造）、技能（含上传与详情文件树）、CLI | 五个域可增改查，连通性测试与关联拦截可用 |
| M2 智能体域（配置） | 智能体 5 步向导、团队、四类绑定与环境参数、刷新受影响会话 | 能建出一个带工具与技能的智能体并在 Web 侧看到同一份配置 |
| M3 对话全量 | 会话列表、分段渲染、工具确认、计划面板、slash 命令、权限模式、图片、workspace、团队多路合并 | 第 11 章第 5 条端到端链路通过；与 Web 同一会话逐段对照渲染结果一致 |
| M4 任务与系统域 | 定时任务、Channel（含微信扫码、含渠道级三开关）、API Key、环境变量、Token 监控；并落 O6 的后端改动：渠道两个请求 DTO 加三列、`fromEntity` 与创建/更新映射补齐 | 图表数值与 Web 同源一致；Channel 五种类型配置可完成；三开关写入后内部接口读到同一值 |
| M5 收口 | 双语、无障碍、全 app 深浅两档逐屏走查、TestFlight 分发、签名与隐私清单 | 内部试用一轮，无阻断缺陷；逐屏两档走查表全绿 |

M3 与 M4 可并行拆分给不同人。开工前必须先落 §14 的 R1：开发期绕开 Nginx 直连 Admin `28080` / Router `28081` 并在 ATS 里配例外域，否则 M0 的「真机能登录」这条出口判据达不到；R7（Swift 工程骨架、CI、图标基线）由 M0 本身建成。

## 13. 各页面字段级规格

逐字段的全量清单落在同目录 `specs/` 的五份文件里，每份都带 `路径:行号` 锚点。计数口径是「形如 `文件名.扩展名:行号` 的出现次数，同一处写多个行号算多条」：`specs/` 五份合计 1479 条（01 为 404、02 为 382、03 为 160、04 为 334、05 为 199），本文件 70 条，`FEATURES.md` 0 条。其中带目录的全路径锚点 1546 条（同样按 `specs/` 与本文件的行内代码逐个取出）已用脚本逐条核过「文件存在 + 行号在文件行数内」，无一条指向不存在的文件或越界的行号；只写裸文件名的锚点不在这条判据里，它们靠仓库内同名文件唯一来定位。另有 5 处本文件指向 `specs/` 各份文档的相对链接，那是文档对文档的引用，已手工确认文件在位。本章给的是写码时要对照的口径摘要；两者冲突以代码为准，代码与 `specs/` 冲突时以 `specs/` 里更细的锚点为准。

| 文件 | 覆盖页面 | 「未确认」条数 / 其中已回代码核实 |
|---|---|---|
| `specs/01-agent-team.md` | 智能体列表与 5 步向导、团队列表与 3 步向导、四类绑定与刷新会话弹窗 | 10 / 4 |
| `specs/02-session-chat.md` | 会话列表与外壳、新建与详情、Workspace 与产物抽屉、五类可见分段、工具确认、计划与权限、slash | 8 / 3 |
| `specs/03-system-domain.md` | 渠道与微信扫码、API Key、环境变量、Token 监控、租户切换 | 8 / 6 |
| `specs/04-context-domains.md` | 模型两级、工具、MCP 与 OAuth、技能仓库与技能、技能详情、CLI | 11 / 4 |

「已核实」只统计各文件「未确认」章节里显式标了「已定案／已核实／已查实／已裁定」的条目，合计 37 条中已收口 17 条（八项产品问题已全部拍板，剩余 20 条是写码前实测项）；其余条目是写码前需要实测或产品拍板的开口，没有一条可以当作已验证前提。

### 13.1 智能体

- 列表：`GET /api/admin/agents/page` 每行已带回四类绑定与全部会话，Web 侧不再调详情（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:291`-`:392`）。iOS 的列表行模型要按这个形状建，详情页不存在——`GET /api/admin/agents/{id}` 只在会话详情里用到。
- 基本信息：`name` 非空且后端限 1–100、同租户重名拒绝；`description` / `systemPrompt` 后端 `@NotNull` 但允许空串；`modelId` 必选且必须是当前租户可见模型；`owner` 不参与提交，后端一律写当前用户名；`isPublic` 是 `0/1`，非管理员在编辑态禁用该开关。
- 提交形态：新建体固定带 `status: 1`（`harnax-webui/src/pages/agent/components/CreateForm.tsx:160`），编辑体不带 `status`（`harnax-webui/src/pages/agent/components/UpdateForm.tsx:257`-`:280`）；四类列表只要非 `null` 就是整体替换，`null` 保持原值。
- 键名不对称：请求侧四类用 `id`，响应侧分别是 `mcpId` / `toolId` / `cliId`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:55`-`:73` 对 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:91`）。iOS 建模要分读写两套 key。
- 技能是逗号串不是数组：`skillList: "1,2,3"`（`harnax-webui/src/pages/agent/components/CreateForm.tsx:168`），空选＝空串，后端按空清空全部。
- 候选集被 `pageSize=100` 截断且四个选择器都没有远程搜索；只有团队成员有 300 毫秒防抖搜索（`harnax-webui/src/pages/team/components/MembersField.tsx:35`-`:53`）。
- 掩码不可回写：敏感环境项的 `displayValue` / `defaultValue` 都是掩码，「回填后原样提交」会把星号写成正式值。

### 13.2 团队

- 向导是三步：基本信息 → 主管技能 → 成员智能体（`harnax-webui/src/pages/team/components/TeamWizard.tsx:261`-`:269`），不合并。
- `members` 是 `{agentId, delegationDescription}[]` 且不允许空数组；主管技能 `skillIds` 是数字数组，提交遵循「不动即不发」——比较的是排序后的 id 集合（`harnax-webui/src/pages/team/components/TeamWizard.tsx:200`-`:210`），每次无条件带上会让一条失效技能把整个团队写坏。
- 团队不提交任何 `envBindings`；成员顺序有语义（后端按 `ORDER BY id` 回读），iOS 必须提供排序 UI。
- 删除前读 `GET /api/admin/teams/{id}/related-sessions`，但那次读只用于选确认文案；读失败时不给弹窗，因为此时无法判断会不会被拒（`harnax-webui/src/pages/team/index.tsx:136`-`:158`）。

### 13.3 会话与对话

- 列表一次拉 100 条、不分页、无搜索与分组，排序完全来自后端 `create_time DESC`（`harnax-webui/src/pages/session/index.tsx:39`）。
- 新建返回 `ResultVo<Void>`，拿不到新会话 id，必须重拉列表再选中。
- `isPublic` 是死 UI：不进提交体，后端 DTO 也没有该字段；「公开」语义只在列表 SQL 的 `is_public = 1 OR creator = 自己` 里成立。
- 图片附件走 `imageUrls` 的 base64 data URL（`FileReader.readAsDataURL`），没有上传接口，历史消息不保存图片；非 `data:image` 前缀的值会被后端当本地路径读。
- 收附件是 iOS 超出 Web 的一项：`EndEvent.attachments` 七个字段（`fileId`/`fileName`/`filePath`/`fileSize`/`mimeType`/`url`/`objectKey`，`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/FileAttachment.kt:14`-`:22`），webui 全仓零处引用 `attachments`。下载口 `GET /api/output-files/{sessionType}/{sessionId}/{fileId}` 整个控制器受 `@ConditionalOnProperty(minio.enabled=true)` 控制（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/OutputFileController.kt:38`），未开启时该路由不存在（默认值 `${MINIO_ENABLED:false}` 关，标准部署 compose 显式开），iOS 要按 404 降级而不是当业务失败报错，是否留 v1 见 O8；`sessionType` 只接受 `web`/`task`/`channel`，`fileId` 必须是 UUID。
- 分段类型 6 个、实际渲染 5 类：`tool_result` 类型的渲染分支直接返回 `null`，结果写进 `tool_call` 段的 `toolResult` 由合并卡展示（`harnax-webui/src/pages/session/components/ChatWindow.tsx:2771`-`:2773`）；`isLast === true` 的帧要丢内容；`KeepAliveEvent` 无分支即忽略，但成员 KeepAlive 带 `childRunId` 时会画出空气泡。
- 主管确认是整批允许／拒绝，成员确认靠 `childRunId` 整轮一次性；`alwaysAllow` 与 `toolResults` 前端零使用、服务端也忽略（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:366`-`:371`）。
- slash 只映射 9 个 keyword 到 8 个 `CommandType`，缺 `deny` 与 `refresh`，`COMPACT` 后端未实现。
- Web 侧主管确认期间 `await` 会阻塞整个 SSE 读循环，且切会话不 abort 旧流——这两条都是缺陷，iOS 不复刻。
- 权限模式五个字符串值 `DEFAULT`／`BYPASS`／`ACCEPT_EDITS`／`EXPLORE`／`DONT_ASK`，后端只 `@Size(20)` 不做枚举校验，iOS 侧要自己收敛取值。

### 13.4 上下文四个域

- 模型是「厂商卡片 → 模型表格」两级；`thinkingMode` 取 `0/1/2` 并与 `supportReasoning` 双向推导；厂商统计走 `GET /api/admin/model-providers/{id}/stats`。
- 工具页只读，`needConfirm` / `readOnly` 是 `0/1` 整数约定；按 Agent 维度的可用工具口不在上下文页使用。
- MCP 的 OAuth 是六步状态机：`status` → `discover` → `client` → `authorize-url` → `exchange` → `revoke`；`state` 只存在于 URL，服务端内存态 TTL 5 分钟且一次性消费；stdio 类型在运行时被禁，前端只是把后端 message 呈现出来；工具参数列表不可展开。
- 技能仓库的「同步」是两个口的组合：`GET /skill-sources/{id}/fetch` 拿预览，`POST /skill-sources/{id}/install` 落库，后端没有 `sync` 口；上传是 multipart，字段名 `file` 与 `name`；安装结果 `200` 不等于全成功，要按新增／`flagged`／`stale` 分区渲染。技能没有删除入口，删除只在仓库侧。
- 技能详情的资源文件没有独立读取接口，全部来自详情响应；Markdown 正文即 `skillmd`。
- `POST /api/admin/skills/batch` 是 `@Deprecated` 且 Web 零调用的旧口，iOS 不接。
- CLI 是「全体登录用户可见可开关」的一页：路由不带 `access`（`harnax-webui/config/routes.ts:100`-`:105`，全仓只有 `/system/user`、`/system/tenant`、`/system/api-key` 三条加了管理员门禁），`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:37`-`:95` 的读口与 `PUT /toggle/{id}` 也没有角色校验；又因 `CliResponse` 不含 `isPublic`/`creator`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt:21`-`:58`），§13.7 的两条权限规则在这页套不上。

### 13.5 系统域

- 渠道是「类型 × 接入模式」的条件字段矩阵，`callbackKey` 从不下发、`callbackUrl` 只在 webhook 模式存在；`configJson` 与 `envValue` 都走「掩码回传＝不修改」协议；`http` 类型没有 adaptor，运行时会报 `no adaptor registered`，iOS 表单保留选项但灰显不可提交（O7）。渠道级 `enableThink` / `enableSearch` / `enablePlan` 三开关进 v1，取值沿用实体的 0/1 整数、与类型无关因此放在表单通用段而非条件段（O6，连带后端补 DTO 三列）。
- API Key 的 `scope` 由前端传值决定，缺省 `"chat"`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:91`），且 scope 不参与访问控制；原始 Key 只在创建与 `regenerate` 的响应里出现一次。`regenerate` 对 PERMANENT／SYSTEM 保护键没有服务端拦截，这是**既定出口而非遗漏**——改／删／停三处的拒绝文案自己写着「use regenerate instead」（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:116`-`:117`、`:142`-`:143`、`:153`-`:154`，保护集 `:40`），Web 侧也按 `keyType` 灰显；iOS 不加客户端拦截，但二次确认要写清「换钥会让既有引用立即失效」。另一条要拍的板是可见性：Web 把这一页挂在管理员门禁下（`harnax-webui/config/routes.ts:136`-`:141`），见 O5。
- 环境变量 `GET /list` 固定返回 `id` / `envKey` / `displayValue` / `sensitive` 四键，其中 `sensitive` 是布尔（全仓其余标志位都是 `0/1` 整数）；掩码三档规则见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:275`-`:279`。
- Token 监控是唯一图表页，一次进页并发 5 个请求、任一返回即重算；会话维度前端截 `slice(0, 10)`，周维度按周一对齐。
- 租户：`GET /api/admin/auth/tenants` 只返回已有 `user_tenant` 关联行的租户，不给管理员放宽，而 `switch-tenant` 对管理员放行任意租户——两个口径不一致，O1 的呈现方案要处理这点。

### 13.6 登录与账号

- 登录页只显示用户名与密码：`GET /api/admin/auth/login-methods` 会宣传手机号／邮箱登录，但两条登录路径实际都按 `getByUsername` 取用户，iOS 不能照着它摆三个入口。
- 管理员标记来自登录响应的 `data.userInfo.isAdmin`（`Int`，`1` 才是管理员，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/LoginResponse.kt:98`），`cli-login` 同样填充它。
- `POST /api/admin/auth/refresh-token` 返回 `accessToken` / `tenantId` / `expiresIn`，没有绝对过期时间，iOS 要自己换算；`switch-tenant` 返回的是**新令牌**而不是只换请求头，切租户后必须整体替换凭据。

### 13.7 跨页面的字段级硬约定

1. 判定成功一律走 `code == 200`；`message` 在成功时也是 `"success"`，不能拿它判空。
2. 分页请求字段是 `pageNum` / `pageSize`，而 Web 前端类型声明的是 `size` / `current`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/Page.kt:9`-`:13`）。iOS 按后端字段解码，不要照抄前端类型。
3. 启停与只读标志是 `0/1` 整数，只有环境变量的 `sensitive` 是布尔。
4. 路由不对称：环境变量是 `/{id}/toggle`，渠道与 API Key 是 `/toggle/{id}`。
5. 前端接口类型文件 `harnax-webui/src/services/ant-design-pro/typings.d.ts`（不是 `harnax-webui/src/typings.d.ts` 那份 umi 声明）里声明了后端并不返回的字段，例如 `ApiKeyItem.keyHash`（`harnax-webui/src/services/ant-design-pro/typings.d.ts:302`，后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyResponse.kt:10`-`:40` 只有 `keyPrefix`/`enabled`）。iOS 建模要按 controller 的 DTO，不按前端类型文件。
6. 操作权限只有两条规则：管理员放行一切，非管理员仅放行 `creator` 等于自己的实体；`isPublic` 不额外放行编辑（`harnax-webui/src/utils/permissionUtil.ts:111`-`:123`）。
7. 后端拒绝原因是英文串且必须原样呈现（删除拦截、stdio 禁用、连通性失败都在 message 里），客户端不能吞成通用报错。
8. 候选下拉普遍被 `pageSize=100` 截断，凡「选了找不到」的场景先怀疑截断。

## 14. 风险与前置条件

| # | 项 | 影响 | 处置 |
|---|---|---|---|
| R1 | 前端走 Nginx 自签证书，`CN = localhost` 且无 SAN 扩展（`harnax-deploy/ssl/server.crt`），ATS 必拦 | 真机连不上 443 | 开发期**绕开 Nginx 直连两个服务的宿主明文端口**（Admin `28080`、Router `28081`，见 `harnax-deploy/docker-compose.yml:190,262`），配 ATS 例外域走 http；对外分发形态再换受信证书。这也反过来印证 §4.1 双基址的必要性 |
| R2 | `cli-login` 无失败锁定与限流 | 免验证码口令口暴露在网络上 | 客户端退避只是补偿；v1.1 后端限流为硬要求，切换登录口的前置条件 |
| R3 | 验证码状态在进程内存 | 走 `login` 后 Admin 多副本即登录失败 | v1.1 切回 `login` 之前需改共享存储，或保证单实例部署 |
| R4 | 永久 API Key 是账号级、无设备维度 | 设备丢失等于账号对话凭据丢失 | iOS 存 Keychain 并受生物识别保护；设备维度凭据列入后端待拍板 |
| R5 | 对话链路各跳超时不一 | 长回答或长确认等待会被中途掐断 | 客户端超时取值需逐跳核对，改任一跳要复核另一跳 |
| R6 | 团队子会话归属在两处判断口径不一致 | 按 id 可能访问到同租户他人会话 | iOS 只按列表返回的 id 访问，不自行拼 id；服务端收敛归属判断另列专项 |
| R7 | 仓库当前无 Swift 工程、无移动端 CI、无品牌图标资源 | M0 需自建骨架与图标基线 | M0 一并解决；DESIGN 类占位图不可直接复用 |

## 15. 待拍板的开放问题

八项已全部拍板（2026-09-28）：O1~O5、O7、O8 按「推荐」列执行，O6 反转为进 v1 并要求后端补渠道 DTO 三列。「推荐」列即执行口径，下表保留理由备查。

| # | 问题 | 推荐 | 理由 |
|---|---|---|---|
| O1 | iOS 是否提供手动切租户 | 提供，但只放在「我的」页、仅对拥有多个租户的账号可见，不进主导航 | Web 已按「多租户由系统自动管理」硬关闭该能力（§5.4）。移动端的合理用途是管理员跨租户排查问题，不是日常切工作区；放主导航等于推翻原裁定 |
| O2 | 对话凭据是否要设备维度 | v1 接受账号级永久 Key，v1.1 与 `cli-login` 限流一起提后端 | 该 Key 一旦泄露等同账号对话权（R4）。iOS 侧 Keychain + 生物识别能挡设备丢失，挡不住主动导出。要后端新增设备登记接口，与零改造前提冲突 |
| O3 | APNs 推送做不做、推什么 | 列入 v1.1，作为 v1.1 唯一新增后端接口项（v1 的后端改动只有 O6 的渠道 DTO 三列）；事件源先只开两个——定时任务失败、工具等待确认 | 移动管理台的真实增量就是「不在电脑前也能被叫回来处理确认」。其余事件（对话结束、Token 报表）噪音大于价值 |
| O4 | 旧移动端工程与移动专用接口面何时下线 | 与 iOS M0 并行做，先删工程再删接口面 | §4.3 的顺序不可颠倒。趁 iOS 还没写第一行 Swift 之前把口径收干净，能避免实现阶段误引到那条面上 |
| O5 | API Key 页是否照搬「仅管理员可见」 | 照搬：非管理员不显示入口，但「我的永久 Key」照常可看 | Web 的门禁只落在前端路由（`harnax-webui/config/routes.ts:136`-`:141` 的 `access: canAccessUserManagement`，判据 `harnax-webui/src/access.ts:13` 即 `isAdmin === 1`），后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt:23`-`:25` 没有任何管理员校验。iOS 若放开入口，等于给普通用户新开一条 Web 上刻意不给的能力面；而 `getMyPermanentKey`（`:128`）本来就是按人取自己的 Key，不必连带上列表 |
| O6 | 渠道级「思考 / 联网 / 计划」三个开关进不进 v1 | **已定案（2026-09-28）：进 v1，后端把三列补进渠道 DTO** | 三列在实体上（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Channel.kt:49`-`:55`）、内部接口会读（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:417`-`:419`），但 admin 侧三个 DTO（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelCreateRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelUpdateRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt`）不带，Web 表单因此从未能编辑。改动落点四处：两个请求 DTO 加字段、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt:86`-`:103` 的 `fromEntity` 加三行映射、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:79` 的创建映射与 `:116` 的更新映射。这是 v1 唯一的后端改动 |
| O7 | `http` 渠道类型给不给入口 | 给，但灰显且不可提交，标注「暂未支持」 | 后端把它当合法值接受，运行时找不到适配器、报 `no adaptor registered`（见 `harnax-ios/specs/03-system-domain.md` 的渠道矩阵）。让用户填完一整张表单才在运行期看到失败，比入口灰显更糟 |
| O8 | 附件收取与下载留不留 v1 | 留，客户端把 404 当作「对象存储未开启」处理 | 该路由整控制器受开关控制（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/OutputFileController.kt:36`-`:39`），`harnax-admin/src/main/resources/application.yml:96` 里 `${MINIO_ENABLED:false}` 默认关，但标准部署 compose 显式置 `true`（`harnax-deploy/docker-compose.yml:178`、`:478`）。也就是说部署环境能用、裸配置环境必 404，需要一条明确的降级表现而不是让页面报错 |
