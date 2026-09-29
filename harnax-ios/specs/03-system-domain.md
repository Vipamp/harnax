# Harnax iOS — 系统域实现规格（Channel / API Key / 环境变量 / Token 监控 / 租户切换）

范围：这四页全部进 v1；用户管理、租户管理两页不进。租户切换保留，走 `POST /api/admin/auth/switch-tenant`。
所有锚点均为实际读过的 `相对路径:行号`。后端 Kotlin 源码目录是 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/`（不是 `src/main/java`）。

## 0. 全局约定（iOS 网络层必须先落地）

- 统一响应包装 `ResultVo<T>` = `{ code, message, data }`，`code == 200` 才算成功；业务失败仍是 HTTP 200 + body code 400（`harnax-webui/src/requestErrorConfig.ts:82-93`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/exception/GlobalExceptionHandler.kt:32-36`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/exception/BizException.kt:10-12` 单参构造默认 code=400）。
- 请求头：`Authorization: Bearer <accessToken>`、`X-Tenant-ID`、`Accept-Language`（`harnax-webui/src/requestErrorConfig.ts:51`、`:53-56`、`:64`）。webui 的 `X-Tenant-ID` 取自缓存的 `currentUser.currentTenantId`，iOS 必须取自「当前生效租户」这一唯一状态源。
- 401 处理：清本地存储并跳登录（`harnax-webui/src/requestErrorConfig.ts:135-167`）。
- 分页体 `Page<T>` = `records / total / pageNum / pageSize / pages`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/Page.kt`，webui 侧另用 `hasPrevious/hasNext` 派生）。

---

## Channel

### 1. 列表页

筛选（三个，`harnax-webui/src/pages/channel/index.tsx:423-445`，参数装配 `:151-193`）：

| 筛子 | 控件 | 请求参数 | 说明 |
|---|---|---|---|
| 关键字 | Input，500ms 防抖 | `keyword` | 后端透传，匹配名称等 |
| 类型 | Select | `type` | 五值小写码 `wecom/wechat/feishu/dingtalk/http`（`harnax-webui/src/pages/channel/index.tsx:46-52`）；后端 `ChannelType.fromCode` **大小写敏感**，iOS 必须发小写 |
| 状态 | Select | `status` | 只有 `1` / `0` 两个取值（`harnax-webui/src/pages/channel/index.tsx:151-193`） |

分页参数：`pageNum` / `pageSize`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ChannelController.kt:34-40`）。

列表列（`harnax-webui/src/pages/channel/index.tsx:259-391`）：`ID` / `name` / `type` / `agentName` / `sessionId` / 沙箱状态 / `createTime` / 操作。
注意：`ChannelResponse` 里带 `tenantId`、`typeDisplayName`、`permissionMode`、`enabled`、`status`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt:74-81` 提供 `getTypeDisplayName`），但**不含 `callbackKey`**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt:36-41` 明确注释），列表也拿不到 `configJson`。

沙箱状态来源：不在 channel 接口里。列表拿到本页 `sessionId` 集合后，另行批量查询 `GET /api/router/agent/workspace/status?sessionIds=a,b,c`，取 `data[x].active` 渲染（`harnax-webui/src/pages/channel/index.tsx:111-128`、`:311-343`、`harnax-webui/src/services/ant-design-pro/workspace.ts:98-110`）。iOS 要把这一步当作「列表二级异步加载」建模，失败时渲染未知而非崩溃。

操作列：编辑 / 启停 / 删除（`harnax-webui/src/pages/channel/index.tsx:358` 起）；操作权限由 `hasOperationPermission` 门禁控制（`harnax-webui/src/pages/channel/index.tsx:358`）；**只有 `type === 'wechat'` 才显示扫码登录按钮**（`harnax-webui/src/pages/channel/index.tsx:366-378`）。

删除前置检查：**有**。确认弹窗（`harnax-webui/src/pages/channel/index.tsx:197-219`）→ 后端 `deleteChannel` 先 `sessionRuntimeReleaser.release(sessionId)` 再 `deleteById`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:163-169`）；`clearSession` 非成功即抛 `BizException(error.session.runtime.release)`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionRuntimeReleaser.kt:42-48`）→ iOS 必须把「运行时释放失败导致删不掉」当成常规错误分支展示，而不是当 bug。
停用前置检查：**没有**。`toggleChannelStatus` 只改 `status`，仅校验值域 0/1（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:133-146`），不检查运行时、不检查引用。

租户隔离：`getChannel` 用 `selectById(id)?.takeIf { it.tenantId == currentTenantId() }`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:62`）——跨租户 ID 直接 404/业务失败，iOS 不要试图用 ID 直连。

### 2. ChannelType × 接入模式 条件字段矩阵

模式全集四类：`webhook` / `websocket` / `stream` / `long_polling`。
可用模式与默认值三处副本必须对齐：前端 `harnax-webui/src/pages/channel/components/channelModes.ts:16-22`（可用集）、`:25-31`（默认值）、`:33-38`（`allowedModes`，type 为空时返回全集）、`:40-46`（`modeFor`，陈旧值回退默认）、后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:360-366`（`MODES_BY_TYPE`，集合顺序即默认值）、`:208-219`（`resolveCommunicationMode`）。

凭证键全集固定 5 个，全部序列化进 `configJson`：`token` / `encodingAesKey` / `appId` / `appSecret` / `webhookUrl`（`harnax-webui/src/pages/channel/components/CreateForm.tsx:82-94`、`harnax-webui/src/pages/channel/components/UpdateForm.tsx:12` `MANAGED_CONFIG_KEYS`）。

| type | 可选模式（★=默认） | 模式选择器 | appId 位字段 | appSecret 位字段 | encodingAesKey | token | webhookUrl | callbackUrl 只读 | 备注 |
|---|---|---|---|---|---|---|---|---|---|
| wecom | `websocket`★（唯一） | 创建时必填；编辑时显示（编辑态仅 wechat 隐藏选择器，`harnax-webui/src/pages/channel/components/UpdateForm.tsx:308-320`） | `Bot ID`，创建必填（`harnax-webui/src/pages/channel/components/CreateForm.tsx:109-118`） | `Bot Secret`，创建必填，密码框（`harnax-webui/src/pages/channel/components/CreateForm.tsx:119-125`） | ✗ | ✗ | ✗ | ✗（非 webhook 不拼） | — |
| wechat | `long_polling`★（唯一） | **隐藏**（`harnax-webui/src/pages/channel/components/CreateForm.tsx:254-267`、`harnax-webui/src/pages/channel/components/UpdateForm.tsx:308-320`） | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | 表单只显示提示文案「无需凭证，创建后点列表微信图标扫码」（`harnax-webui/src/pages/channel/components/CreateForm.tsx:128-138`）；后端强制校正为 long_polling（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:113-115`） |
| feishu | `websocket`★ / `webhook` | 显示，创建必填 | `App ID`，必填（`harnax-webui/src/pages/channel/components/CreateForm.tsx:139-148`） | `App Secret`，必填（`harnax-webui/src/pages/channel/components/CreateForm.tsx:149-155`） | **仅 webhook 模式出现且必填**（`harnax-webui/src/pages/channel/components/channelModes.ts:55-57`、`harnax-webui/src/pages/channel/components/CreateForm.tsx:42-65`） | **仅 webhook 模式出现且必填**（同上 `:56-62`） | ✗ | **仅 webhook 模式出现** | 全系统唯一 `supportsCallback()=true`（`harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuAdaptor.kt:203`，默认 false 见 `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelAdaptor.kt:152`） |
| dingtalk | `stream`★（唯一） | 显示，创建必填 | `App Key`，必填（`harnax-webui/src/pages/channel/components/CreateForm.tsx:159-168`） | `App Secret`，必填（`harnax-webui/src/pages/channel/components/CreateForm.tsx:169-175`） | ✗ | ✗ | ✗ | ✗ | `stream` 是接入模式，与「流式输出」无关 |
| http | `webhook`★（唯一） | 显示，创建必填 | ✗ | ✗ | ✗ | ✗ | `Webhook URL`，**选填**（`harnax-webui/src/pages/channel/components/CreateForm.tsx:178-186`） | **出现**（webhook 模式） | 后端仍视其为合法值但运行时无 adaptor，报 `no adaptor registered`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:355-366` 注释、`harnax-webui/src/pages/channel/components/channelModes.ts:14`） |

矩阵之外的固定字段：

- 创建：`name` 必填、`type` 必填、`agentId` 必填（下拉可搜索，选项来自 `GET /api/admin/agents/page`，`harnax-webui/src/services/ant-design-pro/channel.ts:100-107`）、`communicationMode` 必填（wechat 除外）、`enabled` Switch 默认 true 且提交时转 `1/0`（`harnax-webui/src/pages/channel/components/CreateForm.tsx:213-276`，转换在 `:92`）、`description` 选填 TextArea。
- 后端约束：`name @NotBlank @Size(100)`、`type @NotBlank`、`agentId @NotNull`、`communicationMode @Size(20)`、`permissionMode @Size(20)`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelCreateRequest.kt`）。`permissionMode` **webui 全链路不出现**（前端页面/service/typings 均无该词），iOS 若不填即走后端默认。
- 编辑差异（`harnax-webui/src/pages/channel/components/UpdateForm.tsx`）：① 类型专属凭证字段**一律无 required**（除 feishu+webhook 的 `encodingAesKey`/`token`，`:46-70`），因为已有存量值；② 打开时用 `modeFor(type, stored)` 修正陈旧模式（`:89`）；③ 切换 type 会清空凭证并按新 type 重解析模式（`:275-288`）；④ 提交采用 merge-over-stored 且**总是发送 configJson**（`:122-145`）；⑤ 额外两块只读区：`sessionId`（标 `IMMUTABLE`，`:340-409`、`:404`）与 `callbackUrl`（标 `AUTO`，`:412-481`、`:476`）。

### 3. callbackKey 与回调地址

- `callbackKey = "<prefix>-" + UUID去横线取前 16 位`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:320-324`）；`sessionId = "chn-" + UUID`（`:329`）。
- **callbackKey 永不下发前端**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt:36-41`）。前端只见 `callbackUrl`。
- `callbackUrl` 只在 `communicationMode == "webhook"` 时拼接：`"$baseUrl/api/channel/callback/$callbackKey"`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:190-192`）；`baseUrl` 来自 `${app.base-url}`（`:38-39`，默认值见 `harnax-admin/src/main/resources/application.yml:121` `${APP_BASE_URL:http://localhost:8080}`）。
- 回调接收端真实存在：`@RequestMapping("/api/channel/callback")` + `@PostMapping("/{callbackKey}")`（`harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/endpoint/ChannelCallbackController.kt:44`、`:55`）。这是给平台调的，不是给 iOS 调的。
- iOS 语义：**只读展示 + 复制**，绝不可编辑；非 webhook 模式下该字段根本不存在，UI 要能表达「当前模式无回调地址」。

### 4. configJson 凭证与掩码协议

- `maskSecret`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:311-315`）对 `SECRET_CONFIG_KEYS`（`:342`）内的键做掩码后输出；`keepStoredSecrets` / `isUnchangedMask` 负责「回传值仍是掩码 → 保留库中原值」。
- `validateConfigJson` 上限 20_000 字符（`:228-244`、`:373`）。
- iOS 编辑表单：不要把掩码串当真实值提交；未改动就原样回传掩码，由后端判定保留（等价 `harnax-webui/src/pages/channel/components/UpdateForm.tsx:122-145` 的 merge-over-stored）。

---

## 微信扫码

前端弹窗 `harnax-webui/src/pages/channel/components/WechatLoginModal.tsx`，后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/WechatLoginController.kt` + `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/WechatLoginService.kt`。

- 接口前缀 `@RequestMapping("/api/admin/channels/{id}/wechat")`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/WechatLoginController.kt:28`）。
- 开始：`POST /login`（`:35-37`），`startLogin` 返回 `ResultVo<String>`，`data` 直接是 **base64 PNG data URL**；后端由 zxing 生成 280px PNG 再包 `data:image/png;base64,...`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/WechatLoginService.kt:191-208`）。前端把它原样塞进 `<img src>`，不做二次解码（`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx:60-64`）。iOS 用 `URLSession` 拿到 base64 → `Data(base64Encoded:)` → `UIImage`，注意剥掉 `data:image/png;base64,` 前缀。
- 轮询：`GET /login/status`，间隔固定 **2000ms**（`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx:22` `POLL_INTERVAL_MS`）。
- 状态机：前端 `LoginPhase` 六态（`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx:20`）；后端返回 `WechatLoginStatus(status, message)`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/WechatLoginService.kt:214-217`），`status` 取值 `NOT_LOGIN` / `SCANNED` / `LOGGED_IN` / `EXPIRED` / `ERROR`；无进行中会话时 `NOT_LOGIN` + `message = "No login in progress"`（`:107-139`）。
- 成功后延迟 800ms 回调关闭（`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx:84-98`）；轮询期间单次请求异常**不终止**，继续下一轮（`:99-101`）。
- 取消/超时：关闭弹窗时清定时器并调 `POST /login/cancel`（`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx:105-111`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/WechatLoginController.kt:57-59`）；服务端登录态为内存态 `ConcurrentHashMap`，超时 `LOGIN_TIMEOUT_MINUTES = 5L`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/WechatLoginService.kt:60`），到期强制清理。
- 凭据落库：登录成功后把 `botToken` / `userId` / `botId` / `baseUrl` 写入该渠道 `configJson` 并刷新 `updateTime`（`persistCredentials`，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/WechatLoginService.kt:162-186`）。iOS 扫码成功后必须**重新拉取渠道详情**才能看到新状态。

---

## API Key

页面可见性：Web 侧 `/system/api-key` 带 `access: 'canAccessUserManagement'`（`harnax-webui/config/routes.ts:136`-`:141`），而该 access 就是 `currentUser.isAdmin === 1`（`harnax-webui/src/access.ts:13`）——**这一页对非管理员根本不可见**，与用户管理、租户管理同一条门禁。iOS 是否照搬列入 §15 的 O5。

### 1. 字段与校验

新增（`harnax-webui/src/pages/api-key/components/CreateForm.tsx`）：

| 字段 | 控件 | 必填 | 校验/默认 | 后端约束 |
|---|---|---|---|---|
| `name` | Input | ✅ | maxLength 128（`:13-16` 区） | `@NotBlank @Size(128)`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyCreateRequest.kt`）；且**全局唯一**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:65-67`） |
| `scopes` | Checkbox 组，选项固定 `chat` / `manager`（`harnax-webui/src/pages/api-key/components/CreateForm.tsx:13-16`） | ✅ 至少勾一个 | 提交时 `scopes.join(',')`（`:35`） | `@NotBlank`，**不校验取值集合**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyCreateRequest.kt`） |
| `tenantId` | InputNumber | ❌ | `min=1`，留空表示全局 | **非 admin 时后端忽略前端传值**，用当前用户租户（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:72-78`）；admin 视角 page 里 creator/tenantId 均置 null（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt:41-45`） |
| `rateLimit` | InputNumber | ❌ | `1..10000`，默认 60（`initialValues`，`harnax-webui/src/pages/api-key/components/CreateForm.tsx:61`） | — |
| `expiresAt` | DatePicker | ❌ | 序列化 `YYYY-MM-DDTHH:mm:ss`（`:36`） | 留空 = 永不过期 |

编辑（`harnax-webui/src/pages/api-key/components/UpdateForm.tsx`）：`name` **disabled 不可改**（`:83`）；`scopes` 必填；`tenantId` / `rateLimit` / `expiresAt` 可改；`enabled` Switch。

列表列（`harnax-webui/src/pages/api-key/index.tsx:181-308`）：`name` / `keyPrefix` / `scopes`（Tag，按逗号切分渲染）/ `rateLimit`（渲染成 `${val} /min`）/ `expiresAt`（空显示「永不过期」，过去时间打红标「已过期」）/ `enabled` / `creator` / `createTime` / 操作。
类型陷阱：`harnax-webui/src/services/ant-design-pro/typings.d.ts:299-341` 的 `ApiKeyItem` 声明了 `keyHash` / `active`，**后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyResponse.kt` 并不返回**；iOS 建模以 `ApiKeyResponse` 为准，不要照抄 TS 声明。

### 2. scope 真实语义（对既有裁定的核实结论）

- 页面创建路径走 `scopes = request.scopes ?: "chat"`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:91`）——**尊重前端传值**，不是「恒写 chat」。
- 恒写 `"chat"` 的只有两条内部路径：`createPermanentKeyForUser`（`:204`，keyType=PERMANENT）与 `initSystemKeys`（`:280`，keyType=SYSTEM）。
- scope **不参与访问控制**：`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/AuthContext.kt:15-16` 原文注释 “Retained for logging/observability only — not used for access control.”；内部校验接口也只是原样回传 scopes（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:118-137`）。
- 结论：iOS 可以把 scope 当「标签/展示位」实现，必填勾选只是为了与后端 `@NotBlank` 对齐，不要在客户端做基于 scope 的权限分支。

### 3. 一次性原始 Key

- 生成：`rawKey = "hnx_sk_live_" + Base64URL(32 字节)`，入库只存 SHA-256；`keyPrefix` = 前 12 字符 + `...` + 后 4 字符（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:330-334`、`:336-339`、`:82`）。
- `POST /api/admin/api-keys` 与 `POST /{id}/regenerate` 返回 `ApiKeyCreatedResponse`，字段只有 `id / name / rawKey / keyPrefix`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyResponse.kt:60-72`）；创建与重生成**都**弹出 `RawKeyModal`（`harnax-webui/src/pages/api-key/index.tsx:387-390`、`:163-165`）。
- 展示语义（`harnax-webui/src/pages/api-key/components/RawKeyModal.tsx`）：`maskClosable={false}`（`:28`）、Alert 明确「关闭后将无法再次查看」（`:52-55`）、Paragraph `copyable` + 复制按钮走 `navigator.clipboard.writeText`（`:18-20`）。iOS 用 `UIPasteboard.general.string`；且**任何地方都不缓存 rawKey**，弹窗关闭即销毁，`disableAutocorrection`/截图告警按需。

### 4. 启停删与保护键

- 保护集 `protectedKeyTypes`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:40`）：PERMANENT / SYSTEM 类 key **禁止改、禁止删、禁止停用**（`:116-118`、`:142-144`、`:153-155`）。
- 但 `regenerate` **没有保护检查**（`:162-183`）——这是既有事实，iOS 不要自行加白名单，把后端错误当唯一裁判。
- 重新生成前必须弹确认「将使当前 Key 立即失效」（`harnax-webui/src/pages/api-key/index.tsx:149-174`）。
- 可见性 `accessible`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:309-314`）。iOS 只需处理「操作被拒 → 展示 message」。

---

## 环境变量

字段与校验（`harnax-webui/src/pages/env-variable/components/CreateForm.tsx`）：

| 字段 | 必填 | 校验/默认 | 后端 |
|---|---|---|---|
| `envKey` | ✅ | pattern `/^[A-Za-z_][A-Za-z0-9_]*$/` + maxLength 200（`:49-59`） | `@Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$")`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/EnvVariableCreateRequest.kt:13`） |
| `envValue` | ✅ | maxLength 8192 | `@NotBlank @Size(8192)` |
| `description` | ❌ | maxLength 500 | `@Size(500)` |
| `sensitive` | ❌ | 默认 false（`:16`） | — |
| `enabled` | ❌ | 用组件 `useState` 而非 Form 绑定（`:16`、`:43`） | toggle 参数为 query `?enabled=0/1` |

编辑差异（`harnax-webui/src/pages/env-variable/components/UpdateForm.tsx`）：
- **前端 envKey 缺 pattern 校验**（`:68-71`）——iOS 要补上，否则后端 `@Pattern` 会拒。
- 敏感项**不回填现值**（`:23`）；留空即**不提交 envValue**（`:51-58`）；其余字段逐字段 diff 后才提交。
- 后端：`EnvVariableUpdateRequest` 同样带 `@Pattern`，且 `envValue` 可空表示不改。

敏感值与掩码（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt`）：
- 存储：敏感值 AES 加密后入库；输出走 `maskValue`（`:89-91`、`:221-243`）。
- 回读：`isUnchangedMask` 判定「提交值仍是掩码」→ 保留原值（`:288-291`）。
- 仅切换 `sensitive` 标志时会对原明文重新编码/解密后落库 `reEncode`（`:156-161`、`:326-330`）。
- 唯一键 `uk_env_tenant_creator_active_key`：先预检再兜底 `DuplicateKeyException`（`:84-102`）。
- 隔离：**creator + tenant 双重**隔离（`:40`、`:54`、`:57`），比 channel 更严；iOS 列表默认只能看到自己建的。
- 前置检查：`delete` 与 `toggle(0)` **都会**跑 `assertNotReferencedByAgents`，被引用即拒绝，报错信息最多列出 5 个 agent 名（`:183`、`:199-208`、`:215-217`）。列表页**没有操作权限门禁**（`harnax-webui/src/pages/env-variable/index.tsx:140-248` 全程无 `hasOperationPermission`），iOS 保持同等宽松，只依赖后端拒绝。
- 列表列：`envKey`（可复制）/ `envValue`（sensitive 时 EyeInvisible 图标 + 灰字掩码）/ `description` / `sensitive` Tag / `enabled` StatusSwitch / `creator` / `createTime` / 操作。
- `EnvVariable` 在 webui `harnax-webui/src/services/ant-design-pro/typings.d.ts` 中**无类型声明**，iOS 建模直接以 `dto/EnvVariable*` 为准。

作用域与生效时机：
- 工具执行时按 `envKey` 取当前启用值；投递时优先取最新值、失败回退快照（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:893-939` `resolveEnvBindingsJson`）。
- 按运行时取值走 `getDecryptedValue`，跨租户 / 已停用 / 解密失败**一律返回 null**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:293-318`），调用方（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:769`）不区分这三种情况。
- 生效窗口裁定成立且已有代码依据：agent 侧缓存 `.expireAfterWrite(30, TimeUnit.MINUTES)`（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:69`）→ 工具启动即用，其余最长 30 分钟。iOS 需在 UI 上明示「修改后最长 30 分钟生效」。

---

## Token 监控

页面 `harnax-webui/src/pages/token-monitor/index.tsx`（1161 行），后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt` + `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TokenStatsServiceImpl.kt` + `harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml`。

### 1. 三个筛子

| 筛子 | 前端状态 | 可选值 | 参数名 | 默认 |
|---|---|---|---|---|
| 时间区间 | `dateRange`（`:71-74`） | 任意起止 | `startTime` / `endTime`，格式 **`yyyy-MM-dd HH:mm:ss`**（前端 `:147-148`，后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:35`） | 近 7 天（`subtract(7,'day')` → now；后端兜底 `minusDays(7)`，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:52`、`:81`、`:109`、`:137`、`:165`） |
| 时间粒度 | `timeGranularity`（`:75`） | `hour` / `day` / `week` / `month`（`harnax-webui/src/services/ant-design-pro/tokenStats.ts` 各函数联合类型） | `granularity` | `day`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:77`、`:106`、`:134`、`:162`） |
| 统计维度 | `statDimension`（`:76`） | `token` / `fee` | **不发后端**，纯前端切换图表取哪个度量 | `token` |

**租户不作为参数**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:20-24`、`:37` 注释与 `currentTenantId()` 实现）——iOS 只通过 `X-Tenant-ID` 影响结果。
一次进页并发 5 个请求（`harnax-webui/src/pages/token-monitor/index.tsx:145-155`）。

### 2. 七张统计卡（全部来自 `data.overall`，`harnax-webui/src/pages/token-monitor/index.tsx:177-234`）

| 卡 | 取值字段 | 后缀/格式 |
|---|---|---|
| 总费用 | `overall.totalFee` | `¥` + 两位小数（`:172-174`） |
| 总输入 Token | `overall.totalInputToken` | 另算占 `grandTotalToken` 百分比（`:192-194`） |
| 总输出 Token | `overall.totalOutputToken` | 同上百分比（`:202-204`） |
| 总消耗 Token | `overall.grandTotalToken` | K/M 缩写（`:158-169`） |
| 活跃智能体 | `overall.agentCount` | 个 |
| 活跃会话 | `overall.sessionCount` | 个 |
| 使用模型数 | `overall.modelCount` | 个 |

`overall` 七字段声明见 `harnax-webui/src/pages/token-monitor/index.tsx:30-38`。

### 3. 三个饼图（环形 `innerRadius 0.6`，`@ant-design/plots` Pie）

| 饼图 | 数据源 | 每行字段 | 备注 |
|---|---|---|---|
| 按模型 | `data.modelStats` | `modelId` / `modelName` / `providerName` / `totalInputToken` / `totalOutputToken` / `grandTotalToken` / `totalFee`（`harnax-webui/src/pages/token-monitor/index.tsx:39-47`） | 切片渲染 `:244-362` |
| 按智能体 | `data.agentStats` | `agentId` / `agentName` / 四个度量（`:56-63`） | `:365-480` |
| 按会话 | `data.sessionStats` | `sessionId`(String) / `sessionTitle` / 四个度量（`:48-55`） | **前端 `slice(0, 10)` 只取前 10**（`:483-598`） |

排序由后端保证：三个聚合都 `ORDER BY grandTotalToken DESC`（`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:95-145`）。

### 4. 四个折线图（Line，多系列 `shapeField: 'smooth'`）

| 图 | 接口 | 结构 |
|---|---|---|
| 总体趋势 | `/time-series` | `data.timeSeriesData`（`:601-680`）：token 维度摊平成「输入 Token / 输出 Token / 总消耗」三条系列；fee 维度单系列 |
| 按模型趋势 | `/time-series/model` | 同样 `timeSeriesData`，用 `dimensionName` 作系列名（`:683-774`） |
| 按智能体趋势 | `/time-series/agent` | 同上 |
| 按会话趋势 | `/time-series/session` | 同上 |

统一行形状 `TimeSeriesData`：`timePoint`(String) / `dimensionId`(String) / `dimensionName`(String) / `totalInputToken` / `totalOutputToken` / `grandTotalToken` / `totalFee`（BigDecimal）——`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TokenStatsAggregationResponse.kt:240-255`。归一化为「维度键 → dimensionId/dimensionName」是三套 SQL 共用同一响应结构的关键。
X 轴标签按粒度格式化（hour→`MM-DD HH:mm`，week/month→`MM-DD`/`YYYY-MM`，`harnax-webui/src/pages/token-monitor/index.tsx:622-633`）。

### 5. 前端只需知道的 SQL 语义要点（不必移植，但影响取值）

- 「别名即契约」：Mapper 用 camelCase 别名直接对位 DTO 字段（`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:30-39` 有明确注释）。iOS 只要按字段名解码即可。
- 租户与时间窗谓词无 `<if>` 分支，恒定生效（`:41-58`）→ 不可能出现「不带租户查全量」。
- 度量列定义 `tokenMetrics`（`:60-65`）。
- 时间桶用 `CAST(... AS DATETIME)`，四种桶各一份（`:67-72`）；`timePoint` 因此是字符串。
- 维度列别名 model/agent/session 三种（`:74-76`）。
- 维度时序 `GROUP BY timePoint, 维度键`（`:234` 等）→ **每个桶 × 每个模型 = 一行**，所以行数 = 桶数 × 模型数，前端必须按 `dimensionName` 分组后再画多系列。
- 后端补零：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TokenStatsServiceImpl.kt:202-264` 会把缺失桶补 0，`rowsByBucket` 依赖 DATETIME→LocalDateTime（`:266-282`），granularity 分派 `:81-86`、`:157-180`，**周对齐周一**在 `alignToBucketStart`（`:313-314`）。iOS 不需要自己补零，但按周一对齐的口径展示标题。
- 空态：`overall.grandTotalToken === 0` 时整页渲染 Empty，不画任何图（`harnax-webui/src/pages/token-monitor/index.tsx:878`）。

---

## 租户切换

### 1. 后端契约（iOS 按此实现）

- 租户列表：`GET /api/admin/auth/tenants` → `List<TenantResponse>`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:131-144`）。`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/response/TenantResponse.kt` 字段为 `id / name / status / creator / createTime / updateTime`，**没有 role 字段**，iOS 无法在列表上区分「我是管理员还是普通成员」。
- 切换：`POST /api/admin/auth/switch-tenant`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:149-182`）。请求 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/SwitchTenantRequest.kt:10-14` 只有一个字段 `tenantId: Long @NotNull`。
- 成功响应 `data` = `{ "accessToken": <新 JWT>, "tenantId": <请求值> }`；后端先校验 `isUserInTenant` 或当前为 admin，不满足则 `ResultVo.error("No access to this tenant")`（HTTP 200 + code 400）。
- iOS 收到成功后必须：**替换本地 accessToken**（不是只换 X-Tenant-ID），再刷新 `currentUser`，再重放所有已加载页面数据。
- 列表读的是成员关系：`harnax-entity/src/main/resources/mapper/UserTenantMapper.xml:16-21` 的谓词是 `WHERE user_id = ? AND status = 1 ORDER BY joined_at DESC`，那个 `status` 属于成员行；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/UserTenantServiceImpl.kt:31-43` 把 membership 映射成 `TenantResponse` 时不看租户自身的 status。所以面板里会出现 `status = 0` 的租户行，且 `switch-tenant` 照样给它发令牌——iOS 用「已停用」芯片标注，但不禁用该行。
- 切换响应没有 `expiresIn`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:172-177`），客户端只能沿用登录时算出的到期时刻，不能自己给新令牌定时长。
- keychain 里的 `tenantId` 要按**请求值**写入：`harnax-ios/Sources/HarnaxAPI/APIClient.swift:87-89` 的 `X-Tenant-ID` 就是从这个键读出来的，写错等于把之后每个请求都发回旧租户（`TenantInterceptor.kt:28-58` 优先信这个头）。

### 2. TenantInterceptor 的 X-Tenant-ID 校验规则

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/interceptor/TenantInterceptor.kt`：
- 不带 `X-Tenant-ID` 请求头 → 直接放行，不设置上下文（`:31-33`）。
- 头值非数字 → 错误码 `error.tenant.invalid_id`（中文「无效的租户ID」）。
- 未登录 → `error.auth.not_logged_in`（「用户未登录」）。
- admin 角色 → 跳过成员校验（`:42-45`）。
- 非 admin 且不是该租户成员 → `error.tenant.access_denied`（「无权访问该租户」）。
- 成员记录 `userTenant.status == 0` → `error.tenant.user_disabled`（「您在该租户下已被禁用」）。
- `afterCompletion` 清理 ThreadLocal（`:60-68`）。
- 文案对照 `harnax-admin/src/main/resources/i18n/messages_error_zh_CN.properties:24-26`、`:33`。
- 兜底解析链 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/TenantResolver.kt`：`TenantContext` → token claim(>0) → `sys_user.tenantId`(>0) → `DEFAULT_TENANT_ID = 1`（`:37`、`:50-53`）。iOS 要知道「不带 header 时会落到租户 1」，绝不能依赖这个默认值。

### 3. webui 现状（重要，别照抄）

`harnax-webui/src/components/TenantSwitcher/index.tsx:92` **无条件 `return null`**：整个切换器 UI 不渲染，`handleTenantChange`（`:56-84`）只写 localStorage + `window.location.reload()`，**全仓从未调用过 `switch-tenant`**，其后的 JSX 是死代码。
→ iOS 的租户切换必须是按 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:149-182` 新实现，而不是复刻 webui。

---

## 接口清单

Channel（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ChannelController.kt:22` 前缀 `/api/admin/channels`；前端 `harnax-webui/src/services/ant-design-pro/channel.ts`）

| 方法 + 路径 | 关键请求 | 关键响应 |
|---|---|---|
| `GET /page`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ChannelController.kt:31`） | query `pageNum,pageSize,keyword,type,status`（`:34-44`） | `Page<ChannelResponse>` |
| `GET /{id}`（`:53`） | — | `ChannelResponse` |
| `POST /`（`:66`） | body `ChannelCreateRequest`：`name,type,agentId,communicationMode,enabled(0/1),configJson,description` | `ChannelResponse` |
| `PUT /update/{id}`（`:77`） | body `ChannelUpdateRequest`（同形，总带 `configJson`） | `ChannelResponse` |
| `PUT /toggle/{id}?status=`（`:89-93`） | query `status: Int` | `Boolean` |
| `DELETE /{id}`（`:105`） | — | `Boolean`；运行时释放失败会拒 |
| `GET /api/admin/agents/page`（`harnax-webui/src/services/ant-design-pro/channel.ts:107`） | `pageNum,pageSize` | 供 agentId 下拉 |
| `GET /api/router/agent/workspace/status?sessionIds=`（`harnax-webui/src/services/ant-design-pro/workspace.ts:98-110`） | 逗号拼接 sessionId | `{ [sessionId]: { active, ... } }` |

微信扫码（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/WechatLoginController.kt:28` 前缀 `/api/admin/channels/{id}/wechat`）

| 方法 + 路径 | 请求 | 响应 |
|---|---|---|
| `POST /login`（`:35`） | 无 body | `ResultVo<String>`，data = `data:image/png;base64,...` |
| `GET /login/status`（`:46`） | — | `{ status: NOT_LOGIN\|SCANNED\|LOGGED_IN\|EXPIRED\|ERROR, message }` |
| `POST /login/cancel`（`:57`） | — | `Boolean` |

API Key（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt:23` 前缀 `/api/admin/api-keys`）

| 方法 + 路径 | 请求 | 响应 |
|---|---|---|
| `GET /page`（`:33`） | `pageNum,pageSize,keyword,enabled`（`:36-39`） | `Page<ApiKeyResponse>` |
| `GET /{id}`（`:52`） | — | `ApiKeyResponse` |
| `POST /`（`:65`） | `name,scopes(逗号串),tenantId,rateLimit,expiresAt` | `ApiKeyCreatedResponse{id,name,rawKey,keyPrefix}` |
| `PUT /update/{id}`（`:77`） | `scopes,tenantId,rateLimit,expiresAt,enabled`（name 不可改） | `ApiKeyResponse` |
| `PUT /toggle/{id}?enabled=`（`:89-93`） | query `enabled: Int` | `Boolean` |
| `DELETE /{id}`（`:101`） | — | `Boolean` |
| `POST /{id}/regenerate`（`:112`） | — | `ApiKeyCreatedResponse`（新的 rawKey，一次性） |
| `GET /my-permanent-key`（`:126`） | — | `ApiKeyResponse?` |
| `POST /regenerate-permanent`（`:138`） | — | `ApiKeyCreatedResponse` |

环境变量（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/EnvVariableController.kt:16` 前缀 `/api/admin/env-variables`）

| 方法 + 路径 | 请求 | 响应 |
|---|---|---|
| `GET /page`（`:25`） | `pageNum,pageSize,keyword`（`:28-30`） | `Page<EnvVariableResponse>`（敏感值掩码） |
| `GET /list`（`:39`） | — | `List<Map<String,Any?>>`，供 agent 配置选择 |
| `GET /{id}`（`:48`） | — | `EnvVariableResponse` |
| `POST /`（`:61`） | `envKey,envValue,description,sensitive,enabled` | `EnvVariableResponse` |
| `PUT /update/{id}`（`:72`） | 同上，`envValue` 可缺省表示不改 | `EnvVariableResponse` |
| `PUT /{id}/toggle?enabled=`（`:95-99`） | query `enabled: Int` | `Boolean`；被引用时拒绝 |
| `DELETE /{id}`（`:84`） | — | `Boolean`；被引用时拒绝 |

**路径不对称**：环境变量是 `PUT /{id}/toggle`，channel 与 api-key 是 `PUT /toggle/{id}`（`harnax-webui/src/services/ant-design-pro/envVariable.ts:60` vs `harnax-webui/src/services/ant-design-pro/channel.ts:80`、`harnax-webui/src/services/ant-design-pro/apiKey.ts:56`）。iOS 不要抽一个通用 toggle 方法硬套两种顺序。

Token 统计（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:27` 前缀 `/api/admin/token-stats`，全 GET）

| 方法 + 路径 | 请求 | 响应 |
|---|---|---|
| `GET /aggregation`（`:39`） | `startTime?,endTime?` | `TokenStatsAggregationResponse{overall,modelStats,agentStats,sessionStats}` |
| `GET /time-series`（`:65`） | `startTime?,endTime?,granularity=day`（`:77`） | 同上，前端只取 `timeSeriesData` |
| `GET /time-series/model`（`:94`） | 同上（`:106`） | 同上 |
| `GET /time-series/agent`（`:122`） | 同上（`:134`） | 同上 |
| `GET /time-series/session`（`:150`） | 同上（`:162`） | 同上 |

租户（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt`）

| 方法 + 路径 | 请求 | 响应 |
|---|---|---|
| `GET /api/admin/auth/tenants`（`:131-144`） | — | `List<TenantResponse{id,name,status,creator,createTime,updateTime}>` |
| `POST /api/admin/auth/switch-tenant`（`:149-182`） | `{ tenantId: Long }` | `{ accessToken, tenantId }`；无权限时 code 400 + `No access to this tenant` |

---

## iOS 适配注意点

1. **图表重画（Swift Charts）**：webui 用 `@ant-design/plots`，一次进页并发 5 请求、任一返回就 setState。iOS 上若照做，5 个 `@Published` 会触发 5 次全页重算 body，Swift Charts 的 `LineMark` 序列在维度时序上是「桶 × 模型」笛卡尔积（`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:234`），行数可达数百，动画抖动明显。建议：① 5 个请求 `async let` 并发但**全部落地后一次性提交**给视图；② 按 `dimensionName` 预分组成 `[String: [TimePoint]]` 存 VM，视图只读；③ 关闭 `.animation`，只在用户显式改筛子时启用过渡；④ 会话维度按前端 `slice(0,10)` 同样截断（`harnax-webui/src/pages/token-monitor/index.tsx:483-598`），饼图颜色用固定调色板（webui 是 Tableau 风 8 色循环，`:237-239`），避免每次刷新换色。
2. **回调地址只读**：`callbackUrl` 只有 webhook 模式才存在，且 `callbackKey` 从不下发（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt:36-41`）。iOS 用不可编辑的 LabeledContent + 「拷贝」按钮，不要给 `TextField`；非 webhook 模式整行隐藏（对齐 `harnax-webui/src/pages/channel/components/UpdateForm.tsx:412-481` 的条件渲染）。`sessionId` 同理标 IMMUTABLE，任何编辑尝试都应拒绝。
3. **轮询与前后台**：三处轮询周期不同——微信扫码 2s（`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx:22`）、Token 监控是手动刷新不进轮询、沙箱状态是随列表刷新。iOS 规则：① 扫码轮询只在弹窗 scene 内用 `Timer`/`Task` 循环，`scenePhase != .active` 立即暂停并在回前台续跑（服务端 5 分钟硬超时 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/WechatLoginService.kt:60`，后台冻结会让 QR 直接变 EXPIRED，需在 UI 明示「二维码可能已过期，请重新获取」而不是静默失败）；② 单次请求失败**继续轮询**，与 `harnax-webui/src/pages/channel/components/WechatLoginModal.tsx:99-101` 一致，只有拿到 `EXPIRED`/`LOGGED_IN` 或用户关闭才停；③ 关闭弹窗/页面消失必须 `cancel`，否则内存态会话残留到超时（`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx:105-111`）。
4. **一次性密钥**：`rawKey` 只在 `POST`/`regenerate` 响应里出现一次（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyResponse.kt:60-72`）。iOS 用 fullScreenCover + `interactiveDismissDisabled(true)` 对齐 `maskClosable={false}`（`harnax-webui/src/pages/api-key/components/RawKeyModal.tsx:28`），关闭后从内存清除、不写 Keychain、不进日志；复制用 `UIPasteboard.general.string`，并在 iOS 16+ 注意系统「已粘贴」提示是预期行为。
5. **掩码字段编辑**：channel `configJson` 与 env `envValue` 都走「掩码回传 = 不修改」协议（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:311-315`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:288-291`）。iOS 的 SecureField 必须在 focus 时区分「用户没动」和「用户输了新值」，最稳妥是把未动的字段从 PATCH body 里整个省掉（对齐 `harnax-webui/src/pages/env-variable/components/UpdateForm.tsx:51-58`）。
6. **权限与门禁**：channel 列表有 `hasOperationPermission`（`harnax-webui/src/components/EntityCard/index.tsx:358`），env-variable 列表没有（`harnax-webui/src/components/EntityCard/index.tsx:140-248`）。iOS 不要统一加 gate；统一加会让环境变量对合法用户不可操作。
7. **业务错误一律看 body**：删除渠道、停用环境变量、改保护 key 都会以 HTTP 200 + code 400 + message 返回。iOS 的 URLSession 层不能只判 HTTP 状态码，必须在 decode `ResultVo` 后按 `code != 200` 抛错，并把 `message` 原文呈现（本地化由 `Accept-Language` 决定，`harnax-webui/src/requestErrorConfig.ts:64`）。
8. **租户切换是「换 token」**：`switch-tenant` 返回新 `accessToken`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:149-182`），不是只切 header。iOS 必须原子更新 token + 当前租户 + 重新拉 `currentUser` + 让所有已加载域失效；只改 `X-Tenant-ID` 会被 TenantInterceptor 按成员关系拒绝（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/interceptor/TenantInterceptor.kt:42-45`）。
9. **不要依赖不带 header 的默认租户**：TenantResolver 最终兜底到 `DEFAULT_TENANT_ID = 1`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/TenantResolver.kt:37`），iOS 每个请求都必须显式带 `X-Tenant-ID`。

---

## 未确认

1. **已核实 + 已裁定（O6，定案为进 v1）**：Channel「流式开关」不存在——全 webui 无 `enableStream`/`isStream`，`stream` 只是 dingtalk 的接入模式。`Channel` 实体确有 `enableThink`/`enableSearch`/`enablePlan`（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Channel.kt:49`-`:55`，内部接口会读它们，见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:417`-`:419`），但 admin 侧三个渠道 DTO（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelCreateRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelUpdateRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt`）逐文件 grep 这三列零命中，Web 表单因此从未能编辑。裁定：iOS v1 暴露这三个开关，后端同步把三列补进渠道 DTO——落点为两个请求 DTO 加字段、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt:86`-`:103` 的 `fromEntity` 加三行映射、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:79` 的创建映射与 `:116` 的更新映射；三列取值沿用实体的 0/1 `Int`，Web 表单是否跟进不在本规格范围。
2. **`permissionMode` 取值域已查实**：五个字符串值 `DEFAULT`／`BYPASS`／`ACCEPT_EDITS`／`EXPLORE`／`DONT_ASK`（文案见 `harnax-webui/src/locales/zh-CN/pages.ts:871-875`，会话侧 DTO 字段 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionResponse.kt:50`）。后端只声明 `String?` 加 `@Size(20)`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelCreateRequest.kt:32`），没有枚举校验，因此 iOS 要自己把这五个值建成枚举并在提交前拦非法值；渠道侧前端零处引用，v1 按「不发送、原样透传已存值」处理。
3. **已裁定（O7）**：`http` 类型的实际可用性——后端注释明确它「无 adaptor 注册，运行时报 `no adaptor registered`」（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:355-366`、`harnax-webui/src/pages/channel/components/channelModes.ts:14`）。裁定：iOS 表单保留该类型选项但灰显不可提交，标注「暂未支持」。
4. **已核实**：`regenerate` 不带保护键检查是**既定出口而不是遗漏**。改、删、停三处的拒绝文案自己写着「use regenerate instead」（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:116`-`:117`、`:142`-`:143`、`:153`-`:154`，保护集 `protectedKeyTypes = setOf("PERMANENT", "SYSTEM")` 见 `:40`），而 `regenerateApiKey` 只走 `loadAndCheckAccess`（`:162`-`:183`）。webui 侧对任意可操作行都渲染重新生成按钮、不按 `keyType` 灰显（`harnax-webui/src/pages/api-key/index.tsx:282`-`:299`）。iOS 照此实现：不加客户端拦截，但要把「换掉 PERMANENT/SYSTEM 的密钥会让既有引用立即失效」写进二次确认文案。
5. **环境变量 `GET /list` 的返回结构已核实**：投影固定四个键 `id` / `envKey` / `displayValue` / `sensitive`，其中 `sensitive` 是 **Boolean** 而非全仓惯用的 0/1 `Int`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:252`-`:269`），iOS 解码这一处要按布尔写。`displayValue` 的掩码规则是三档：长度 ≤4 全掩、≤8 首尾各留 1 字符、其余首 3 尾 2（同文件 `:275`-`:279`），因此「回填后原样提交」必然写出星号；候选只含 `enabled == 1` 且按调用者可见范围过滤。
6. **租户列表的可见范围已核实，且与切租户口不一致**：`GET /api/admin/auth/tenants` 只返回已经存在 `user_tenant` 关联行的租户（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/UserTenantServiceImpl.kt:31`-`:47`），对管理员没有放宽；而 `POST /api/admin/auth/switch-tenant` 在 `isAdmin == 1` 时跳过成员检查、允许切到任意租户（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:159`-`:162`）。结果是管理员的切换列表可能为空却能切成功。iOS 若要呈现切换器（O1），列表源要么补一次「全部租户」读，要么接受管理员看不到未加入的租户。
7. **`workspace/status` 的失败语义**：`active` 之外还有哪些字段、未创建过沙箱的 `chn-` 会话返回缺省还是条目不存在（`harnax-webui/src/services/ant-design-pro/workspace.ts:98-110`、`harnax-webui/src/pages/session/index.tsx:111-128`），未核实；iOS 的未知态渲染口径待定。
8. **Token 监控周对齐时区**：`alignToBucketStart` 用周一对齐（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TokenStatsServiceImpl.kt:313-314`），但是否按服务器时区还是客户端时区截断、`timePoint` 字符串是否带时区，未读到显式声明；跨时区设备的边界桶归属需实测。
