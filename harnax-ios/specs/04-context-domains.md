# iOS 规格 04：上下文域（模型 / 工具 / MCP / 技能 / CLI）

本文是 harnax-webui 上下文域五个页面重做为 SwiftUI 原生 iOS App 的字段级实现规格。所有锚点均为仓库内真实读过的行号，格式 `相对路径:行号`。后端统一返回 `ResultVo<T>` 信封 `{code, message, data}`；分页统一 `Page<T>` `{records, total}`。所有路径前缀 `/api/admin`，鉴权为 JWT（`Authorization: Bearer`），iOS 直接沿用。

---

## 模型

路由：`harnax-webui/config/routes.ts:69` `/context/model`。页面为**两级结构**：上方厂商（provider）卡片流 + 下方该厂商的模型表格；选中一个厂商才渲染模型表。

### 页面骨架与状态

- 视图模式 `viewMode`（card / table）：`harnax-webui/src/pages/model/index.tsx:31`。
- 厂商分页大小固定 8：`harnax-webui/src/pages/model/index.tsx:52`。
- 厂商列表查询参数拼装：`harnax-webui/src/pages/model/index.tsx:178-199`（name、type、status、isPublic、pageNum、pageSize）。
- 厂商类型筛选下拉：`harnax-webui/src/pages/model/index.tsx:411-416`。
- 模型筛选区：模型类型 + 能力标签多选 `harnax-webui/src/pages/model/index.tsx:525-545`；价格区间两个 Input（minPrice / maxPrice）`harnax-webui/src/pages/model/index.tsx:559-576`。
- 标签枚举（前端写死，用于多选筛选）：`harnax-webui/src/pages/model/index.tsx:18-24`，值为 internet / reasoning / tool / mcp / vision。
- 模型表容器：`harnax-webui/src/pages/model/index.tsx:580-586`。

### 厂商卡片（ProviderCard）

`harnax-webui/src/pages/model/components/ProviderCard.tsx`

- 卡片额外拉统计：`GET /api/admin/model-providers/{id}/stats`，`harnax-webui/src/pages/model/components/ProviderCard.tsx:36-53`。
- 按 type 映射图标与主色：`harnax-webui/src/pages/model/components/ProviderCard.tsx:56-73`（dashscope / openai / ollama 等，未知走默认色）。
- 传给通用卡片的属性：`harnax-webui/src/pages/model/components/ProviderCard.tsx:93-133` —— 三个统计格（总数 / 启用 / 停用）、`showTest` `showEdit` `showDelete`、`onClick` 选中该厂商。

通用卡片布局（iOS 需照此重排）：`harnax-webui/src/components/EntityCard/index.tsx:92-101`
- 顶部渐变标识条；头部 = 图标 + 名称 + 类型标签 + 右上角启停开关；中部描述（空则显示"暂无描述"）；统计区为网格（可选）；底部 = 分割线 + 左侧（公开标签、创建人、创建时间）+ 右侧操作按钮。
- `StatItem` 支持 `popoverContent`（hover 列表）与 `popoverMaxWidth` 默认 320：`harnax-webui/src/components/EntityCard/index.tsx:20-30`。
- 颜色必须用十六进制而非 CSS 变量（内部要拼透明度后缀）：`harnax-webui/src/components/EntityCard/index.tsx:44-52`。iOS 里对应 `Color(hex:)` + `.opacity()`，无此限制但要保持色值来源一致。

厂商响应字段（`ModelProviderResponse`）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderResponse.kt:13-43`
id / type / name / description / apiKey（**永远脱敏回显**）/ baseUrl / status(0 禁用 1 启用) / isPublic(0 私有 1 公开，默认 0) / creator / createTime / updateTime。
脱敏规则 = 前 2 字符 + `****` + 后 4 字符，长度 ≤6 时整体 `****`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderResponse.kt:60-68`。

统计响应 `ModelStatsInfo`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelStatsInfo.kt:10-16`
totalModels / enabledModels / disabledModels。

### 厂商表单（创建 / 编辑）

`harnax-webui/src/pages/model/components/ProviderForm.tsx`；请求体 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderCreateRequest.kt`。

| 字段 | 控件 | 必填 | 校验 | 默认 | 后端锚点 | 前端锚点 |
|---|---|---|---|---|---|---|
| type | Select（dashscope/openai/ollama…） | 是 | `^[a-z0-9_]+$`，1-50 | 无 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderCreateRequest.kt:11-14` | `harnax-webui/src/pages/model/components/ProviderForm.tsx:151-164` |
| name | TextField | 是 | 1-100 | 无 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderCreateRequest.kt:17-19` | 同上表单 |
| description | TextEditor | 否 | ≤500 | 空 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderCreateRequest.kt:22` | — |
| apiKey | SecureField | 否 | ≤500 | 不回显 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderCreateRequest.kt:26-27` | `harnax-webui/src/pages/model/components/ProviderForm.tsx:42-60` |
| baseUrl | TextField | 否 | 正则允许空串 | 空 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderCreateRequest.kt:30-34` | `harnax-webui/src/pages/model/components/ProviderForm.tsx:197-211`（`type:'url'` 客户端校验） |
| isPublic | Switch | 否 | 0/1 | 1（创建时默认公开） | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderCreateRequest.kt:38` | `harnax-webui/src/pages/model/components/ProviderForm.tsx:42-60,213-224` |

关键点：
- **编辑时 type 禁用**（不可改）：`harnax-webui/src/pages/model/components/ProviderForm.tsx:151-164`。iOS 用 `.disabled(isEditing)`。
- **apiKey 绝不回填**，留空 = 不修改：`harnax-webui/src/pages/model/components/ProviderForm.tsx:42-60`；update DTO 的 description/apiKey 注释同样声明"空表示不修改"：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderUpdateRequest.kt:21-27`。
- baseUrl 的正则在 create 与 update 两处**不完全一致**（create 允许空，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderCreateRequest.kt:30-34`；update 不允许空串，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderUpdateRequest.kt:29-32`）。iOS 表单需按模式切换正则。

### 模型表格

`harnax-webui/src/pages/model/components/ModelListTable.tsx`
- 一次拉 100 条且不显示分页器：`harnax-webui/src/pages/model/components/ModelListTable.tsx:57-88`（`pageSize: 100`）。iOS 建议 `List` + 服务端分页，不要照抄"无分页 + 100 条"。
- 列：名称、技术名、类型、能力标签、价格、是否公开、创建人、状态开关；操作列按权限门控：`harnax-webui/src/pages/model/components/ModelListTable.tsx:178-255`。
- 能力标签渲染：`harnax-webui/src/pages/model/components/ModelListTable.tsx:137-176`。`thinkingMode === 2` 渲染红色"必需思考"标签。

模型响应字段 `ModelResponse`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelResponse.kt:11-53`
id / name / modelName / providerId / providerName / description / modelType / supportInternet / supportReasoning / thinkingMode / supportTool / supportMcp / supportVision / contextWindow / price / status / isPublic / creator / createTime / updateTime，外加**计算字段 tags**：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelResponse.kt:82-89`（`calculateTags`，由 support_* 整数推导 internet/reasoning/tool/mcp/vision）。

### 模型表单：thinkingMode 与四个能力开关

`harnax-webui/src/pages/model/components/ModelForm.tsx`；请求体 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelCreateRequest.kt`。

语义（三值，不是布尔）：
- 0 = 不支持思考；1 = 可选思考；2 = **强制思考**。
- DDL 注释即权威定义：`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:336-362` 的 `model.thinking_mode NOT NULL DEFAULT 0`，注释 `0:not supported, 1:optional, 2:required`。
- 后端上下界校验 `@Min 0 @Max 2` 且可空：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelCreateRequest.kt:39-41`；update 同规则：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelUpdateRequest.kt:34-37`。

前端交互规则（必须逐条复刻）：
1. 能力开关**仅 chat 模型可用**；`modelType` 非 chat 时重置全部能力位为 0：`harnax-webui/src/pages/model/components/ModelForm.tsx:67-78`。
2. 四个开关 = reasoning / internet / vision / tool（+ mcp），`harnax-webui/src/pages/model/components/ModelForm.tsx:206-233`；thinkingMode 下拉（0/1/2）同样仅 chat 显示：`harnax-webui/src/pages/model/components/ModelForm.tsx:235-250`。
3. 编辑回填时 thinkingMode 的**兼容推导**：`thinkingMode ?? (supportReasoning === 1 ? 1 : 0)`：`harnax-webui/src/pages/model/components/ModelForm.tsx:33-65`。旧数据只有 supportReasoning 时据此还原。
4. 提交时**反向推导**：`supportReasoning = (thinkingMode ?? 0) >= 1 ? 1 : 0`：`harnax-webui/src/pages/model/components/ModelForm.tsx:84-99`。即 thinkingMode 1/2 都会把 supportReasoning 置 1；iOS 必须保留这一耦合，否则后端 tags 计算（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelResponse.kt:82-89`）与 Agent 侧选模型逻辑会不一致。
5. providerId 为隐藏字段（由选中厂商注入）：`harnax-webui/src/pages/model/components/ModelForm.tsx:149-151`。
6. modelName 必填：`harnax-webui/src/pages/model/components/ModelForm.tsx:160-165`；后端 `@NotBlank @Size 1-100`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelCreateRequest.kt:16`。
7. price 步进 0.0001，含义"元 / 百万 token"：`harnax-webui/src/pages/model/components/ModelForm.tsx:178-184`；后端 `@DecimalMin 0.0` 默认 0.0：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelCreateRequest.kt:56-58`。
8. contextWindow 是数字输入（`step 1`、`min 1`、示例 128000），仅 tooltip 说明留空语义：`harnax-webui/src/pages/model/components/ModelForm.tsx:186-197`；提交时 `contextWindow ? parseInt(contextWindow, 10) : null`：`:92`，回填直接取 `values.contextWindow`：`:43`。后端两侧都可空且 `@Min 1`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelCreateRequest.kt:52-54`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelUpdateRequest.kt:48-50`；create 直接赋值（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ModelServiceImpl.kt:132`，null 即留给运行时推断），update 走 `?.let`（`:186`，键缺席即保留已存值）。iOS 复刻：数字键盘、留空就不带这个键（不得填 0）、填了必须是不小于 1 的整数、编辑从行数据回填。

其余后端校验：name `@NotBlank @Size 1-100` `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelCreateRequest.kt:11`；providerId `@NotNull` `:22`；modelType `@NotBlank` `:29`；support* 五个 `Int? = 0` `:33/:36/:44/:47/:50`；isPublic `= 1` `:61`。

### 启停 / 删除 / 连通性测试

- 厂商启停：`PUT /api/admin/model-providers/toggle/{id}`，前端 `harnax-webui/src/pages/model/index.tsx:220-246`（乐观本地翻转 + 失败回滚）。
- 厂商删除：`DELETE /api/admin/model-providers/{id}`，前端 `harnax-webui/src/pages/model/index.tsx:249-267`，删除前需确认（厂商下有模型时后端行为见"未确认"）。
- **连通性测试**：`POST /api/admin/model-providers/{id}/test` → `ResultVo<Boolean>`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelProviderController.kt:122`。前端调用：`harnax-webui/src/pages/model/index.tsx:270-282`；返回 `data === true` 提示成功，`false` 提示失败，`code != success` 提示后端 message。**没有耗时、错误详情等结构化字段**，iOS 里只能给"通过 / 不通过"两态（若要做进度条需后端补字段）。
- 模型启停：`PUT /api/admin/models/toggle/{id}?status=`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelController.kt:101`。
- 模型删除：`DELETE /api/admin/models/{id}`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelController.kt:114`。
- 写接口一律返回 `ResultVo<Void>`（create/update）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelController.kt:74,87`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelProviderController.kt:72,85`。iOS 不要指望拿到新建 id。

权限：编辑/删除/启停门控统一走 `hasOperationPermission(isAdmin, currentUser, creator)`（管理员放行，否则仅本人创建）：`harnax-webui/src/utils/permissionUtil.ts:111-120`；用户信息从 localStorage 的 `currentUser` 读取，`isAdmin === 1` 才算管理员：`harnax-webui/src/utils/permissionUtil.ts:80-97`。iOS 应改为从登录态内存/Keychain 取，且**服务端仍是最终裁决**（前端隐藏按钮只是体验）。
`isPublic` 开关禁用规则（两条）：不可编辑则禁用；非管理员编辑已公开实体时不能改回私有：`harnax-webui/src/utils/permissionUtil.ts:57-75`。

---

## 工具

路由：`harnax-webui/config/routes.ts:75` `/context/tool`。**确认：该页是只读的内置工具表**，前端无任何创建/编辑/删除入口。

后端契约自证：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:13-15` 的类注释 "Read-only: tools are registered by the code, so this API exposes no write path."。全部端点均为 GET：`/page` `:26`、`/{id}` `:41`、`/available` `:54`（排除强制工具）、`/builtin` `:67`（"always enabled" 的启用行）、`/{id}/required-env-params` `:80`。

- 数据源：页面只调 `/builtin`：`harnax-webui/src/pages/tool/index.tsx:39-51`。iOS 对应只读列表。
- 搜索为**客户端过滤**（拿全量后按关键字过滤 name/description）：`harnax-webui/src/pages/tool/index.tsx:58-68`。iOS 建议保持本地过滤（内置工具量小）或改服务端 keyword。
- 表格**不分页**：`harnax-webui/src/pages/tool/index.tsx:165-181`（`pagination={false}`）。
- 列定义与标签规则：`harnax-webui/src/pages/tool/index.tsx:70-141` —— name、description、beanName/methodName（实现定位）、`needConfirm === 1` 橙色"需确认"标签、`isRequired === 1` 红色"必需"标签、状态。

字段与 **Int 约定**（0/1 而非 bool，全后端如此）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentToolResponse.kt`
- `readOnly`：`:33`，注释 `0:No, 1:Yes` —— 表"内置工具、不可改"。
- `needConfirm`：`:36`，`0:No, 1:Yes` —— 执行前需人工确认。
- `isRequired`：`:39`，`0:optional, 1:required` —— 强制绑定到所有 Agent。
- `envParams`：`:30`；`requiredEnvParamKeys`：`:42`（仅必需参数名，供快速判断）；`status`：`:45`；`beanName`：`:24`；`methodName`：`:27`。

iOS 侧解码建议统一写一个 `Int01` 的 ExpressibleByBooleanLiteral / `Bool` 转换扩展，避免 0/1 与 true/false 在 5 个域里反复手写。

### 环境变量参数 Popover 的数据来源

- 表格里的"参数"列点击/悬浮渲染 `EnvParamsPopover`，数据即 `AgentToolResponse.envParams`（列表接口直接带回，不额外请求）。编辑器组件：`harnax-webui/src/pages/tool/components/ToolEnvEntriesEditor.tsx`；条目形状：`harnax-webui/src/pages/tool/components/ToolEnvEntriesEditor.tsx:6-11`。
- 约定："必需参数必须有默认值；secret 值以密码框呈现"：`harnax-webui/src/pages/tool/components/ToolEnvEntriesEditor.tsx:55`。
- Popover 展示逻辑：`harnax-webui/src/components/EnvParamsPopover/index.tsx:24` count 取 `entries.length`，无条目时回退 `fallbackCount`；`:44-91` 悬浮表格列为 Param Name / Description / Required / Sensitive / Default；`:94-103` 触发器是紫色数量 Tag。
- 后端条目 DTO：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt:12-31` —— id / envParamName / description / required / secret / defaultValue。
- 独立只读接口：`GET /api/admin/tools/{id}/required-env-params`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:80`）；iOS 列表页不需要，工具详情或 Agent 表单才用。

---

## MCP

路由：`harnax-webui/config/routes.ts:81` `/context/mcp`（列表）、`:85` `/context/mcp/detail/:id`（详情）。
后端：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:22` 前缀 `/api/admin/mcp`。

### 卡片列表

`harnax-webui/src/pages/mcp/index.tsx`
- endpoint 展示规则：**type 为 stdio 时显示 command，否则显示 url**：`harnax-webui/src/pages/mcp/index.tsx:44`。
- 点击卡片进详情：`harnax-webui/src/pages/mcp/index.tsx:48`。
- OAuth 徽标：authType 为 OAUTH2 时卡片加标签：`harnax-webui/src/pages/mcp/index.tsx:99-102`。
- type → 颜色/图标映射：`harnax-webui/src/pages/mcp/index.tsx:146-153`（stdio / sse / streamablehttp）。
- 列表筛选下拉里 **stdio 仍然出现**（前端不隐藏历史 stdio 行）：`harnax-webui/src/pages/mcp/index.tsx:492-495`。

响应字段 `McpServerResponse`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerResponse.kt:14-44`
id / name / description / type / command / url / authType / oauthConfig / status / isPublic / creator / createTime / updateTime / headers(`List<McpConfigEntry>`) / envParams(`List<ToolEnvParamEntry>`)。
**header / envParam 的 secret 值在返回前被掩码**：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerResponse.kt:55-56`。
`McpConfigEntry`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpConfigEntry.kt:10-18` —— key / value / secret。
`oauthConfig` 结构：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt:13-30` —— authorizationServer / scopes / audience / resourceIndicator（**默认 true**）。

### 创建 / 编辑表单

`harnax-webui/src/pages/mcp/components/CreateForm.tsx`、`harnax-webui/src/pages/mcp/components/UpdateForm.tsx`；请求体 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerCreateRequest.kt` / `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerUpdateRequest.kt`。

| 字段 | 控件 | 必填 | 校验/默认 | 锚点 |
|---|---|---|---|---|
| name | TextField | 是 | ≤100 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerCreateRequest.kt:13` |
| description | TextEditor | 否 | — | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerCreateRequest.kt` |
| type | Segmented（**仅 sse / streamablehttp**） | 是 | 默认 `streamablehttp` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerCreateRequest.kt:25-26`；stdio 不提供 `harnax-webui/src/pages/mcp/components/CreateForm.tsx:26-32` |
| command | TextField（stdio 才显示） | stdio 必填 | — | `harnax-webui/src/pages/mcp/components/CreateForm.tsx:154-175` |
| url | TextField（非 stdio） | 是 | 正则校验 URL | `harnax-webui/src/pages/mcp/components/CreateForm.tsx:177-207` |
| headers | 动态 key/value + secret 勾选 | 否 | `List<McpConfigEntry>` | `harnax-webui/src/pages/mcp/components/CreateForm.tsx:177-207` |
| envParams | 动态条目（stdio 用） | 否 | 必需项须有默认值 | `harnax-webui/src/pages/mcp/components/CreateForm.tsx:86-95` |
| authType | Picker：NONE / STATIC_HEADER / OAUTH2 | 否 | 省略即 NONE；**BASIC 已不在选项中** | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerCreateRequest.kt:41`；`harnax-webui/src/pages/mcp/components/CreateForm.tsx:35-43` |
| oauthConfig | 子表单（仅 OAUTH2 时提交） | 否 | 非 OAUTH2 时整体剔除 | `harnax-webui/src/pages/mcp/components/CreateForm.tsx:96-102` |
| isPublic | Switch | 否 | 省略即公开 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerCreateRequest.kt:49-50` |

交互细节（iOS 必须复刻）：
- 切换 type 会**清空另一套传输字段**；切到 stdio 时强制 authType = NONE：`harnax-webui/src/pages/mcp/components/CreateForm.tsx:129-152`。
- OAUTH2 选项在 stdio 下禁用：`harnax-webui/src/pages/mcp/components/CreateForm.tsx:35-43`。
- 编辑回填必须补 `oauthConfig: { resourceIndicator: true, ...(values.oauthConfig || {}) }`：`harnax-webui/src/pages/mcp/components/UpdateForm.tsx:31-49`。
- 编辑时 **stdio 只在"本来就是 stdio"时可选**（不允许切进 stdio）：`harnax-webui/src/pages/mcp/components/UpdateForm.tsx:79-85`。
- update 请求体所有字段可空，语义为"省略即不变"：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerUpdateRequest.kt:10-58`；oauthConfig 同规则 `:42-46`。iOS 编码时要用 `encodeIfPresent` + 只发变更字段，否则会把未填字段清掉。
- 改 URL 的警示文案："changing URL clears every user grant"：`harnax-webui/src/pages/mcp/components/UpdateForm.tsx:245-254`（需二次确认弹窗）。
- 表单内连通性测试返回 `string | null`（null 通过，字符串为原因）：`harnax-webui/src/pages/mcp/components/UpdateForm.tsx:88-111`。
- 取消/重置时要同时还原组件本地 state（不只 form 值）：`harnax-webui/src/pages/mcp/components/CreateForm.tsx:255-264`。
- OAuth 子字段：authorizationServer 有正则、scopes 用 `mode="tags"` 多选可自填、audience 文本、resourceIndicator 开关默认 true：`harnax-webui/src/pages/mcp/components/OAuthFields.tsx:19-34,36-48,50-56,58-66`。**clientId/clientSecret 不在此表单**（属于 OAuthPanel）：`harnax-webui/src/pages/mcp/components/OAuthFields.tsx:11-16`。

### 启停 / 删除（含关联 Agent 检查）

- 启停：`PUT /api/admin/mcp/toggle/{id}?status=`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:99`；前端 `harnax-webui/src/pages/mcp/index.tsx:361-378`。
- 关联查询：`GET /api/admin/mcp/{id}/related-agents` → `ResultVo<List<RelatedAgentInfo>>`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:111-123`；元素字段 agentId / agentName / status(0/1)：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:175-180`。列表按当前租户过滤：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:152-164`。
- 删除流程（**先查关联再决定文案，最后才发 DELETE**）：`harnax-webui/src/pages/mcp/index.tsx:314-358`，helper 在 `:302-311`。有关联 Agent 时弹窗列出受影响 Agent，明确删除会解除绑定；`DELETE /api/admin/mcp/{id}`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:125-134`（**逻辑删除**）。
- related-agents 失败时前端不阻断删除，只是省略清单（iOS 里同样不要把查询失败当成"无关联"，也不要因查询失败禁用删除按钮 —— 见接口清单的 skipErrorHandler 约定）。

### 连通性测试

- 端点：`POST /api/admin/mcp/{id}/connectivity-test` → `ResultVo<Boolean>`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:136-146`。失败时 `code != success` 且 message 为原因（例如 stdio 被策略拒绝）。
- 前端把结果与 **15 秒超时竞赛**并置：`harnax-webui/src/pages/mcp/index.tsx:381-438` —— 15s 内无响应即本地判"超时"，但请求仍在后台；iOS 用 `URLSession` 的 `timeoutIntervalForRequest = 15` 或 `Task` + `withTimeout`，并在 UI 上区分"服务端失败"与"本地超时"两种文案。
- 返回值只有布尔，没有 tools count / latency；详情里的"工具列表"是另一个接口。

---

## MCP 详情与 OAuth

### 详情页数据源

`harnax-webui/src/pages/mcp/detail.tsx`
- 详情主体：`GET /api/admin/mcp/{id}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:63`），用于"配置信息"卡片：`harnax-webui/src/pages/mcp/detail.tsx:277-342`；页头 `DetailPageHeader`：`:236-275`。
- authType 中文标签映射：`harnax-webui/src/pages/mcp/detail.tsx:34-38`。
- **工具列表仅在 `status === 1` 时才请求**：`harnax-webui/src/pages/mcp/detail.tsx:79-83`；调用带 `skipErrorHandler`，以便区分"调用失败"与"服务器没有工具"：`harnax-webui/src/pages/mcp/detail.tsx:99-120`。三态空文案（未启用 / 加载失败 / 无工具）：`harnax-webui/src/pages/mcp/detail.tsx:383-392`。
- 端点：`GET /api/admin/mcp/{id}/list_tools` → `ResultVo<List<McpToolResponse>>`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:148-175`。参数由 `inputSchema.properties` 摊平为 `{name, type, description}`，type 缺省 `"string"`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:154-171`。DTO：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpToolResponse.kt:9-29`。
- **规格修正（以代码为准）**：任务描述里说参数"可展开"，实际前端是**行内直接渲染参数标签**，无 expandable 行：`harnax-webui/src/pages/mcp/detail.tsx:126-201`。iOS 用小屏友好的"参数 chip 流 + 长按/点击看详情"即可，不必做可展开表格；若要做展开，需自行保留原始 `inputSchema`（当前接口已丢弃 required / enum 等信息）。
- OAuthPanel 只在 authType === OAUTH2 时挂载：`harnax-webui/src/pages/mcp/detail.tsx:345-347`。

### stdio 在运行时被禁：前端如何呈现（已核验）

后端策略：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt`
- 开关属性 `harnax.mcp.stdio-enabled`，**默认 false**：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt:20-23`。
- 拒绝原因全文（说明 stdio 会真正 spawn 进程、admin 容器以 root 挂宿主 docker socket）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt:26-31`。
- agent 侧还有第二道同名开关 `harness.mcp-stdio-enabled`，两者都开才真跑：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt:16-18`。

前端呈现（三处，均保留历史 stdio 行但不给入口）：
1. 创建表单的 type 选项里**根本没有 stdio**：`harnax-webui/src/pages/mcp/components/CreateForm.tsx:26-32`，并注明原因指向 `McpStdioPolicy` / `HARNAX_MCP_STDIO_ENABLED=false`。
2. 编辑表单里 stdio 仅在该行本就是 stdio 时保留可选（避免改别的字段时 type 漂移）：`harnax-webui/src/pages/mcp/components/UpdateForm.tsx:79-85`。
3. 列表筛选与 endpoint 展示仍认 stdio（显示 command）：`harnax-webui/src/pages/mcp/index.tsx:44,492-495`。
4. 运行时禁用不靠前端提示，而靠 `connectivity-test` / `list_tools` 返回失败 + message（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:136-146,148-175`），前端把 message 原样展示。
iOS 结论：**只提供 sse / streamablehttp 两种创建/编辑选项**；已有 stdio 行只读展示 command；点击"测试"或"工具列表"若拿到策略拒绝 message，直接展示，不要本地翻译成"网络错误"。

### OAuth 五个端点的调用顺序与状态机

端点全在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt:36` 前缀 `/api/admin/mcp`；用户身份完全来自 JWT，**路径里没有 userId**：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt:30-34`。

| # | 端点 | 锚点 | 请求 | 响应 |
|---|---|---|---|---|
| 1 | `POST /{id}/oauth/discover` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt:45` | 无 body | `McpOAuthDiscoveryResponse`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthDiscoveryResponse.kt:11-50` |
| 2 | `POST /{id}/oauth/client` | `:59` | `McpOAuthClientRequest`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthClientRequest.kt:14-31` | 保存结果（掩码回显） |
| 3 | `GET /{id}/oauth/authorize-url?scope=` | `:74` | query scope 可选 | `McpOAuthAuthorizeResponse`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthAuthorizeResponse.kt:14-25` |
| 4 | `POST /oauth/exchange` | `:90` | `McpOAuthExchangeRequest`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthExchangeRequest.kt:20-35` | `McpOAuthExchangeResponse`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthExchangeResponse.kt:20-31` |
| 5 | `GET /{id}/oauth/status` / `POST /{id}/oauth/revoke` | `:109` / `:123` | — | `McpOAuthStatusResponse`（status ∈ ACTIVE / NEEDS_CONSENT / REVOKED 或 null）`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthStatusResponse.kt:13-33`；revoke → `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthRevokeResponse.kt:13-21` |

前端实际调用顺序（`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx`）：
1. **挂载时只发唯一无副作用的一次调用**：`GET /{id}/oauth/status`，`:70-95`。这是状态机初态。
2. `knownIssuer = discovery?.issuer || config?.authorizationServer`：`:68`。**"注册 client"按钮在 knownIssuer 存在前禁用**：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:253-273` → 所以 discover 是 client 的前置条件。
3. `POST /{id}/oauth/discover`：仅管理员可见（会做外网访问并写库）：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:150-168`。结果面板同时展示 `callbackUrl`（已存）与 `defaultCallbackUrl`（当前部署推导值），两者不一致时给**陈旧告警**：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:390-517`。
4. `POST /{id}/oauth/client`：模态保存，`clientSecret = ''` 表示清除，省略/掩码表示保留：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:176-217`；后端语义 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthClientRequest.kt:14-31`（clientId `@NotBlank` ≤255，callbackUrl ≤500）。
5. `GET /{id}/oauth/authorize-url` → 授权跳转：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:97-115`，**当前是同标签页 `window.location.href = authorizeUrl`**。
6. `POST /{id}/oauth/revoke` → 确认 → **重新读 status**：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:117-148`。
7. 授予状态徽标三态（未授权 / 有效 / 需重新同意 / 已撤销）：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:231-243`。
8. 说明文案：渠道会话没有用户身份，因此不加载 OAuth server：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:323-331`。
9. client 模态字段清单：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:519-589`。

服务端 state 机制（决定 iOS 改造方式）：
- `state` **在 authorizeUrl 的 query 里，不在响应体**：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthAuthorizeResponse.kt:14-25`；URL 拼接在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt:153`。
- 服务端为每个 state 存一条 `PendingAuthorization(tenantId, userId, mcpId, issuer, codeVerifier, redirectUri, resource, requestedScopes, expiresAtNanos)`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt:17-30`。
- **刻意用内存 ConcurrentHashMap，不落库**，理由与后果（用户在同意页期间服务重启 ⇒ "unknown or expired"，必须重新发起）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt:32-43`、`:49`。
- `consume()` 在真正换 token **之前**移除条目 ⇒ state 不可重放：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt:77-82`。
- TTL 5 分钟：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt:106`；全局上限 500 条：`:109`；每用户上限 5 条：`:118`；超限检查在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt:127`，state 生成 `:134`，登记 `:146`，消费 `:174`，跨用户持有 state 会被拒：`:184`。
- redirectUri 取值：优先库里存的 `client.callbackUrl`，否则用部署默认值：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt:103`。
- PKCE + RFC 8707 resource indicator + RFC 9728 元数据发现 + RFC 7009 revocation；**DCR（动态注册）未实现**，所以 client 必须人工填（这正是 discover→client 两步存在的原因）。
- exchange 路径**不带 MCP id**（设计上由 state 反查 mcpId）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt:98-100`；被拒绝时不是 HTTP 错误，而是 `authorized = false`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt:101-102` → iOS 必须按 body 字段判定，不能只看 HTTP 状态码。
- exchange 请求体字段全部可选：code ≤4096、state ≤512、error ≤128、errorDescription ≤1024：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthExchangeRequest.kt:20-35`。**要区分"用户取消/被拒"与"网络失败"**：前者带 error 字段，走 authorized=false。

### 现有 Web 回跳依赖什么（iOS 必须替换的部分）

`harnax-webui/src/pages/mcp/oauth-callback.tsx`
- 回调路由常量 `CALLBACK_PATH = '/mcp/oauth/callback'`：`harnax-webui/src/pages/mcp/oauth-callback.tsx:9`；路由注册 `harnax-webui/config/routes.ts:161-163`。
- 页面直接从 URL query 读 `code` / `state` / `error` / `error_description`，随后 `history.replace` **先把 query 抹掉再 exchange**：`harnax-webui/src/pages/mcp/oauth-callback.tsx:35-44`。
- state / code 从不落到 useState 持久化或 localStorage：`harnax-webui/src/pages/mcp/oauth-callback.tsx:19-28`。
- `authorized === true` → 成功提示；否则错误提示：`harnax-webui/src/pages/mcp/oauth-callback.tsx:72-91`。
- redirect_uri 本质是**前端页面 URL**，由部署变量 `APP_FRONTEND_BASE_URL` / `APP_BASE_URL` 推导（即 `defaultCallbackUrl`），与库里存的 `callbackUrl` 做陈旧比对：`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:390-517`。

因此 Web 方案依赖三件事：(a) 回调是浏览器可寻址的 http(s) 页面；(b) `state` 只走 URL query，服务端内存保存其上下文；(c) exchange 是一次带 JWT 的普通 JSON POST。

### CLI 与 Skill 关联（供交叉验证）

见「CLI」「技能详情」两节；MCP 域本身不含 CLI 字段。

---

## 技能与仓库

路由：`harnax-webui/config/routes.ts:92` `/context/skill`。页面为**左右分栏**：左 = 技能来源（skill source）列表，右 = 该来源下的技能表。
布局断点：左 `Col xs={24} md={6}`、右 `Col xs={24} md={18}`：`harnax-webui/src/pages/skill/index.tsx:183,220` → 窄屏上下堆叠。iOS 用 `NavigationSplitView`，紧凑宽度退化为 push 栈。

- 来源列表加载：以每页 50 循环拉全量：`harnax-webui/src/pages/skill/index.tsx:40-68`；自动选中第一条：`:58-62`。iOS 建议直接 `GET /api/admin/skill-sources/active`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt:43`）一次拿完，不必复刻"循环分页拼全量"。

### 四种来源类型（以代码为准）

DDL：`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:509-537`（`skill_repository`，含 `builtin_guard` 生成列与 `last_sync_*` 列）。
后端 create/update 的 `sourceType` 取值以 GIT / NPM / ZIP 为表单可选项；**BUILTIN 由种子数据产生、管理端只读**：`harnax-webui/src/constants/builtinRepository.ts:1-9`（`BUILTIN_CLI_SKILL_REPO = 'builtin-cli-skills'`，说明其技能只经 CLI 关联下发，仓库与其技能在管理端只读）。

前端表单只暴露 **GIT / NPM / ZIP** 三种（BUILTIN 不出现在选项中且编辑时 sourceType 禁用）：`harnax-webui/src/pages/skill/components/RepositoryForm.tsx:198-211`（实际路径 `harnax-webui/src/pages/skill/components/RepositoryForm.tsx:198-211`）。
**sourceType 不可变**：update 请求体根本没有 sourceType 字段 —— `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceUpdateRequest.kt:8-36`；create 有：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceCreateRequest.kt:8-40`。

各类型差异字段：
- GIT：`url` + `branch`。列表展示：`harnax-webui/src/pages/skill/components/RepositoryList.tsx:394-426`。
- NPM：`packageName`。同上锚点。
- ZIP：**上传后由后端保存原始文件名 `originalFilename`**，编辑时不显示 url/branch：同上锚点。
- BUILTIN：无外部配置，卡片类型标签含 BUILTIN：`harnax-webui/src/pages/skill/components/RepositoryList.tsx:349-357,364-377`。

来源响应字段 `SkillSourceResponse`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceResponse.kt:9-62`
id / name / description / sourceType / url / branch / packageName / originalFilename / status / lastSyncStatus / lastSyncTime / lastSyncDetail(`Map`) / enabledSkillCount（`:62`）。

### 上传（multipart 字段名）

- 端点：`POST /api/admin/skill-sources/upload`，参数 `@RequestParam("file") MultipartFile` + `@RequestParam("name") String`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt:137`。
- 前端 FormData 的字段名与之对应，就是 `file` 与 `name`：`harnax-webui/src/services/ant-design-pro/skillSource.ts:119-133`；调用条件"ZIP + 创建"：`harnax-webui/src/pages/skill/components/RepositoryForm.tsx:71-83`。
- 控件约束：accept `.zip`、`maxCount: 1`、`beforeUpload` 返回 false（手动提交而非自动上传）：`harnax-webui/src/pages/skill/components/RepositoryForm.tsx:258-277`。iOS 用 `fileImporter` + 手工 `multipart` 组包，见「iOS 适配注意点」。

### 来源 CRUD / 安装 / 同步

| 操作 | 端点 | 锚点 | 前端锚点 |
|---|---|---|---|
| 分页 | `GET /api/admin/skill-sources/page` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt:28` | `harnax-webui/src/pages/skill/index.tsx:40-68` |
| 全量启用 | `GET /api/admin/skill-sources/active` | `:43` | — |
| 详情 | `GET /api/admin/skill-sources/{id}` | `:52` | — |
| 创建（非 ZIP） | `POST /api/admin/skill-sources` → `SkillSourceInstallResponse` | `:65` | `harnax-webui/src/pages/skill/components/RepositoryForm.tsx:85-90,130-132` |
| 安装/重装 | `POST /api/admin/skill-sources/{id}/install`（body 可选） → `SkillInstallResponse` | `:74` | `harnax-webui/src/pages/skill/components/RepositoryList.tsx:304-314` |
| 更新配置 | `PUT /api/admin/skill-sources/{id}` | `:86` | `harnax-webui/src/pages/skill/components/RepositoryForm.tsx:104-127` |
| 删除 | `DELETE /api/admin/skill-sources/{id}` | `:100` | `harnax-webui/src/pages/skill/components/RepositoryList.tsx:248-270` |
| 启停 | `PUT /api/admin/skill-sources/toggle/{id}` | `:114` | `harnax-webui/src/pages/skill/components/RepositoryList.tsx:248-270` |
| 预览（不落库） | `GET /api/admin/skill-sources/{id}/fetch` → `List<SyncSkillResponse>` | `:127` | `harnax-webui/src/pages/skill/index.tsx:127-152` |
| ZIP 上传 | `POST /api/admin/skill-sources/upload` | `:137` | `harnax-webui/src/pages/skill/components/RepositoryForm.tsx:71-83` |

- 安装请求体：`names` 省略 = 安装全部：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceInstallRequest.kt:13-15`。
- 创建响应把来源与安装结果一起返回：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceInstallResponse.kt:12-17` = `{source, install}`。
- 编辑 ZIP/GIT/NPM 配置时**表单不提交 url/branch**，并提示"Configuration saved. Reinstall…"（改了配置必须重装才生效）：`harnax-webui/src/pages/skill/components/RepositoryForm.tsx:104-127`。
- `Install` 与 `Sync` 按钮对 ZIP 与 BUILTIN 隐藏：`harnax-webui/src/pages/skill/components/RepositoryList.tsx:441,451`。
- 删除受 enabledSkillCount 阻断：`harnax-webui/src/pages/skill/components/RepositoryList.tsx:304-314` —— 该来源下还有启用中的技能时不给删。
- 同步是**两步式**：先 `fetch` 拿预览，再开模态让用户勾选，最后 `install`：`harnax-webui/src/pages/skill/index.tsx:127-152`。
- 同步详情面板按 failed / flagged / stale / sourceError 分区渲染：`harnax-webui/src/pages/skill/components/RepositoryList.tsx:73-81,153-183`；同步状态中文映射 `harnax-webui/src/pages/skill/components/RepositoryList.tsx:30-35`；相对时间格式化 `harnax-webui/src/pages/skill/components/RepositoryList.tsx:48-65`。
- 重装前有确认弹窗：`harnax-webui/src/pages/skill/components/RepositoryList.tsx:273-301`。
- 页面级 toggle：本地翻转 + 失败刷新：`harnax-webui/src/pages/skill/index.tsx:107-124`。
- 只读来源判定：`name === BUILTIN_CLI_SKILL_REPO`：`harnax-webui/src/pages/skill/index.tsx:279`。

### 同步模态

`harnax-webui/src/pages/skill/components/SyncSkillModal.tsx`
- 打开时**全部预选** + 提供全选/全不选：`harnax-webui/src/pages/skill/components/SyncSkillModal.tsx:38-52`。
- 提交 `installSkillSource(repositoryId, skillNames)`，**无论结果如何都刷新右侧表**：`harnax-webui/src/pages/skill/components/SyncSkillModal.tsx:66-89`。
- 预览条目列（含"已存在"标签，用于区分新增/更新）：`harnax-webui/src/pages/skill/components/SyncSkillModal.tsx:91-144`。
- 预览 DTO：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SyncSkillResponse.kt:9-18` —— name / description / skillmd / resources / exists。

### 安装结果语义（重要：200 ≠ 全成功）

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillInstallResponse.kt:12-68`
installed / updated / failed[{name,reason}] / flagged[{name,reasons}] / sourceError / emptyReason / stale / savedCount / failedCount / complete / summary。
前端把结果翻译成提示的优先级规则（iOS 应原样移植为 alert 分级）：`harnax-webui/src/utils/skillInstall.ts:32-129`
1. `install` 缺失 → 退化为通用"成功"（不能拿 0 谎报）：`:39-46`。
2. `failed.length > 0 && savedCount === 0` → error「一个都没存」：`:52-60`。
3. `failed.length > 0`（有存）→ warning「部分成功」：`:62-70`。
4. `flagged.length > 0` → warning（被内容安全扫描拦下、置为禁用待审）：`:72-83`。
5. `sourceError` → error「源读不出来」：`:88-96`。
6. `emptyReason` → warning「源里没有可装技能」：`:98-106`。
7. `savedCount === 0` → **warning 而非绿色**「源里没有可装技能」：`:108-118`。
8. 否则 success「已保存 N 个」：`:120-128`。
失败明细最多展示 3 条：`harnax-webui/src/utils/skillInstall.ts:23`；提示停留 6 秒：`:131-132`。

### 右侧技能表

`harnax-webui/src/pages/skill/components/SkillList.tsx`
- 列：name（链接进详情）、description、isPublic、creator、updateTime（表头文案 'Sync Time'）、status：`harnax-webui/src/pages/skill/components/SkillList.tsx:27-138`。
- 启停规则：`harnax-webui/src/pages/skill/components/SkillList.tsx:94-134` —— 当 `boundAgentCount > 0 || boundTeamCount > 0` 时禁止禁用/删除，并按 Agent / Team / 两者 三种情况给不同 tooltip。该约束的后端出处即 DTO 注释："A bound skill can be neither disabled nor deleted from here"：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillResponse.kt:34-36`。
- 只读技能（内置）渲染标签而非开关：`harnax-webui/src/pages/skill/components/SkillList.tsx:15`。
- **该页对技能没有删除操作**（与任务描述中的"delete"不同，以代码为准）：`harnax-webui/src/pages/skill/components/SkillList.tsx:27-138` 无 delete 列。
- 技能启停端点：`PUT /api/admin/skills/toggle/{id}`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillController.kt:101`。技能 CRUD 端点存在但本页不调用：`/page` `:35`、`/{id}` `:64`、`POST` `:78`、`/update/{id}` `:89`、`DELETE /{id}` `:114`、`POST /batch?repositoryId`（body `List<String>`）`:129`。
- 技能响应字段：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillResponse.kt:12-44`（含 boundAgentCount `:34`、boundTeamCount `:36`）。
- DDL：`skill` 表 `resources` 为 JSON `path → content`：`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:486-507`。
- **agent ↔ team 双向 blocked 拦截**在删除/禁用来源与技能时都体现：来源侧靠 `enabledSkillCount`（`harnax-webui/src/pages/skill/components/RepositoryList.tsx:304-314`），技能侧靠 `boundAgentCount` / `boundTeamCount`（`harnax-webui/src/pages/skill/components/SkillList.tsx:94-134`）。

> 说明：`/api/admin/skill-repositories`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillRepositoryController.kt:35,66,82,100,116,141,168,184`）是**遗留接口，技能页未使用**，iOS 不要接。

---

## 技能详情

路由：`harnax-webui/config/routes.ts:96` `/context/skill/detail/:id`。页面：`harnax-webui/src/pages/skill/detail.tsx`。

### Tab 结构与数据源

- 只有一个数据源：`GET /api/admin/skills/{id}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillController.kt:64`）。前端调用后 **`JSON.parse(res.data.resources)`** 得到 `Record<path, content>` 扁平映射，并自动选中第一个 key：`harnax-webui/src/pages/skill/detail.tsx:192-224`。
- **规格修正（以代码为准）**：任务描述提到"文件内容读取接口"，实际不存在独立内容读取接口 —— 文件内容随 `{resources}` 一次性返回。iOS 不要预留二次拉取。
- Tab 两项：`skillmd`（Markdown 正文）与 `resources`（资源文件树）：`harnax-webui/src/pages/skill/detail.tsx:291-354`（skillmd）、`:355-523`（resources）。
- Markdown 正文字段即技能实体的 `skillmd`；类型定义里 resources 为 `Record<string,string>`：`harnax-webui/src/typings.d.ts:461-468`（`SkillSyncItem.resources?: Record<string,string>`）。正文只取开头 frontmatter 块（`---` 起、`---` 止）之后的部分，`name` / `description` 由页头给出；剥除判据与服务端 `SkillFileParser.stripFrontmatter`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/SkillFileParser.kt:138-143`）逐字一致，首行是 `---` 但找不到闭合分隔线时按原文展示。
- 页头 `DetailPageHeader` 带来源名 / 来源 URL / 分支：`harnax-webui/src/pages/skill/detail.tsx:560-582`。

### 文件树数据结构与呈现

- 树由扁平路径**在前端构造**（`buildTree()`，按 `/` 分段）：`harnax-webui/src/pages/skill/detail.tsx:231-280`。iOS 直接复用该算法（对 `Dictionary<String,String>` 的 key 排序建树）。
- 布局：左树固定 320px + 右内容区；**窄屏改为上下堆叠且树区 maxHeight 200**：`harnax-webui/src/pages/skill/detail.tsx:355-523`。iOS 用 `NavigationSplitView` 三栏降级为两栏（列表 → 内容），或紧凑宽度下用 `List` + push。
- 内容渲染按扩展名映射语言高亮：`harnax-webui/src/pages/skill/detail.tsx:35-61`；渲染器 `FileContentRenderer`：`:94-171`。iOS 用 `TextEditor` 只读 + 简单语法着色（或 `AttributedString`），映射表照抄 `:35-61`。
- 无任何写操作：**技能详情页纯只读**（没有编辑资源、上传、删除文件的入口）。iOS v1 同样只读。

---

## CLI

路由：`harnax-webui/config/routes.ts:103` `/context/cli`。页面：`harnax-webui/src/pages/cli/index.tsx`。

### 与最新 DDL 的一致性核验（结论：一致）

- DDL 基线是**机械合并 V1~V50 的单文件**：`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:1-18`。
- 其中明确：**V35 DROP 了 `cli_plugin` / `agent_cli_plugin_binding` / `cli_skill_binding`**：`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:8-9`；V37 退役了种子 harnax-cli 技能但**保留其仓库行**：`:10-11`。
- `cli` 现存列：`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:203-224` —— `skill_id`（`:209`）、`package_digest`（`:215`）、`payload_digest`（`:216`）、`deps_apt`（`:218`）、`runtime_env`（`:219`）、`package_object`，唯一键 `uk_cli_name`。**没有 cli_plugin 关联表**，与前端字段一致。
- 控制器类注释：**没有 create / update / delete**。CLI 由把 `.harnaxcli.zip` 放进 admin 包目录、启动时由 `CliPackageAutoRegistrar` 扫描登记（设计 D2）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:18-25`。
  → iOS v1 的 CLI 页**只能是只读列表 + kill-switch**，不要做增删改。
- 响应字段：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt:21-58`
  列表：id / name / description / version / checkCommand / packageDigest / envParams / skill{skillId, skillName, skillDescription} / status / createTime / updateTime。
  详情额外：payloadDigest、depsApt、runtimeEnv（`convertToDetailResponse`，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:59`）。
  **没有 isPublic，也没有 creator** → 因此列表无法用"是否本人创建"做权限门控，只有管理员语义（见"未确认"）。
- 前端类型同结构：`harnax-webui/src/typings.d.ts:616-623` `CliDetail = CliItem & {payloadDigest, depsApt, runtimeEnv}`。

### 列表列

`harnax-webui/src/pages/cli/index.tsx:174-276`（8 列）：name、version、description、checkCommand、packageDigest（截断显示）、envParams 数量、skill 名、status 开关。
页面整体只读定位说明：`harnax-webui/src/pages/cli/index.tsx:16-20`。

### 启停拦截（kill-switch）与 related-agents 门禁

`harnax-webui/src/pages/cli/index.tsx:82-172`
- 启停端点：`PUT /api/admin/clis/toggle/{id}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:72`）；**失败时用 `Modal.error` 显示后端 message 而不是 toast**：`harnax-webui/src/pages/cli/index.tsx:82-108`。
- 关联查询：`GET /api/admin/clis/{id}/related-agents` → `List<RelatedAgentInfo>`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:84-93`）。
- 门禁分支（**iOS 必须完整复刻这段状态机**）：`harnax-webui/src/pages/cli/index.tsx:114-172`
  1. related-agents **请求失败** → 不发启停请求（避免在不知爆炸半径时改动）。
  2. **结果为空** → 直接执行启停。
  3. **启用**且有结果 → 执行，并提供后续刷新会话入口。
  4. **停用**且有结果 → 先弹确认，正文列出前 5 个 Agent 名 → 确认后执行 → 再提供刷新入口。

### 会话刷新流程

- 关联会话：`GET /api/admin/clis/{id}/related-sessions` → `List<RelatedSessionInfo>`：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:95-103`。元素字段：sessionId / sourceType("channel"|"session") / sourceName / agentName（仅按 CLI 查询时填充）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:182-188`。
- 刷新端点：`POST /api/admin/agents/refresh-sessions`，body `{sessionIds: string[]}` → `ResultVo<List<SessionRefreshResult>>`（sessionId / success / error）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:105-117`，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:190-194`；前端封装 `harnax-webui/src/services/ant-design-pro/agent.ts:176-183`。
- 共享模态：`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx`
  RelatedSession 形状 `:11-17`；按 `source`（如 `"cli"`）切换取数端点并**默认全勾选** `:47-78`；`refreshAgentSessions(checked)` 后逐条统计部分失败 `:80-115`；**加载失败与"结果为空"区分显示** `:174-186`；channel/session 标签配色，sessionId 截断到 24 字符 `:193-217`。
- CLI 页接入点：`harnax-webui/src/pages/cli/index.tsx:337-343`（`<AgentRefreshModal source="cli" />`）。

### 详情抽屉字段分组

`harnax-webui/src/pages/cli/components/CliDetailDrawer.tsx`；宽 560：`:73`；它是 `GET /api/admin/clis/{id}` 的**唯一读者**：`:21-24`。
Descriptions 顺序（`:86-207`）：version → description → status → checkCommand → envParams（标签流）→ skill（单个标签）→ packageDigest → **payloadDigest，标签文案为 'Image Fingerprint'（镜像指纹）** → depsApt（标签流）→ runtimeEnv（`key = value` 行，遍历自 `Object.entries(detail?.runtimeEnv || {})`，`:60`）→ createTime。

按任务要求的三组归类（iOS 建议分 Section）：
- 包 / 镜像指纹：packageDigest、payloadDigest（'Image Fingerprint'）。
- apt 依赖：depsApt（列表，仅详情返回）。
- 运行时环境：runtimeEnv（Map，仅详情返回）、envParams（键位声明）、checkCommand（健康检查命令）。

---

## 接口清单

格式：方法 + 路径 + 关键请求字段 → 关键响应字段。锚点为后端定义。

### 模型
| 方法/路径 | 请求 | 响应 | 锚点 |
|---|---|---|---|
| `GET /api/admin/models/page` | name, providerId, modelType, status, tags, minPrice, maxPrice, pageNum, pageSize | `Page<ModelResponse>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelController.kt:32` |
| `GET /api/admin/models/{id}` | — | `ModelResponse` | `:61` |
| `POST /api/admin/models` | `ModelCreateRequest` | `Void` | `:74` |
| `PUT /api/admin/models/update/{id}` | `ModelUpdateRequest`（可空=不变） | `Void` | `:87` |
| `PUT /api/admin/models/toggle/{id}` | query `status` | `Void` | `:101` |
| `DELETE /api/admin/models/{id}` | — | `Void` | `:114` |
| `GET /api/admin/model-providers/page` | name, type, status, isPublic, 分页 | `Page<ModelProviderResponse>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelProviderController.kt:33` |
| `GET /api/admin/model-providers/{id}` | — | `ModelProviderResponse` | `:59` |
| `POST /api/admin/model-providers` | `ModelProviderCreateRequest` | `Void` | `:72` |
| `PUT /api/admin/model-providers/update/{id}` | `ModelProviderUpdateRequest` | `Void` | `:85` |
| `PUT /api/admin/model-providers/toggle/{id}` | `status` | `Void` | `:99` |
| `DELETE /api/admin/model-providers/{id}` | — | `Void` | `:113` |
| `POST /api/admin/model-providers/{id}/test` | — | `Boolean` | `:122` |
| `GET /api/admin/model-providers/{id}/stats` | — | `ModelStatsInfo` | `:134` |

### 工具（全部只读）
| 方法/路径 | 响应 | 锚点 |
|---|---|---|
| `GET /api/admin/tools/page` | `Page<AgentToolResponse>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:26` |
| `GET /api/admin/tools/{id}` | `AgentToolResponse` | `:41` |
| `GET /api/admin/tools/available` | `List<AgentToolResponse>`（排除强制工具） | `:54` |
| `GET /api/admin/tools/builtin` | `List<AgentToolResponse>`（技能页数据源） | `:67` |
| `GET /api/admin/tools/{id}/required-env-params` | `List<ToolEnvParamEntry>` | `:80` |

### MCP
| 方法/路径 | 请求 | 响应 | 锚点 |
|---|---|---|---|
| `GET /api/admin/mcp/page` | pageNum, pageSize, keyword, status, type | `Page<McpServerResponse>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:32` |
| `GET /api/admin/mcp/{id}` | — | `McpServerResponse` | `:63` |
| `POST /api/admin/mcp` | `McpServerCreateRequest` | `Void` | `:76` |
| `PUT /api/admin/mcp/update/{id}` | `McpServerUpdateRequest`（省略=不变） | `Void` | `:87` |
| `PUT /api/admin/mcp/toggle/{id}` | query `status` | `Void` | `:99` |
| `GET /api/admin/mcp/{id}/related-agents` | — | `List<RelatedAgentInfo>` | `:111` |
| `DELETE /api/admin/mcp/{id}` | — | `Void`（逻辑删） | `:125` |
| `POST /api/admin/mcp/{id}/connectivity-test` | — | `Boolean` | `:136` |
| `GET /api/admin/mcp/{id}/list_tools` | — | `List<McpToolResponse>` | `:148` |

### MCP OAuth
| 方法/路径 | 请求 | 响应 | 锚点 |
|---|---|---|---|
| `POST /api/admin/mcp/{id}/oauth/discover` | — | `McpOAuthDiscoveryResponse`（issuer、元数据、`callbackUrl` vs `defaultCallbackUrl`） | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt:45` |
| `POST /api/admin/mcp/{id}/oauth/client` | `McpOAuthClientRequest`(clientId, clientSecret?, callbackUrl) | 保存结果（掩码） | `:59` |
| `GET /api/admin/mcp/{id}/oauth/authorize-url` | query `scope`（可选） | `McpOAuthAuthorizeResponse`（authorizeUrl 内嵌 state） | `:74` |
| `POST /api/admin/mcp/oauth/exchange` | `{code?, state?, error?, errorDescription?}` | `McpOAuthExchangeResponse`（`authorized` 布尔） | `:90` |
| `GET /api/admin/mcp/{id}/oauth/status` | — | `McpOAuthStatusResponse`（ACTIVE/NEEDS_CONSENT/REVOKED/null） | `:109` |
| `POST /api/admin/mcp/{id}/oauth/revoke` | — | `McpOAuthRevokeResponse` | `:123` |

### 技能 / 来源
| 方法/路径 | 请求 | 响应 | 锚点 |
|---|---|---|---|
| `GET /api/admin/skill-sources/page` | 分页 | `Page<SkillSourceResponse>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt:28` |
| `GET /api/admin/skill-sources/active` | — | `List<SkillSourceResponse>` | `:43` |
| `GET /api/admin/skill-sources/{id}` | — | `SkillSourceResponse` | `:52` |
| `POST /api/admin/skill-sources` | `SkillSourceCreateRequest` | `{source, install}` | `:65` |
| `POST /api/admin/skill-sources/{id}/install` | `SkillSourceInstallRequest{names?}` | `SkillInstallResponse` | `:74` |
| `PUT /api/admin/skill-sources/{id}` | `SkillSourceUpdateRequest`（无 sourceType） | `Void` | `:86` |
| `DELETE /api/admin/skill-sources/{id}` | — | `Void` | `:100` |
| `PUT /api/admin/skill-sources/toggle/{id}` | `status` | `Void` | `:114` |
| `GET /api/admin/skill-sources/{id}/fetch` | — | `List<SyncSkillResponse>` | `:127` |
| `POST /api/admin/skill-sources/upload` | multipart `file` + form `name` | `SkillSourceInstallResponse` | `:137` |
| `GET /api/admin/skills/page` | 分页/筛选 | `Page<SkillResponse>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillController.kt:35` |
| `GET /api/admin/skills/{id}` | — | `SkillResponse`（含 resources JSON 串） | `:64` |
| `POST /api/admin/skills` / `PUT /update/{id}` | `SkillCreate/UpdateRequest` | `Void` | `:78,89` |
| `PUT /api/admin/skills/toggle/{id}` | `status` | `Void` | `:101` |
| `DELETE /api/admin/skills/{id}` | — | `Void` | `:114` |
| `POST /api/admin/skills/batch` | query `repositoryId` + body `List<String>` | `Void` | `:129` |

### CLI 与会话刷新
| 方法/路径 | 请求 | 响应 | 锚点 |
|---|---|---|---|
| `GET /api/admin/clis/page` | 分页/筛选 | `Page<CliResponse>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:37` |
| `GET /api/admin/clis/{id}` | — | `CliResponse`（detail 版含 payloadDigest/depsApt/runtimeEnv） | `:59` |
| `PUT /api/admin/clis/toggle/{id}` | `status` | `Void` | `:72` |
| `GET /api/admin/clis/{id}/related-agents` | — | `List<RelatedAgentInfo>` | `:84` |
| `GET /api/admin/clis/{id}/related-sessions` | — | `List<RelatedSessionInfo>` | `:95` |
| `POST /api/admin/agents/refresh-sessions` | `{sessionIds: string[]}` | `List<SessionRefreshResult>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:105` |

接口约定补充：需要区分"调用失败"与"结果为空"的请求，前端统一加 `skipErrorHandler`（例：MCP `list_tools` `harnax-webui/src/pages/mcp/detail.tsx:99-120`）。iOS 里对应"每个 fetch 都保留 `Result` 语义，不要用空数组吞掉错误"。

---

## iOS 适配注意点

### 1. 通用信息架构
- 上下文域做成一个 Tab（`ContextView`），内部 5 个入口用 `List` + `NavigationLink`；模型、MCP、技能三个域本身是"主从"结构，各自再用 `NavigationSplitView`。
- 卡片流：`LazyVGrid(columns: [GridItem(.adaptive(minimum: 320), spacing: 12)])`。宽度 < 400pt（iPhone）自然退化为单列 —— 对应 Web 的 `ResponsiveCardGrid` + `CardPagination` 语义，但 iOS 上应改为**连续滚动 + 分页加载**，不要把"每页 8 张"当成 UI 约束（`harnax-webui/src/pages/model/index.tsx:52` 只是 Web 分页尺寸）。
- EntityCard 的五段式布局（`harnax-webui/src/components/EntityCard/index.tsx:92-101`）在 iOS 拆成 `CardHeader`(图标+名称+类型 chip+`Toggle` 开关) / `Text(description)` / `HStack` 统计三格 / `Divider` / 底部 `HStack`(公开 chip、创建人、时间 + 尾部操作 `Menu`)。开关放右上角并加 `.buttonBorderShape`，避免与卡片点击手势冲突：卡片用 `.onTapGesture`，开关用 `Toggle` 且阻止手势穿透。
- 长 ID / 摘要（digest、sessionId）用小号 `Text(...).fontDesign(.monospaced)` + `.lineLimit(1)` + `.truncationMode(.middle)`，并支持长按复制（`Transferable`）。Web 是首尾截断（sessionId 24 字符，`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:193-217`）。

### 2. 文件树（技能详情）
- 数据是扁平 `Dictionary<String, String>`（path → content，`harnax-webui/src/pages/skill/detail.tsx:192-224`），树在前端构建（`:231-280`）。iOS 同样在本地建树，节点模型 `FileNode { name, path, isDir, children }`。
- 呈现：`List` + `OutlineGroup`（原生折叠树，零成本支持展开/折叠），选中项驱动右侧内容。
- 紧凑宽度（iPhone）下不要照抄"320px 左树 + maxHeight 200 顶部堆叠"（`harnax-webui/src/pages/skill/detail.tsx:355-523`），改为 push：树页 → 文件内容页，顶部 `Menu` 可切文件，避免二次返回层级丢失。
- 内容渲染：只读等宽 `ScrollView` + 按扩展名映射语言（映射表移植 `harnax-webui/src/pages/skill/detail.tsx:35-61`）；超大文本要 `LazyVStack` 分段渲染，避免一次性 `AttributedString` 卡主线程。
- Markdown 正文（`skillmd`）：iOS 17+ 用 `AttributedString(markdown:)` 只能处理内联语法，**表格/代码块/列表会失真**；建议引入轻量 Markdown 渲染（或自研分块渲染器），并保留"查看原文"切换。

### 3. Multipart 上传（ZIP 技能来源）
- 后端只认两个 part：`file`（二进制，`@RequestParam("file") MultipartFile`）与 `name`（普通表单字段）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt:137`；前端字段名同为 `file` / `name`：`harnax-webui/src/services/ant-design-pro/skillSource.ts:119-133`。**`name` 必须是 multipart/form-data 的普通字段，不能塞进 URL query**，否则后端 400。
- 控件语义照抄：只允许 `.zip`、单选、选完不自动上传（`harnax-webui/src/pages/skill/components/RepositoryForm.tsx:258-277`）。iOS：`.fileImporter(allowedContentTypes: [.zip])` + 显式"创建并安装"按钮。
- 沙盒访问：`fileImporter` 返回的安全资源作用域 URL 需 `startAccessingSecurityScopedResource()`，并把内容读成 `Data` 后再组 body（大 zip 用 `InputStream` 分块写 body，避免整包进内存）。
- 手工组 body 顺序：`--boundary` / `Content-Disposition: form-data; name="name"` / … / `name="file"; filename="x.zip"` + `Content-Type: application/zip` / 二进制 / `--boundary--`。**boundary 随机且不能出现在 body 里**。
- 上传即安装：`upload` 直接返回 `{source, install}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceInstallResponse.kt:12-17`），所以 UI 上"上传成功"之后还要走 `harnax-webui/src/utils/skillInstall.ts:32-129` 的八级判定，别只报"上传成功"。
- 进度：`URLSessionUploadTask` 的 `delegate` 进度回调；上传后统一走 `describeSkillInstall` 的等价函数，把 warning 与 error 用不同样式呈现（iOS 上 error 用 `Alert`，warning 用带橙色图标的 banner，因为文案较长且需 6 秒时长，`harnax-webui/src/utils/skillInstall.ts:131-132`）。

### 4. OAuth 回跳改造（最关键）
Web 那条链路的形状 iOS 全部保留，只有「谁去接回跳」这一处不同：redirect_uri 是**前端 http 页面**（由 `APP_FRONTEND_BASE_URL`/`APP_BASE_URL` 推导，`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:390-517` 的 `defaultCallbackUrl`），`state` 只在 URL query 里（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt:153`），落点是 `/mcp/oauth/callback`，读 query 后 `history.replace` 抹掉再 exchange（`harnax-webui/src/pages/mcp/oauth-callback.tsx:9`、`:35-44`），exchange 是带 JWT 的普通 JSON POST 且路径不带 mcpId（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt:90`、`:96-100`）。iOS 承接的是同一串东西，只是把它在自己进程里读掉：

1. **回跳地址只能写得出 http(s)，自定义 scheme 注册不进来**：保存客户端注册时 `callbackUrl` 先过 `validateHttpUrl` —— `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt:107-110` 发起，实现 `:433-448`，判定行 `:444` 只放 `http`/`https` 过并要求 host 非空，别的 scheme 一律 `BizException`。而授权请求里的 `redirect_uri` 就是这一列的当前值（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt:103`，进参数表 `:152`），换票时同一串再原样递一次（`:688`），AS 侧按逐字比对。两头都在后端手里，所以 `harnax://…` 既写不进库、也永远不会出现在回跳里 —— App 里不存在 URL scheme 回调这一层：`harnax-ios/Config/Info.plist` 全文 38 行只有本地化、方向与 ATS 三组键，没有 `CFBundleURLTypes`，也没有 associated domain 文件。**也不为一台设备改写这一行**：`mcp_oauth_client` 是按「租户 + issuer」存一行（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt:98` 的 `selectByTenantAndIssuer`），动它等于动同 issuer 下所有服务器与所有 Web 用户共用的注册。
2. **授权在 App 内 `WKWebView` 里跑**：详情页 OAuth 块并列两个入口（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailView.swift:287-303`）。`去授权`（`harnax-ios/Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:373`）把 URL 交给 Safari 再有界轮询（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpBrowserAuthorizer.swift:14-26`、`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailViewModel.swift:297-311`）；`在本应用授权`（同文件 `:390`）走 `startInAppAuthorization()`（`McpDetailViewModel.swift:491-499`）。应用内那一路是必需的而不是可选的：回调页在别的浏览器会话里换票，凭据落的是**那个会话的登录态**，这台设备什么也拿不到。
3. **要等的地址随授权请求一起给出，不需要先跑 discovery**：`McpOAuthAuthorization.redirectURL` 从服务端刚建好的那条 `authorizeUrl` 的 query 里把 `redirect_uri` 读出来（`harnax-ios/Sources/HarnaxCore/Contract/McpOAuth.swift:99-102`），它天然等于库里那行 `callbackUrl`。`url` 与 `redirect` 两个值一起装进 `InAppTarget`（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailViewModel.swift:41-44`）；读不到 redirect 就不开会话并给一句 `mcp.oauth.noRedirect`（`:493-496`，文案 `harnax-ios/Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:387`）——没有可比对的地址就没有任何一次跳转能被认出来。sheet 在不在场**就是**这个值是否非空，关掉它等价于取消（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailView.swift:35-42`）。
4. **认回跳的判据是 location 归一化后整串相等**：既不是前缀匹配也不比 query —— `McpOAuthCallback.matches` 把两边都化成 `scheme://host[:port]path`，丢 query 与 fragment、去掉结尾斜杠、四段大小写不敏感（`harnax-ios/Sources/HarnaxCore/Contract/McpOAuth.swift:290-295`，归一化在 `:319-333`）。非要做归一化，是因为 AS 会在这串后面追加 `code`/`state`（有时还有 `session_state`），而注册值可能带也可能不带尾斜杠；host 与 port 仍必须相等，所以只差大小写不可能把 code 交到别处。四个键由 `draft(from:)` 只从 query 取，`code` 与 `error` 都没有就返回 nil（`:302-316`）——一次无关跳转不该被送去换票，那会白 spend 掉服务端那个 state，而 exchange 的口是全可选 body、这判断它做不了。
5. **回跳页一次都不加载**：`WKWebView` 挂上协调器后直接 `load` 授权请求（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpAuthorizationWebSheet.swift:64-69`，macOS 同构 `:83-88`）。`classify` 里的 `caught` 保证一次会话只截一次（`:125-133`），命中即先 `decisionHandler(.cancel)` 再 `onReturn(draft)`（`:143-147`）——这条 cancel 是整个设计的承重墙：让页面加载就等于把这台设备刚收到的 code 交给回调页、在它自己登录的 console 会话下花掉。同一地址第二次导航回来时 `caught` 已置位，答案退回 `.allow`，免得把 spend 掉的 code 再递一次（`:120-124`）。
6. **换票与收尾**：draft 一到就走 `finishInAppAuthorization`（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailViewModel.swift:519-531`）→ `POST /oauth/exchange`（`harnax-ios/Sources/HarnaxAPI/McpEndpoint.swift:86-88`、`harnax-ios/Sources/HarnaxAPI/McpClient.swift:61-64`），先清 `inAppTarget`、再 `stopAwaitingAuthorization()`。判定只读 `authorized`：AS 拒绝也是 `code == 200` 的信封里一句 `authorized = false`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt:101-102`），不能从传输状态推断（`harnax-ios/Sources/HarnaxCore/Contract/McpOAuth.swift:266` 的 `isRefusal` 只是描述、不是分支）。之后一律 `loadOAuth()` 用 `GET /{id}/oauth/status` 收尾（`harnax-ios/Sources/HarnaxAPI/McpEndpoint.swift:90-92`），徽标只信这次读——应用内截获与浏览器 + 轮询两条路最后都落在 `oauth/status` 上。
7. **四个值都不落地**：`code`/`state` 只活在 hand-off 那一刻，不进 Keychain、不进 `UserDefaults`、不写文件，与回调页同规矩（`harnax-webui/src/pages/mcp/oauth-callback.tsx:19-28`）。`authorizeUrl` 只在界面显示、不持久化，显示它是为了 hand-off 没接住时能在另一台设备上续跑（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailViewModel.swift:56-59`）。会话的 cookie store 是 `WKWebView` 自带的那个，作用只到省掉同一个 AS 的二次登录（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpAuthorizationWebSheet.swift:18-20`）。
8. **state 的时间与一次性仍全部由后端兜**：TTL 5 分钟（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt:106`）、`consume` 先 `remove` 再判过期所以换票失败也不能拿同一个 state 重试（`:77-82`）、每人 5 个在途（`:118`，检查在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt:127-133`）、全局 500（`:109`，`put` 的拒绝在 `:63-66`）。iOS 不做倒计时、不做客户端配额预判，这几条超限都是后端 message 原样贴出。取消不发 exchange：关 sheet 只置 nil 加一句「已取消」，服务端那条 pending 自己过期（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailViewModel.swift:507-511`）。
9. **重启与陈旧都不在客户端兜**：state 只在内存、刻意不落库（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt:32-43`），用户停在同意页时后端恰好重启，exchange 就答 "unknown or expired"；iOS 不单独识别这一条也不重发，只把后端 message 显示出来（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailViewModel.swift:527-528`），用户重新点一次授权即可。`McpOAuthDiscovery.isCallbackStale` 比的是库里 `callbackUrl` 与本次部署的 `defaultCallbackUrl`（`harnax-ios/Sources/HarnaxCore/Contract/McpOAuth.swift:168-171`），不一致只警告不改写：AS 按逐字串拒绝，新地址要先在 AS 那边登记过，那是运维的动作（对齐 `harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:390-517`）。

### 5. 小屏重排清单
- 模型页两级结构：Web 是"卡片流 + 下方表格"同屏（`harnax-webui/src/pages/model/index.tsx:580-586`）。iOS 改为 **push**：厂商列表 → 该厂商模型列表（`ModelListTable` 转成 `List`）。隐藏 providerId 的注入方式（`harnax-webui/src/pages/model/components/ModelForm.tsx:149-151`）变成列表上下文传参，表单用 `.sheet`。
- 模型表"一次 100 条且不分页"（`harnax-webui/src/pages/model/components/ModelListTable.tsx:57-88`）在 iPhone 上首屏成本过高 → 改为 `pageSize: 20` + 触底加载；能力标签（`harnax-webui/src/pages/model/components/ModelListTable.tsx:137-176`）压成一行 `chip`，`thinkingMode === 2` 的红色"必需思考"保留在最前。
- 价格区间两个 Input（`harnax-webui/src/pages/model/index.tsx:559-576`）在 iOS 用 `.sheet` 里的筛选表单（数字键盘 + 校验 min ≤ max），不要塞导航栏。
- MCP 卡片的 endpoint（stdio→command，否则→url，`harnax-webui/src/pages/mcp/index.tsx:44`）常超长 → 单行中间截断 + 长按复制。
- 技能页左右分栏（`Col xs={24} md={6}` / `md={18}`，`harnax-webui/src/pages/skill/index.tsx:183,220`）→ iPhone 用 `NavigationSplitView` 一级来源列表 + 二级技能表；来源切换后必须清空技能表的选中态与滚动位置。
- CLI 详情抽屉（宽 560，`harnax-webui/src/pages/cli/components/CliDetailDrawer.tsx:73`）→ iPhone 用 `.sheet` 全屏 + 顶部关闭，字段按"基本信息 / 包与镜像指纹 / apt 依赖 / 运行时环境"分 `Section`，顺序仍照 `:86-207`。
- 表格列 → 行卡片：工具页 5 列（`harnax-webui/src/pages/tool/index.tsx:70-141`）、技能表 6 列（`harnax-webui/src/pages/skill/components/SkillList.tsx:27-138`）、CLI 8 列（`harnax-webui/src/pages/cli/index.tsx:174-276`）在 iOS 都折叠为"主标题 + 副标题 + 右侧开关/标签"三件套，次要字段进 `.swipeActions` 的"详情"或 push 详情页。
- 悬浮态全部要换：`EnvParamsPopover` 是 hover 触发（`harnax-webui/src/components/EnvParamsPopover/index.tsx:44-91`），iOS 无 hover → 改 tap 展开（`DisclosureGroup`）或 `.popover`，触发器仍是紫色数量 chip（`:94-103`）。`ProviderCard` 统计格的 `popoverContent`（`harnax-webui/src/components/EntityCard/index.tsx:20-30`）同理。
- 阻断类确认弹窗：MCP 删除带"受影响 Agent 清单"（`harnax-webui/src/pages/mcp/index.tsx:314-358`）、CLI 停用带前 5 个 Agent 名（`harnax-webui/src/pages/cli/index.tsx:114-172`）、技能禁用被 `boundAgentCount/boundTeamCount` 拦住时给三种 tooltip（`harnax-webui/src/pages/skill/components/SkillList.tsx:94-134`）。iOS 里 tooltip 换成 `Alert` 正文或长按 help（`.help()` 在 iOS 17 有 tooltip 效果但不保证可见），**不要静默禁用开关而不给原因**。

### 6. 其它共性
- 0/1 Int 约定统一封装（`readOnly`/`needConfirm`/`isRequired`/`isPublic`/`status`/`support*`），见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentToolResponse.kt:33-39`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelCreateRequest.kt:33-57`。
- update 请求"省略即不变"（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerUpdateRequest.kt:10-58`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelUpdateRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceUpdateRequest.kt:8-36`）→ 必须用 `encodeIfPresent` + 只编码变更字段；空串与 nil 语义不同（apiKey / clientSecret 空串 = 清除，掩码/省略 = 保留，`harnax-webui/src/pages/model/components/ProviderForm.tsx:42-60`、`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:176-217`）。
- 敏感值永远以掩码回显（apiKey 前 2 + `****` + 后 4，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderResponse.kt:60-68`；header/envParam secret，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerResponse.kt:55-56`）→ iOS 表单初始值不要回填掩码串，直接留空并提示"留空不修改"。
- 连通性测试只有布尔（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelProviderController.kt:122`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:136`）+ 前端 15s 超时竞赛（`harnax-webui/src/pages/mcp/index.tsx:381-438`）→ 用 `Task` + `TimeoutError` 区分三态：通过 / 服务端失败(message) / 本地超时。
- 权限来自登录态（Web 从 localStorage `currentUser`，`harnax-webui/src/utils/permissionUtil.ts:80-97`）→ iOS 从 Keychain 会话解析，规则照抄 `hasOperationPermission`（`:111-120`）与 `isPublicSwitchDisabled`（`:57-75`）；CLI 无 creator/isPublic 字段，因此不能照模型页做门控（见"未确认"）。
- 路由参数：`/context/mcp/detail/:id`、`/context/skill/detail/:id`（`harnax-webui/config/routes.ts:85,96`）→ iOS 用 `.navigationDestination(for: MCP.ID.self)` / `Skill.ID.self`。OAuth 回调路由 `/mcp/oauth/callback`（`:161-163`）在 iOS 不存在，也不注册任何 `CFBundleURLTypes`：租户共享的 `redirect_uri` 要过 `validateHttpUrl`，只认 http(s)（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt:433-448`），自定义 scheme 既无法注册也不该由一台设备改写（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpAuthorizationWebSheet.swift:8-17`）。承接有两条：应用内在 `WKWebView` 里截获回跳、由本机换 code（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailViewModel.swift:491-499`），或交给系统浏览器、仍由控制台回调页收 code 并落令牌，iOS 轮询授权状态收尾（`harnax-ios/Sources/HarnaxFeatures/Mcp/McpBrowserAuthorizer.swift:10-13`、`harnax-ios/Sources/HarnaxFeatures/Mcp/McpDetailViewModel.swift:305`）。两条都不把 code 与一次性 state 存下来。

---

## 未确认

1. `GET /api/admin/model-providers/{id}/delete` 在厂商仍有模型时的后端行为（是否级联、是否报 message）未读实现层，仅确认端点存在：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelProviderController.kt:113`。
2. `connectivity-test` 对 stdio 的具体拒绝路径：只确认 `McpStdioPolicy.refusalReason()` 文案存在（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt:26-31`）与 `list_tools` 异常时把 message 塞进 `ResultVo.error`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:173-175`），但 service 层 `connectivityTest` / `listTools` 的 stdio 分支实现未逐行读。
3. `McpOAuthDiscoveryResponse` / `McpOAuthStatusResponse` / `McpOAuthExchangeResponse` 的**逐字段清单**取自 DTO 行范围（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthDiscoveryResponse.kt:11-50` 等），未逐字段展开类型（例如 scopes 是 `List<String>` 还是逗号串、过期字段名）。iOS 建模前建议再逐行核对这三个 DTO。
4. **已核实**：CLI 走的是「全体登录用户可见可开关」，不是管理员限定。`harnax-webui/config/routes.ts:100`-`:105` 的 `/context/cli` 没有 `access` 字段（全仓只有三条路由带 `canAccessUserManagement`：`/system/user`、`/system/tenant`、`/system/api-key`），`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:37`-`:95` 的读口与 `PUT /toggle/{id}` 也都没有 `@PreAuthorize`。又因 `CliResponse`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt:21`-`:58`）不含 `isPublic` / `creator`，`hasOperationPermission` 两条规则在这页套不上——iOS 的 CLI 页对任何登录用户都放行到接口层，权限完全由后端默认策略兜。
5. `harnax-webui/src/services/ant-design-pro/{model,mcp,skill,cli}.ts` 中各封装函数返回 `res.data` 的具体解构位置未逐个贴行号（以 controller 的返回类型为准）；iOS 只需按 `ResultVo` 信封解 `data`。
6. 技能页「批量安装到 Agent」已核实为**不存在**：`POST /api/admin/skills/batch` 带 `@Deprecated`，注释说明它保留给 CLI 调用、请改用 `POST /api/admin/skill-sources/{id}/install`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillController.kt:125`-`:135`），且全 webui 无任何调用点（`skills/batch` 与 `batchInstall` 零命中）。iOS 只接 `install` 那一路，不要为废弃口做适配层。
7. `EntityCard` 的底部/统计区精确 CSS 层级与响应式断点细节未逐行读完（只读了 `:1-120` 与注释块 `:92-101`）；iOS 视觉稿需自行决定间距/圆角，无强约束。
8. **已核实**：`hasOperationPermission`（`harnax-webui/src/utils/permissionUtil.ts:111`-`:123`，文件共 123 行）**没有**「公开实体额外放行」分支，规则只有两条——`isAdmin` 放行一切，否则 `currentUser === creator`。因此「公开资源允许非管理员操作」在 Web 侧并不成立，iOS 照两条实现即可，不要给 `isPublic` 加编辑放行。
9. 工具页除 `/builtin` 之外是否存在按 Agent 维度的可用工具视图（`/available`，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:54`）—— 上下文域页面不用，Agent 表单用；iOS 若做 Agent 域再确认。
10. `SkillInstallResponse.flagged[].reasons` 的元素类型（`List<String>` 还是单串）与 `stale` 的语义细节需按 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillInstallResponse.kt:12-68` 逐行核对；本文只按 `harnax-webui/src/pages/skill/components/RepositoryList.tsx:73-81,153-183` 的分区渲染结论描述。
11. **已核实**：Flyway 目录只剩 `harnax-admin/src/main/resources/db/migration/README.md` 与 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`，没有更高版本脚本，增量历史全部折进了基线。需要留个记号的是「V1~V50 机械合并」这一口径只有该文件头注释一个出处（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:1`-`:18`），文件体内不含任何 `V35__` / `V37__` 之类的版本标记，因此 V35 删 `cli_plugin`、V37 退役种子技能这两条结论是从注释读来的，不是从 DDL 逐条对上的。
