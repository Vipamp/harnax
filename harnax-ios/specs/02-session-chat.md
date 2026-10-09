# iOS 实现规格 02：会话与 ChatWindow（全量对齐 harnax-webui）

取证范围：`harnax-webui/src/pages/session/**`（含 `harnax-webui/src/pages/session/components/ChatWindow.tsx` 3792 行，已完整读完）、`harnax-webui/src/services/ant-design-pro/{session,chat,workspace,team,agent}.ts`、`harnax-webui/src/requestErrorConfig.ts`、以及 `harnax-protocol` / `harnax-session-router` / `harnax-admin` / `harnax-agent` 相应 Kotlin 源。所有行号均为实际读到的行。

约定：下文「锚点」路径相对仓库根 `/Users/heqingsong/code/my_project/harnax`。iOS 侧要求「行为等价」而非「像素等价」；凡 webui 存在但后端不支持（或反之）的能力，均在条目上标注 **webui 死 UI** / **后端未消费**，iOS 应照 webui 行为实现以保持视觉一致，同时在「未确认」中记录取舍。

---

## 会话列表与外壳

### 页面结构

- 桌面/平板双栏：左侧会话列表 + 右侧 `ChatWindow`；窄屏（移动端断点）单栏，列表页与聊天页互切，聊天页左上角有返回按钮。锚点 `harnax-webui/src/pages/session/index.tsx:106-109`（返回按钮渲染）、`harnax-webui/src/pages/session/index.tsx:255-263`（移动端单栏切换）、`harnax-webui/src/pages/session/index.tsx:152`、`harnax-webui/src/pages/session/index.tsx:229`（单/双栏容器分支）。
- iOS 映射：`NavigationSplitView`（regular 宽度双栏 / compact 宽度单栏 push），返回即 `dismiss`。
- iOS 独有的一行入口：会话列表顶部挂「技能草稿」行，通到本租户的草稿审核队列——Web 侧这一页在 `/context/skill-drafts`（`harnax-webui/config/routes.ts:107`-`:111`），不在会话列表里，所以这一行不对应 webui 的任何元素，只借会话列表的位置露出。行上的计数是租户级待审总数（这一行给的是审核链的导航，队列的会话谓词由本 spec「本会话自写技能」那一节的那次读用），读失败只隐藏数字不撤行；行的挂载位置与两级 push 形状见 `harnax-ios/specs/07-skill-draft-review.md` §5.1。

### 列表数据加载

- 进入页面即 `getSessionPage({ pageNum: 1, pageSize: 100 })`，**一次拉 100 条、不分页、不做无限滚动**。锚点 `harnax-webui/src/pages/session/index.tsx:39`。
- 无任何前端筛选逻辑：不做本地 keyword 过滤、不做本地排序。排序完全由后端 SQL 决定 `ORDER BY create_time DESC`。锚点 `harnax-entity/src/main/resources/mapper/SessionMapper.xml:104-118`。
- 后端可见性 SQL 语义：`active=1 AND (is_public=1 OR creator=#{currentUsername})` + `tenant_id` 过滤；即「公开会话everyone可见 + 自己的私有会话」。**这就是「是否公开」唯一的真实语义**。锚点 `harnax-entity/src/main/resources/mapper/SessionMapper.xml:104-118`。
- 列表接口 param 里存在 `keyword` / `status`，但 **webui 未传、UI 上不存在搜索框/分组/排序控件**（这是重要结论，避免 iOS 自作主张加搜索框）：`harnax-webui/src/services/ant-design-pro/session.ts`（`getSessionPage` params 定义）与 `harnax-webui/src/pages/session/index.tsx:39` 只传 pageNum/pageSize 互为佐证。

### 选中与深链

- 无 `?id=` 参数时默认选中列表第一条。锚点 `harnax-webui/src/pages/session/index.tsx:49-52`。
- 支持 URL 深链 `?id=` 定位会话（数字主键 `id`）。锚点 `harnax-webui/src/pages/session/index.tsx:62-72`。iOS 映射：`URL` / universal link query `id`。
- 传给聊天窗的是**字符串业务主键 `sessionId`**，不是数字 `id`。锚点 `harnax-webui/src/pages/session/index.tsx:309`（`<ChatWindow sessionId={selectedSession.sessionId} />`）。

### 列表行渲染

- 行内容：`title` 主标题、副标题（`sessionDescription` 或时间）、当 `session.teamId` 非空时追加蓝色 `Team` 标签。锚点 `harnax-webui/src/pages/session/index.tsx:203-207`。
- iOS：`List` row + `Text("Team").foregroundStyle(.blue)` 徽标。

### 行操作

- 删除：`deleteSession(id)`，用**数字主键 id**。成功后若删的是当前选中项，清空 selection（回到空状态）。失败时把 `res.message` 作为错误提示展示（不是靠 HTTP 状态码）。锚点 `harnax-webui/src/pages/session/index.tsx:80-99`。接口 `DELETE /api/admin/sessions/{id}`，锚点 `harnax-webui/src/services/ant-design-pro/session.ts`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:163-172`。
- 新建：打开 `SettingsModal`。锚点 `harnax-webui/src/pages/session/index.tsx` 的新建按钮区域与 `harnax-webui/src/pages/session/components/SettingsModal.tsx`。
- Workspace 按钮：先 `getWorkspaceStatus(selectedSession.sessionId)`，**只有 `res.data?.active == true` 才打开抽屉**，否则 toast warning `Sandbox is not running`，不打开抽屉。锚点 `harnax-webui/src/pages/session/index.tsx:280-295`。
- Artifacts 按钮：**仅当 `session.teamId` 存在时出现**。锚点 `harnax-webui/src/pages/session/index.tsx:296-305`。

### 新建会话表单（SettingsModal）

字段与校验（iOS 需 1:1 复刻）：

| 字段 | 控件 | 校验 | 锚点 |
|---|---|---|---|
| `title` | Input | `required`；`maxLength` 由后端 `@Size(max=100)` 决定（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionCreateRequest.kt:13-14`）；**异步查重 validator，validateTrigger 仅 onBlur** | `harnax-webui/src/pages/session/components/SettingsModal.tsx:142-165` |
| `sessionDescription` | TextArea | 无 required；`maxLength=500` | `harnax-webui/src/pages/session/components/SettingsModal.tsx:167-176` |
| `executor` | Select（分组） | `required`；value 形如 `agent:<id>` / `team:<id>`；label `${name} - ${description}` | `harnax-webui/src/pages/session/components/SettingsModal.tsx:178-213` |
| `isPublic` | Switch | **webui 死 UI：不进入提交体** | `harnax-webui/src/pages/session/components/SettingsModal.tsx:215-225` |

- 查重实现：validator 内调 `checkSessionTitle`（`GET /api/admin/sessions/check-title?title=`，返回 `ResultVo<Boolean>`）。`validateSessionTitle` 定义在 `harnax-webui/src/pages/session/components/SettingsModal.tsx:33-51`。同文件 `:54` 有一个 `debouncedValidateTitle`，但 **Form 上未挂该防抖函数**（挂的是 `validateSessionTitle`），iOS 可直接实现防抖（对齐意图）或不做（对齐现状），建议做 300ms 防抖。
- 执行者下拉数据源：
  - Agent：`getAgentPage({pageNum:1,pageSize:100,status:1})` 后再本地 `filter(status===1)` 二次过滤。锚点 `harnax-webui/src/pages/session/components/SettingsModal.tsx:69-71`。
  - Team：`getTeamPage({current:1,size:100,status:1})`（service 内部把 current/size 转成 pageNum/pageSize，锚点 `harnax-webui/src/services/ant-design-pro/team.ts:6-25`）。
  - 合并进同一个 Select 的两个分组。
- **agent 与 team 互斥逻辑**：提交前把 `executor` 按 `':'` 拆成 `kind`/`rawId`，`isTeam = kind==='team'`；team 会话提交 `agentId: undefined, teamId: id`，否则 `agentId: id, teamId: undefined`。锚点 `harnax-webui/src/pages/session/components/SettingsModal.tsx:92-100`。
- 提交体 `createData` **只有 4 个可能键**：`title`、`sessionDescription`、`agentId`、`teamId`（**不含 isPublic**）。锚点 `harnax-webui/src/pages/session/components/SettingsModal.tsx:95-101`；后端 DTO 同样无 isPublic 字段，锚点 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionCreateRequest.kt:17-30`。
- 创建接口 `POST /api/admin/sessions` 返回 `ResultVo<Void>`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:80-89`），所以前端 `onSuccess(res.data || createData)` 中的 `res.data` 恒为空、实际回退到本地 `createData`。锚点 `harnax-webui/src/pages/session/components/SettingsModal.tsx:107`。**iOS 不能依赖响应体拿新会话**，必须在创建成功后重新拉列表并按 title 匹配或直接关闭弹窗由用户点选。

### 会话详情弹窗（DetailModal）

入口：列表行/头部的详情按钮；实现为 `Collapse` 多面板弹窗，`defaultActiveKey` 全展开。锚点 `harnax-webui/src/pages/session/components/DetailModal.tsx:187`。

数据加载分支（**根据 session 上的 agentId/teamId 二次拉取，会话列表本身不带完整配置**）：

- `session.teamId` 存在 → `getTeamById(teamId)`，填 `skillList` + `memberList`，并**显式清空 tool/mcp/cli**（团队会话不展示这三栏）。锚点 `harnax-webui/src/pages/session/components/DetailModal.tsx:114-128`。
- 否则 `agentId` 存在 → `getAgentById(agentId)`，填 `toolList` / `mcpList` / `cliList` / `skillList`。锚点 `harnax-webui/src/pages/session/components/DetailModal.tsx:130-144`。
- 两者都没有 → 直接用 `session.skillList`（列表接口已带）。锚点 `harnax-webui/src/pages/session/components/DetailModal.tsx:146-147`。

面板（key / 可见条件 / 内容）：

| 面板 | 可见条件 | 展示 | 锚点 |
|---|---|---|---|
| basic | 恒显示 | `sessionId`（可复制）、`title`、`sessionDescription`、`isPublic` Tag、owner、creator、createTime | `harnax-webui/src/pages/session/components/DetailModal.tsx:192-221`；isPublic 判定 `harnax-webui/src/pages/session/components/DetailModal.tsx:160-164` 与 `:208-210` |
| agent | 恒显示 | name、description、modelName、modelPrice 显示为 `¥{n}/M`、systemPrompt（4 行截断可展开） | `harnax-webui/src/pages/session/components/DetailModal.tsx:224-279` |
| tools | 仅非 team | 工具名按 locale 选 `toolDisplayNameZh`/`toolDisplayName`/`toolName`（`harnax-webui/src/pages/session/components/DetailModal.tsx:313-315`）；`needConfirm` 显示橙色 Tag（`:319-321`）；含 `EnvBindingsTable`（`harnax-webui/src/pages/session/components/DetailModal.tsx:37-90`，默认折叠，列 envKey / envVarName 或 Custom Tag / envValue=customValue） | `harnax-webui/src/pages/session/components/DetailModal.tsx:282-337` |
| mcp | 仅非 team | MCP 列表 + EnvBindingsTable | `harnax-webui/src/pages/session/components/DetailModal.tsx:340-389` |
| skill | 恒显示 | 技能列表；`skillAvailable===false` 红色「失效」Tag | `harnax-webui/src/pages/session/components/DetailModal.tsx:392-446`（`:431-433`） |
| cli | 仅非 team | CLI 列表 + EnvBindingsTable | `harnax-webui/src/pages/session/components/DetailModal.tsx:449-502` |
| members | 仅 team | 成员列表；`agentAvailable===false` 红色失效 Tag | `harnax-webui/src/pages/session/components/DetailModal.tsx:505-561`（`:540-542`） |

- 弹窗底部展示 `updateTime`。锚点 `harnax-webui/src/pages/session/components/DetailModal.tsx:567`。
- 注意：`isPublic` 在详情里**只读展示**，不可改；值来自 `SessionResponse.isPublic: Int?`（0/1）。锚点 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionResponse.kt:59-60`。

### Workspace 抽屉

- 根路径固定 `/workspace`，所有 path 以此为基准。锚点 `harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:39`、`:112`。
- 打开时 `getWorkspaceStatus(sessionId)`；非 active 显示 `Empty`（空态），不报错。锚点 `harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:53-57`（loading）、`:76-86`（status）、`:186-190`（空态）。
- 文件列表：`listWorkspaceFiles(sessionId, path)`（默认 `/workspace`），排序规则 **目录优先，再按 `name.localeCompare`**。锚点 `harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:53-57`；service `harnax-webui/src/services/ant-design-pro/workspace.ts:38-51`。
- 行图标/大小来自 `WorkspaceFile{type:'file'|'directory'|'symlink'|'unknown', name, size, modified}`。锚点 `harnax-webui/src/services/ant-design-pro/typings.d.ts:344-349`。
- 点击文件 → `readWorkspaceFile(sessionId, path)`，取 `res.data.content` 展示；失败时把 `Error: ...` 文本写进预览区（**不弹错误框**）。锚点 `harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:88-107`；类型 `WorkspaceFileContent{content,truncated,size}` 锚点 `harnax-webui/src/services/ant-design-pro/typings.d.ts:351-355`。
- 面包屑 + 上一级导航：锚点 `harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:118-133`、`:203-219`。
- 上传：`uploadWorkspaceFile(sessionId, file, currentPath)`，FormData 字段名 `file` 与 `path`；`beforeUpload` 返回 `false` 由前端自行发起（**不依赖 antd 的自动上传**）。锚点 `harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:153-174`；service `harnax-webui/src/services/ant-design-pro/workspace.ts:115-138`；后端 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:261-273`（`@RequestParam path` + `file`）。
- 下载：行内下载按钮 → `downloadWorkspaceFile(sessionId, filePath)` → 生成 blob → `<a download>` 触发。锚点 `harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:279-304`；service `harnax-webui/src/services/ant-design-pro/workspace.ts:143-165`（**裸 fetch，只带 `X-Api-Key`，不带 Bearer/Tenant**）；后端 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:291-315`（`safeFileName` 过滤控制字符/引号/目录部分并截断 128 字符 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:281-286`；超 50MB（`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:41` `MAX_DOWNLOAD_SIZE`）返回 413；不存在 404；正常时带 `Content-Disposition`）。
- iOS 已落地的形状（`Sources/HarnaxFeatures/Chat/WorkspaceSheet.swift`、`WorkspaceViewModel.swift`）：
  - 抽屉 → `sheet`；下载 → `URLSession` 取 `Data` 后交系统分享面板（`HXFileShare`），不落 `<a download>`。
  - 上传 → `.fileImporter` 选一个文档，落到**屏上当前那一层目录**（`WorkspaceSheet.swift:53-59`、`:285-302`）；上传中禁用按钮，失败在行上方一条红 banner 说原因，列表不动。
  - 入口在会话页右上角，**只有状态读回答 running 才画**（`ChatView.swift:81-89`）。这是有意分叉：webui 的按钮常显、点下去才查，未运行弹 warning、查失败弹 error（`harnax-webui/src/pages/session/index.tsx:280-292`），而沙箱管理器没起时这五条路由全是 404（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/SandboxWorkspaceController.kt:398-416`），一个只会失败的入口会被当成坏按钮。iOS 侧读失败同样按未运行处理，且不吭声。
  - 状态读三个时机：进屏/换会话（`ChatView.swift` 的 `.task(id: conversation)`）、一轮的最后一句之后（`ChatViewModel.swift` 的 `readerClosed` / `readerFailed`，容器是被运行里的第一次工具调用建出来的，只在进屏读一次的话入口永远出不来）、切走会话时归零。答案按会话 id 判身份，迟到的旧会话答案不许点亮这一屏（`ChatViewModel.refreshSandboxStatus`）。
  - 面板与开着的抽屉都随会话换：`bind` 撤面板、收抽屉、按新 id 重建（`ChatViewModel.retireWorkspacePanel`）。
  - 单测：`Tests/HarnaxFeaturesTests/ChatViewModelTests.swift` 的「the sandbox workspace entry」5 条 + `WorkspaceViewModelTests.swift`。

### 团队产物抽屉（TeamArtifactsDrawer）

- 列表：`listTeamArtifacts(sessionId)` → `GET /api/admin/team-artifacts?sessionId=`。锚点 `harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx:30-51`；service `harnax-webui/src/services/ant-design-pro/team.ts:79-85`。
- 列字段与渲染：
  - `fileName` 主行 + 副行 `${mimeType} · ${formatSize(sizeBytes)}`（`harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx:68-82`）。
  - `fileId` 渲染为可点击复制的 Tag，显示 `fileId.slice(0,8)…`（`harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx:83-104`）。
  - `createTime`（`harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx:105-114`）。
  - 下载图标按钮（`harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx:116-122`）。
  - `rowKey="fileId"`，表格宽度 620（`harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx` 表体定义）。
- 类型 `TeamArtifactItem{fileId,fileName,mimeType,sizeBytes,memberAgentId,createTime}`。锚点 `harnax-webui/src/services/ant-design-pro/typings.d.ts:246-253`。响应 DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamArtifactResponse.kt:14-32`，**故意不返回 objectKey**（同文件 `:9-12` 文档说明）。
- 下载：`downloadTeamArtifact(item.fileId, sessionId)` → `GET /api/admin/team-artifacts/{encodeURIComponent(fileId)}?sessionId=...`，**只带 `Authorization: Bearer`，不带 `X-Tenant-ID`**（裸 fetch）。锚点 `harnax-webui/src/services/ant-design-pro/team.ts:91-113`；抽屉调用 `harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx:53-66`。
- 后端鉴权/归属校验（iOS 需理解错误语义）：控制器 `@ConditionalOnProperty(minio.enabled=true)` 才注册（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt:42`）；`fileId` 非 UUID → 400（`:78`，`UUID_PATTERN` `:54`）；`sessionId` 必须匹配 `^[a-zA-Z0-9_-]{1,128}$`（`:56`）；非属主 → 403（`:79`）；产物不属于该会话 → 404（`:81-86`）。属主判定 = 会话 `status=1`、`teamId` 非空、`creator === 当前用户名`（`:108-127`）。列表侧同样做 tenant 过滤（`:59-70`，过滤在 `:68`）。

---

## 分段渲染规格

### 数据模型（iOS 需等价建模）

- `MessageSegment.type`：`'text' | 'thinking' | 'tool_call' | 'tool_result' | 'tool_confirm' | 'plan_card'`，附 `content`、`toolName`、`toolId`、`confirmStatus('pending'|'confirmed'|'rejected')`、`interrupted`、`pendingCallTools[]`、`planData`、`toolResult`、`toolResultExpanded`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:62-73`。
- `ChatMessage{id, role('user'|'assistant'), segments[], timestamp, imageUrls?, teamSource?, teamRun?, parentToolId?}`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:75-89`。
- `PendingCallTool{toolId, toolName, arguments, isDangerous}`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:54-59`。
- `MemberRunState{source, segments[], toolIndex, status, startedAt, endedAt?, task?, parentToolId?}`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:95-104`。
- 团队事件源 `TeamEventSource{teamId,teamName,memberAgentId,memberAgentName,childRunId,childSessionId}`。锚点 `harnax-webui/src/pages/session/components/teamRun.ts:11-18`；后端定义 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:53-60`。

### 各块的取数来源

| 块 | 实时流来源事件 | 历史来源字段 | 锚点 |
|---|---|---|---|
| `text` | `TextEvent.message`（eventType=`text`） | `AssistantMessageLog.text` | 流 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1405-1442`；历史 `harnax-webui/src/pages/session/components/ChatWindow.tsx:900-902`；协议 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:83-99` |
| `thinking` | `ThinkingEvent.message` | `AssistantMessageLog.thinking` | 流 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1444-1485`；历史 `harnax-webui/src/pages/session/components/ChatWindow.tsx:838-849`；协议 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:83-99` |
| `tool_call` | `CallToolEvent{toolId,toolName,arguments}` | `AssistantMessageLog.toolUseLog[]{name,input}` | 流 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1487-1542`；历史 `harnax-webui/src/pages/session/components/ChatWindow.tsx:851-897`；协议 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:116-124`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:52-55` |
| `tool_result` | `ToolResultEvent{toolId,toolName,message,success}`（**写入 tool_call 段的 `toolResult`，不生成独立段**） | `ToolResultMessageLog{name,result}`（**被前一条 ASSISTANT 吸收**） | 流 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1596-1617`；历史 `harnax-webui/src/pages/session/components/ChatWindow.tsx:911-929`（`:925-926` 注释明确「不再创建独立 tool_result segment」）；协议 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:126-135`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:57-64` |
| `tool_confirm` | `ToolConfirmEvent{pendingCallTools[]}` | 历史无（确认过程不落库为独立块） | 流 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1657-1738`；协议 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:101-114` |
| `plan_card` | 由计划接口灌入（非事件流直接产生） | 计划接口 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:110-132`、`:2965-3038`；`loadCurrentPlan` `harnax-webui/src/pages/session/components/ChatWindow.tsx:2479-2600` |

### text / thinking：增量拼接规则

- 连续增量拼接：同类型事件持续到达时把 `message` 追加到同一 `segments` 项；`currentEventType` 切换（text↔thinking、或遇到非计划工具）时新开一段。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1405-1442`（text）、`:1444-1485`（thinking）、`:1513-1518`（非计划工具重置连续性）。
- **`isLast === true` 的语义是「本轮该通道收尾、其携带的内容要丢弃」**：实现是清空累计变量与活跃段索引，并且不加内容。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1422-1428`（text）、`:1444-1485` 内 thinking 同构。iOS 必须复刻：不能把 isLast 帧当作「最后一批内容」。
- 空内容帧 skip，不产生空段。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1432`。
- 收尾时过滤空 text/thinking 段。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1635-1655`（EndEvent 分支）。

### text 渲染（Markdown）

- `ReactMarkdown` + `remarkGfm`，即 **GFM 表格/删除线/任务列表支持必须实现**；`code` 渲染交由自定义 `CodeBlock`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2746-2858`（`renderSegment` 的 default 分支走 ReactMarkdown）。
- `CodeBlock`：用 `/language-(\w+)/` 从 className 取语言；**无语言匹配时渲染纯 `<code>`，不高亮、不显示复制按钮**。有语言时 `Prism.highlight(code, Prism.languages[lang] ?? plaintext, lang)`，主题 oneLight，字号 13。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:167-208`（语言解析 `:198-205` 主题与字号）。
- 复制按钮：`navigator.clipboard.writeText(code)`，成功后图标变化 1800ms 回退。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:180-184`。iOS 的代码块不摆图标：长按呼出「复制」菜单（`Sources/HarnaxFeatures/Chat/ChatTranscriptRows.swift:430`，菜单本体 `:704` 的 `HXCopyMenu`），菜单与 `textSelection` 的自由取词并存。
- iOS 落点：主管气泡与成员气泡的 text 段交给 `HXMarkdownText`（`Sources/HarnaxFeatures/Chat/ChatTranscriptRows.swift:261-267`），两者共用 `ChatSegmentRow` 所以一处接线两处生效。渲染器本体 `Sources/HarnaxKit/Components/HXMarkdownText.swift`，块结构 `Sources/HarnaxKit/Components/HXMarkdownParser.swift`（iOS 17 的 `AttributedString(markdown:)` 只吃行内语法，把技能正文交给它，表格/围栏/列表会塌成一行平文）。覆盖：标题 1–6、有序/无序/GFM 任务列表、```` ``` ````与 `~~~` 两种围栏、表格（含列对齐）、引用、分割线、行内粗体/斜体/删除线/行内 code、`[文案](地址)` 链接（只放行 `http`/`https`/`mailto`，`HXMarkdownText.swift` 的 `openableSchemes`）。
- 用户气泡与 thinking 保持纯文本：webui 的 user 分支就是 `<span>{msg.segments[0]?.content}</span>`（`harnax-webui/src/pages/session/components/ChatWindow.tsx:3420`），只有 assistant 与成员分支走 `renderSegment`（`:3426`）。iOS 同形（`Sources/HarnaxFeatures/Chat/ChatTranscriptRows.swift:43`）。
- 围栏底色跟宿主走：落在页面底上的文档（技能正文）围栏取 `surface`，落在 `surface` 气泡里的围栏取 `surfaceAlt`，判据是纯函数 `HXMarkdownRender.codeBackground(on:)`（`Sources/HarnaxKit/Components/HXMarkdownText.swift:32-40`），由 `HXMarkdownText(_:on:)`（`:204`）传给渲染树，列表项与引用里的围栏继承同一档（用例 `Tests/HarnaxKitTests/HXMarkdownRenderTests.swift:128-144`）。两档底上的 `textPrimary` 都在对比度表里（`Sources/HarnaxKit/Theme/Palette.swift:77-78`）。
- 流式半篇不裂：未闭合围栏延续到文末，代码仍是代码（`Sources/HarnaxKit/Components/HXMarkdownParser.swift:270-271`；用例 `Tests/HarnaxKitTests/HXMarkdownParserTests.swift:161`）。视图每帧重建时重解析整篇，次数上限是流合并的帧窗（一帧一次）。
- 与 webui 有意不同的两条：①正文不可选中——渲染器自身的规则是「开了 `textSelection` 的 `Text` 不保证把链接那一行的点击交回去，而点不开的链接是更坏的那一半」（`Sources/HarnaxKit/Components/HXMarkdownText.swift:193-195`），所以气泡里链接优先，围栏仍可选，整条原文由长按复制菜单取走，且复制的是 Markdown 源码（见「iOS 实施要点」第 11 条）；②不做语法高亮，围栏是等宽原色加语言 chip（webui 用 Prism oneLight 字号 13，`harnax-webui/src/pages/session/components/ChatWindow.tsx:167-208`）。

### thinking 渲染

- 独立折叠块：头部 `BulbOutlined` + 文案「思考过程」+ 折叠箭头；**默认展开**（`useState(true)`）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:211-228`。
- 受 `showThinking` 门控（右键菜单切换，纯前端）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2746-2858`（`thinking` 分支受门控）、`:3506-3572`（右键 `contextMenu` 切 `showThinking`）。

### tool_call 渲染（合并卡 MergedToolCard）

- 一个 tool_call 段即一张卡，内部按状态展示参数与结果；`tool_result` 不单独渲染（`renderSegment` 对 `tool_result` **永远返回 null**）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2771-2773`；旧版 `ToolCallCard` 保留兼容不再走主路径（`harnax-webui/src/pages/session/components/ChatWindow.tsx:385-413`）。
- 展开规则（重要，iOS 必须一致）：`manual` 初值 `null`，`expanded = manual ?? !!busy`。即**默认折叠；运行中（busy）自动展开；一旦用户手动点过，就永久以用户选择为准**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:305-307`、头部点击 `:333`。
- 状态与颜色（六态）：`pending` 警告色 / `confirmed` 绿 / `rejected` 红 / `calling` 主色 + 旋转时钟图标 / `completed` 绿 / `interrupted` 警告色。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:310-317`。
- 状态判定顺序（务必按此短路顺序）：`confirmStatus==='pending'` > `'rejected'` > `'confirmed' 且无结果` > `'interrupted' 且无结果` > `'有 toolResult' → completed` > 默认 `calling`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:319-324`。
- 正文顺序：Arguments 表 → 嵌套成员运行区块 → Result `<pre class="mergedToolCode">`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:353`、`:361-369`、`:371-377`。
- Arguments 表（`ArgumentsTable`）规则：
  - 字符串值会先尝试 `JSON.parse`，失败退回 `<pre>` 原文。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:231-287`（parse 与回退）。
  - 空对象显示 `—`。
  - boolean → 绿色/默认 Tag；number → 等宽字体。
  - **string 长度 > 200 → 截断 + `Collapse` 「Show full」**。
  - object/array → `JSON.stringify(v, null, 2)`，长度 > 300 → 折叠预览前 80 字符。
- `tool_call` 段若 `toolName` 为空 → 整段不渲染（返回 null）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2746-2858`。

### plan_card 渲染

- 无 `name` → 不渲染（返回 null）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2746-2858`。
- 计划面板渲染见「计划与权限模式」章节。

### 用户消息与图片

- 用户气泡的 text 段是**纯文本**（webui 直接 `<span>{msg.segments[0]?.content}</span>`，不做 Markdown；`harnax-webui/src/pages/session/components/ChatWindow.tsx:3420`），气泡上方展示 `imageUrls` 缩略图。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:75-89`（`imageUrls` 字段）、`:3413-3419`（消息内图片渲染）、iOS `Sources/HarnaxFeatures/Chat/ChatTranscriptRows.swift:30-55`（`userBubble`）。

### 气泡容器

- 用户气泡：品牌底，尖角留在右下。锚点 `harnax-webui/src/pages/session/components/ChatWindow.less:257-264`；iOS `Sources/HarnaxFeatures/Chat/ChatTranscriptRows.swift:92`（`userCard`）。
- 主管回答：`surface` 底 + `separator` 发丝描边，尖角留在左下，与用户气泡成镜像。锚点 `harnax-webui/src/pages/session/components/ChatWindow.less:266-275`；iOS `:87`（`answerCard`）+ `:70-82`（`leadAnswer`）。
- 成员运行：与主管同底同形，尖角收在左上与左下，靠左 3pt 色带和状态色描边标明这是被委派的一轮。锚点 `harnax-webui/src/pages/session/components/ChatWindow.less:277-281`；iOS `:135`（`memberCard`）。
- 空轮次不画任何容器：一轮在首帧之前不成立，「正在读」由 `ChatReadingRow` 说。gate 在 `answer`（`Sources/HarnaxFeatures/Chat/ChatTranscriptRows.swift:58-68`），判据是 `turn.hasContent`。
- 卡内块各留一档底：代码块、文件行是 `surfaceAlt`，工具卡沿用 `surface` 底加状态色发丝边，靠描边与所属气泡分开。`surface` 与 `surfaceAlt` 上的文字组合都在对比度表里（`Sources/HarnaxKit/Theme/Palette.swift:75-95`）。

---

## 工具确认

### 主管（lead）确认：模态弹窗 + 读流阻塞

- 触发：`ToolConfirmEvent` 到达，`pendingCallTools` 为工具数组；**无 toolName 的项直接 skip**（`:1670`）；**计划相关工具（`plan_enter|plan_write|plan_exit`）skip，不弹确认**（`:1676`）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1657-1738`。
- 卡片复用：若同一 `toolId` 已有卡片，则**原地只改 `confirmStatus` 并覆盖 content**，不新增段。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1683-1703`。
- 弹窗组件 `ToolConfirmCard`：**挂载即 `modalVisible = true`**（无延迟、无动画门）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:468-552`（`:472`）。宽度 700。
- 弹窗内容：`Table`，列 = `toolName` + `Risk Level`，`isDangerous ? 红色「高危」 : 绿色「低危」`；`defaultExpandAllRows` 默认展开全部行的 Arguments 表。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:485-505`（风险列）、`:538-548`（展开参数）。
- 按钮：`拒绝`（danger）与 `允许执行`（primary），**只有整批两个按钮，没有单工具粒度的批准**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:517-526`。
- UI 状态（关键）：弹窗挂起时执行 `await new Promise(resolve => setPendingConfirm({pendingCallTools, resolve}))`，**整个 SSE 读循环被阻塞**，弹窗期间消息流不再更新，`loading` 保持 true。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1709-1714`；`pendingConfirm` state 定义 `harnax-webui/src/pages/session/components/ChatWindow.tsx:585-588`。
- 用户作答后：把当前消息内**所有 `confirmStatus==='pending'` 的段一次性**改为 `confirmed` 或 `rejected`（同样批量、无逐个）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1720-1725`。
- 提交：`POST /api/router/agent/confirm`，body `{type:'CONFIRM', sessionId, isConfirmed, toolInfoList:[{toolId, toolName}]}`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1730-1749`；协议 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:136-145`（`ConfirmAgentRequest`）、`:165-168`（`ToolInfo{toolId?,toolName?}`）。
- **`alwaysAllow` 与 `toolResults` 前端完全不使用**：grep 整个 `harnax-webui/src` 无 `alwaysAllow` / `toolResults` 命中；协议虽有 `ToolConfirmResult{toolId,toolName,confirmed,alwaysAllow}`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:155-160`），但后端逐工具分支里 `ConfirmResult(tr.confirmed, toolUseBlock, null)` **第三参（alwaysAllow）传 null，即服务端忽略**。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:366-371`。iOS 可只实现 bulk 模式（`isConfirmed` 对全部 pending 生效，见 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:372-375`），**不要为「始终允许」做 UI**。

### 确认流是一个独立的 SSE 流（嵌套读流）

- `/confirm` 返回 SSE，前端对确认响应再做一次完整的分帧 + 事件解析，逻辑与主管主流同构。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1753-2341`；后端 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:118-126`（POST /confirm 返回 `Flux<ChatEvent>`）。
- 在确认流内部还允许**第三层嵌套确认**（成员/主管再次请求确认）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2036-2331`。
- 确认流内的断连检测：锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2328-2331`、`:2344-2347`。
- 无 pending 时后端直接回 `ErrorEvent` + `EndEvent`。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:355-364`。

### 成员（member）确认：内联卡片 + childRunId

- 多成员同一 run 同时待确认时**必须用 `childRunId` 定位**：`ConfirmAgentRequest.childRunId` 字段承载。锚点 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:136-145`；前端发送 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1295-1349`（`answerMemberConfirm`，POST confirm 带 `childRunId`）。
- 渲染位置：成员事件在 `data.source?.childRunId` 存在时**优先分流**到 `handleMemberEvent`，不进主管气泡。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1400-1403`、`:1221-1292`。
- 成员收到 `ToolConfirmEvent`：向该成员 run 的 segments push 一个 `tool_confirm` 段，并把 run 状态置 `awaiting_confirm`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1276-1277`。
- 内联确认卡 `ToolConfirmChatCard`（不同于主管弹窗）：
  - 展开初值 `useState(!!onAnswer)` —— **有 `onAnswer`（成员内联、可交互）默认展开；历史/只读默认折叠**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:422`。
  - 状态色：green / red / orange。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:425`。
  - 每个工具一行：`toolName` + `isDangerous` 时红色「高危」Tag + JSON `<pre>`（`maxHeight 100`）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:440-450`（高危 Tag 在 `:444`）。
  - **仅当 `onAnswer && status==='pending'` 才出现「拒绝 / 允许执行」按钮**；否则为纯展示。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:451-460`。
- 成员确认整轮一次性（无单工具粒度），服务端判定 `approved = toolResults.isEmpty ? isConfirmed : toolResults.all { it.confirmed }`；成功时**只回一个 `EndEvent`**，前端不等内容。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:427`、`:436`、`:411-442`。
- `confirm` 请求里 `childRunId` 会短路到 `confirmMemberRun`（走主管路径的前提是没有 childRunId）。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:346`。
- 成员确认的失败态映射（iOS 需给出对应文案）：`NO_PENDING` / `ALREADY_ANSWERED` / `NOT_IN_THIS_TEAM` / `STOPPED`。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:429-435`。
- 前端对成员确认的处理：POST 后读取响应文本，若发现 `ErrorEvent` 就 warning「该确认已不在等待」；**不解析其余内容**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1295-1349`。
- 组件卸载/收尾时把 `answerMemberConfirmRef` 置 null，避免向已卸载 UI 回写。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:606`（声明）、`:2375-2395`（finally 清理）。

### 确认期间的其余 UI 行为

- 主管 run 卡片状态：`awaiting_confirm` 时 run 摘要头部显示「等待确认」文案与警告样式。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2870-2889`（状态文案与样式映射）、`harnax-webui/src/pages/session/components/teamRun.ts:36-38`（`isRunOpen = running || awaiting_confirm`）。
- 断流收尾：主流 `finally` 会 `closeAllMemberRuns(false)` 并把主管侧未收口的工具卡标记为 interrupted。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2375-2395`；`markOpenToolCards` `:1148-1153`；`closeAllMemberRuns` `:1193-1197`。
- 后端资源锁：上一轮团队 run 仍有成员在等确认时，新消息会返回 `RESOURCE_LOCKED`「The previous team run is still waiting for a member…」，前端作为普通文本错误呈现。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:215-222`。

---

## 计划与权限模式

### 开关来源与会话配置读取

- 会话配置来自 `GET /api/admin/sessions/{sessionId}/config`（走全局拦截器 = `Bearer` + `X-Tenant-ID`）。锚点 `harnax-webui/src/services/ant-design-pro/chat.ts:48-56`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:112-126`（BizException 单独保留 403「会话禁用」与 404「不存在」的区分）。
- 前端拉取时 `.catch` 单独吞掉 config 错误（**config 失败绝不能连带丢失历史消息**，二者 `Promise.all` 但各自兜底）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:711-717`。
- config → state 映射（含默认值，iOS 必须照此）：锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:720-738`。
  - `enableThink = config.enableThink || false`
  - `enableSearch = config.enableSearch`
  - `enablePlan = config.enablePlan`
  - `permissionMode = config.permissionMode || 'DEFAULT'`
  - `modelSupportReasoning = config.modelSupportReasoning !== 0`
  - `modelThinkingMode = config.modelThinkingMode ?? (modelSupportReasoning !== 0 ? 1 : 0)`
  - **`(modelThinkingMode ?? 0) === 2` 时强制 `setEnableThink(true)`**（模型必须思考档）
  - `modelSupportInternet = config.modelSupportInternet !== 0`
  - `modelSupportVision = config.modelSupportVision !== 0`
  - `leadLabel = config.teamId ? config.name : undefined`（团队会话把主管气泡标注为团队名）
- 字段类型：`modelSupportReasoning/modelThinkingMode/modelSupportInternet/modelSupportVision` 与 `enableThink/enableSearch/enablePlan/permissionMode` 全为可空 Int?/String?。锚点 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionResponse.kt:35-50`；其中 `enable*` 实际由后端把布尔转 0/1（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt:113-116`），`modelSupport*` 来自模型表（`:106-109`），`name` 在 `:94`。

### 计划面板（右侧滑出）

- 打开入口两条：①输入区工具条的「启用计划」Switch（`enablePlan` 开关，无模型门控）；②右边缘的折叠/展开按钮（**仅 `enablePlan` 为真时存在**）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3590-3600`（Switch）、`:3298-3305`（右边缘按钮）、`handleTogglePlanPanel` `:2651-2669`。
- iOS 的口径：会话页右上角**没有**手动开计划抽屉的按钮（那个角落给了沙箱工作区，见「Workspace 抽屉」），`ChatViewModel.openPlanPanel` 随之删除。抽屉仍由计划调用自己打开（`ChatWindow.tsx:1501` 的对应实现 `ChatViewModel.reactToPlanFrame`，`isPlanPanelPresented` 只剩这一路写），输入区的「启用计划」开关与转录里的计划块都不动。
- 面板打开时 `loadPlans(false)` 全量拉取并 `loadCurrentPlan()`；关闭时清定时器。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2651-2669`。
- 当前计划接口：`GET /api/router/agent/session/{sessionId}/current-plan`（`X-Api-Key`）。锚点 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:170-178`；前端调用 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2479-2600`（fetch 于 `:2485` 附近）。
- 历史计划接口：`GET /api/router/agent/session/{sessionId}/plans`。锚点 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:157-165`；前端 `loadPlans` `harnax-webui/src/pages/session/components/ChatWindow.tsx:2603-2648`。
- 数据结构：`PlanNote{sessionId, planId, name, description, expectedOutcome, subtasks[], createdAt, finishedAt, costTimeSeconds, status}`；`PlanSubTask{name, description, expectedOutcome, outcome, state(TODO/IN_PROGRESS/DONE/ABANDONED), createdAt, finishedAt, costTimeSeconds}`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:110-132`。
- 渲染：
  - 当前计划卡 `renderCurrentPlanCard`，有效性判定 `hasValidCurrentPlan`（无 name 视为无效）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2965-3038`。
  - 历史计划用 `Collapse` accordion 模式，`defaultActiveKey=[plans[0].planId]`（只展开最新一条）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3041-3290`。
  - 子任务 `Table` 列：`state` / `name` / `costTimeSeconds`；`expandable` 展示 description / expectedOutcome / outcome；footer 展示 createdAt / costTimeSeconds。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3041-3290`。
- 轮询：
  - `current-plan` 每 **2s** 轮询，条件为 `enablePlan && currentPlanExpanded && currentPlan` 三者同时成立。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:649-669`；定时器声明 `:602-603`。
  - 历史计划 5s 定时器 `plansListTimerRef` **声明但从未启动**（只有 clearInterval），所以历史计划只在「开面板」或「手动刷新」时加载。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:603`、`:2651-2669`、`:2672-2675`（`handleRefreshPlans` 走增量）。
  - 增量刷新语义：`loadPlans(true)` 只把新出现的 `planId` **前插**，不重建已有项。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2603-2648`。
- 计划模式进入/退出（无独立按钮，纯由工具调用驱动）：
  - 进入：SSE 中出现 `CallToolEvent` 且 `toolName ∈ {plan_write, plan_enter}` 且 `enablePlan` → 自动展开面板 + `loadCurrentPlan()`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1500-1504`；判定函数 `isPlanRelatedTool` `:2943-2962`。
  - 退出：`ToolResultEvent` 且 `toolName === 'plan_exit'` → `flushUI()`、新建 assistant 消息、重置 `currentSegs`/`toolCallMap`、`setCurrentPlan(null)`、`planExitedRef = true`（阻止 finally 再回灌计划）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1544-1617`（`:1566-1591`）、`planExitedRef` 声明 `:604`、finally 判定 `:2375-2395`。
  - 计划工具的 `CallToolEvent` / `ToolConfirmEvent` **不渲染卡片、不打断思考连续性**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1507`、`:1676`。

### 权限模式

- 控件位置：输入区工具条上的 `Dropdown`（trigger = click），5 项 `DEFAULT / BYPASS / ACCEPT_EDITS / EXPLORE / DONT_ASK`，`selectedKeys=[permissionMode]`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3601-3638`；state 初值 `'DEFAULT'` `:583`。
- 交互：同值直接 return；否则**先乐观 `setPermissionMode`，再发命令，失败回滚**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3601-3638`。
- 底部说明文案 key：`pages.session.permissionMode.${permissionMode}`；非 DEFAULT 时高亮显示。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3601-3638`。
- 设置通道：**slash 命令 `/permission <mode>`**，即 `POST /api/router/agent/command` `{type:'COMMAND', command:'PERMISSION', args:<mode>}`（`sendSilentCommand('PERMISSION', key)`）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2408-2430`（`sendSilentCommand`）、`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:74-119`（`CommandAgentRequest`）；后端 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:105-113`。
- 服务端校验：`VALID_PERMISSION_MODES = setOf("DEFAULT","BYPASS","ACCEPT_EDITS","EXPLORE","DONT_ASK")`；非法值拒绝；`task-` 前缀会话拒绝改权限；改动通过 admin API 落库并失效 agent 缓存。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:1014-1040`（校验 `:1017`，task 拒绝 `:1025-1027`，写库 `:1030`，失效缓存 `:1036`）、白名单 `:1052-1053`。
- 注意：存在 `PUT /api/admin/sessions/{sessionId}/config` 可直改 `enableThink/enableSearch/enablePlan/permissionMode`（后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:128-141`，DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionChatUpdateRequest.kt:5-17`，业务校验 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt:284-305`），但 **webui 从不调用它**，一律走 `/command`。iOS 应同样走 `/command`，以免绕过 `task-` 会话与 thinkingMode=2 的保护逻辑。

### 能力开关（思考/联网/计划）

- 深度思考 Switch：`!modelSupportReasoning` → disabled 样式 + warning toast；`modelThinkingMode === 2` → info toast「当前模型必须深度思考，无法关闭」并 **直接 return 不发命令**；否则 toggle + `sendSilentCommand(ENABLE|DISABLE, 'thinking')`，失败回滚。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3506-3572`。服务端同样有 `thinkingMode=2` 禁关保护。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:961-970`。
- 联网搜索 Switch：`!modelSupportInternet` → disabled + toast；否则 toggle + `ENABLE/DISABLE 'search'`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3573-3589`。
- 启用计划 Switch：**无任何模型门控**；toggle + `ENABLE/DISABLE 'plan'`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3590-3600`。
- 能力白名单：`SUPPORTED_CAPABILITIES = setOf("search","thinking","plan","bypass")`，其他 capability 名会被服务端拒绝。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:1052-1053`；校验入口 `:940-960`（`task-` 会话拒绝 `:952-954`）。
- 「思考过程」显示开关不走后端：右键 `contextMenu` 切 `showThinking`，**纯前端**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3506-3572`。
- 会话切换时必须重置这三个开关（含 `enableThink/Search/Plan`）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:683-705`。

---

## slash 命令

### 前端映射表（9 keyword → 8 CommandType）

锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:946-956`：

| keyword | command |
|---|---|
| `interrupt` / `stop` | `INTERRUPT` |
| `clear` | `CLEAR` |
| `compact` | `COMPACT` |
| `approve` | `APPROVE` |
| `stop-sandbox` | `STOP_SANDBOX` |
| `enable` | `ENABLE` |
| `disable` | `DISABLE` |
| `permission` | `PERMISSION` |

- **与后端 10 个 CommandType 的差额**：前端表缺 `deny`/`reject` 与 `refresh`；后端 `APPROVE("approve")`、`DENY("deny","reject")`、`REFRESH("refresh")` 均存在。锚点 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:196-207`；`fromKeyword` 忽略大小写 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:209-221`。
- 结论：**10 个 CommandType 并未全部在 UI 开放**；`deny`/`reject`/`refresh` 只能通过 UI 按钮（拒绝按钮走 `/confirm`、面板刷新按钮走增量 `loadPlans`）而非 slash 触发。iOS 对齐 webui 时同样只暴露 9 个 keyword（若要更完整可自行补齐，但属超出对齐范围）。

### 解析规则

锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:961-983`：

1. 必须以 `/` 开头，`/` 后为空则不是命令。
2. 取**首个空格与首个冒号中索引较小者**作为分隔符（两者都无 → 整串当 keyword）。即 `/permission:bypass` 与 `/permission bypass` 等价。
3. `keyword` 小写化后查表；未命中 → 返回 null → **当普通文本消息发送**（不报错）。
4. `args` = 分隔符之后的剩余并 `trim()`。
- 后端有同构解析（space/colon 取最小索引）：锚点 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:98-118`（`CommandAgentRequest.parse`）。

### 发送与展示

- 命中命令时：把**原始文本**作为用户气泡（单个 text 段）插入，清空输入框，然后 `POST /api/router/agent/command`，body `{type:'COMMAND', sessionId, command, args}`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:986-1032`（气泡 `:992-998`，POST `:1003-1010`）。
- 命令回复取文案：`json.data?.message || (json.data?.success ? 'Done' : json.message || 'Command failed')`，作为 assistant 单 text 段气泡展示，**不走 SSE**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1012`。
- 静默命令（能力开关/权限/停止沙箱/中断）走同一 `/command` 接口但不产生气泡；判定 `result.data.success === false && message` → warning toast 并返回 false 供上层回滚。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2408-2430`。

### 需要二次确认的命令

- `STOP_SANDBOX`：**先查 `getWorkspaceStatus`，active 才弹 `Modal.confirm`（danger 按钮样式）**，确认后才发命令；非 active 直接 warning 不发命令。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3639-3664`。
- `CLEAR`：`Popconfirm` 确认后 `handleClearChat`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3665-3677`；`handleClearChat` `:2678-2708`。
- 其余（enable/disable/permission/interrupt）无二次确认。

### 后端行为差异（iOS 文案需知晓）

- `INTERRUPT` 无活跃执行时返回 failure「No live execution for this session on this instance」。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:253-259`；`live` 判定 `subscription != null || activeCalls.contains(...)` `:340`。
- `COMPACT` 已实现，入口 `:264` → `handleCompact` `:917-…`。三种拒绝都带各自文案，iOS 直接显示返回值即可：`task-` 前缀会话「Compaction is not supported for task sessions」`:921-923`；团队成员自身会话「This is a team member's own session; compact the team session instead」`:924-929`；该会话正在应答「This session is answering right now; wait for the turn to end and compact again」`:930-935`。参数是一个可选的正整数 keepTokens（`:937`，非数字则按服务端默认）。成功回 `result` 里的 `beforeMessages` / `afterMessages` / `beforeTokens` / `afterTokens`（`:948-…`），文案 `Compacted N messages (M tokens) into …` `:980`。
- `CLEAR` `:260-263`；`APPROVE/DENY` → `handleApproveOrDeny` `:265-266`（实现 `:998-…`）；`STOP_SANDBOX` 销毁成员沙箱并失效缓存 `:267-283`；`ENABLE/DISABLE/PERMISSION` `:284-292`；`REFRESH` 失效 agent 缓存 `:293-…`。
- `executeCommand` 总入口 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:247`。

---

## 上下文占用读数与压缩

### 读通道（四跳）

| 跳 | 锚点 | 这一跳说什么 |
|---|---|---|
| 客户端 → router | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:183-191` | `GET /api/router/agent/context/{sessionId}`，响应原样透传 |
| router 选实例 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt:397-407` | `boundInstance(sessionId)` 为空 → **`ResultVo.success(null)`**（会话从未绑定过实例） |
| router → agent | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AgentServiceClient.kt:190-199` | 转发 `GET /api/agent/context/{sessionId}` |
| agent 侧 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt:123-136` | 本实例不持有该会话的 agent → `ResultVo.error("No context held for session … on this instance - usage needs the live agent")` `:134-135`，**HTTP 仍是 200，业务码是失败** |

载荷 `ContextUsageResponse`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ContextUsageResponse.kt:40-49`）与分母档位 `ContextWindowSource`（同文件 `:7-16`）。键名即 data class 属性名，camelCase，无命名策略参与。

### 两种「报不出来」的形状

`code 200 + data:null`（从未绑定）与 `code 500`（实例不持有 agent）都不是「上下文是空的」：两端在这两种形状下整块不显示读数，**绝不显示 `0%`**。

- webui 判据 `harnax-webui/src/pages/session/components/contextUsage.ts:22-26`，挂载点 `harnax-webui/src/pages/session/index.tsx:142-153`。
- iOS 判据 `Sources/HarnaxCore/Contract/ContextUsage.swift:86-88`（`contextWindow > 0 && ratio.isFinite`）；空 `data` 靠 `ContextUsage: HarnaxVoid` 解成空值（`Sources/HarnaxAPI/ContextUsageClient.swift`），业务失败落进 `Result.failure`，调用侧两条同处理。

### 分子与分母的口径

- `ratio` 由服务端算好下发：分子优先 `lastCallInputTokens`（账单真值），没有账单时取 `estimatedTokens`（上游估算），分母 `contextWindow`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ContextUsageResponse.kt:21-38` 写明两个数为什么并存）。客户端不重算，只按 `lastCallInputTokens` 是否为 null 标口径。
- 分子跟的是最近一次**已计费**调用，所以一次压缩不当场改变 `ratio`，要等下一轮结束后的重读才动。
- 分母三档：`MODEL_FIELD`（模型域 `context_window` 列）→ `UPSTREAM_TABLE` → `FALLBACK`；未知档位的新名字保留服务端原词，不并入 `FALLBACK` 的说法。
- 自动压缩触发线由 harnax 按上游算法现算：`triggerTokens = contextWindow - reserved`，`reserved` 取上游默认 20,000，`contextWindow <= 0` 时用上游 `FALLBACK_TRIGGER_TOKENS = 160_000`，算出非正数时钳到 `max(1, contextWindow / 2)`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt:459-480`）；`triggerMessages` 用上游默认 50（`CompactionConfig.java:273-275`，harnax 读路径不覆写）。
- 展示单位：四个 token 行（账单／本地估算／窗口／触发线）不写裸数字，按仓内既有的 M/K 口径缩写——≥1e6 记 `x.xxM`、≥1e3 记 `x.xxK`、不足 1000 原样，webui 走 `src/utils/tokenFormat.ts`（首页总览同源），iOS 走 `TokenFigures.token`（token 页同源），两端因此对同一个数说同一个样。缩写只改展示，`ratio` 与触发判据读的还是原始整数。缺失的账单仍写「尚未记录」而不是被缩写成 `0`；消息条数是条数，不缩写。

### 压缩命令

入口与三种拒绝见上一节「后端行为差异」的 `COMPACT` 条。两条客户端共同的判据：

- 成败**只认 `success` 旗标**。协议里 `CommandResponse.success: Boolean` 是非空（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/CommandResponse.kt:13-17`），所以「读到命令体却没有旗标」不可能是线上形状，只能读本端没读到——webui `harnax-webui/src/pages/session/components/contextUsage.ts:73-80` 与 iOS `CompactionOutcome.compact`（`Sources/HarnaxCore/Contract/AgentStreaming.swift:81-87`）同判据，一律读成失败，绝不说「已压缩上下文」。
- 计数只区分「压成」与「没得压」：`beforeMessages == afterMessages` 时报告还太短；计数缺失但旗标为真时只报告压成、不给数字。

### iOS 落点

| 位置 | 文件与行 | 要点 |
|---|---|---|
| 契约判据 | `Sources/HarnaxCore/Contract/ContextUsage.swift:86-147` | `isReadable`／`basis`／`isAtAutoTrigger`／`percentText` 四条与 `contextUsage.ts` 一一对应；`percentText` 在比值越出整数范围时钳到 `Int.max` 而不是 `Int(Double)` 崩溃（`:119-134`） |
| 读端点 | `Sources/HarnaxAPI/ContextUsageClient.swift` | 独立协议 `ContextUsageReading`，不与历史/计划共用；`sessionId` 先按路径段编码 |
| 读数组装 | `Sources/HarnaxFeatures/Chat/ContextUsageReadout.swift:26-58` | 芯片两句 + 名／数两列的五行明细，读序即 `rows` 的序；账单行缺失时写「尚未记录」而不是 `0`；四个 token 行走 `TokenFigures.token` 的 M/K 档，消息条数不缩写 |
| 标题栏 | `Sources/HarnaxFeatures/Chat/ChatView.swift:92-93`、芯片 `:160`、面板 `:192` | 单点 unwrap `vm.contextUsage`——模型里不留读不到的读数，缺席本身就是判据；webui 的 hover Tooltip 在 iOS 用 `popover` 承载（`Menu` 会把内容摊成一个叶子一行，名与数进不了同一行）；数那一列按自己最宽的一格撑开并右对齐、名那一列吃掉余量，面板宽度有下限，行字取 13 点档（15 点档下英文最宽一行 382 点 > 最窄手机 375 点）；排在 workspace 入口之前 |
| 输入区 | `Sources/HarnaxFeatures/Chat/ChatView.swift:543-555` | `chat.composer.compact` 芯片，排在权限芯片之后、停沙箱与清空之前（对齐 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3716-3765`）；无二次确认；`isMuted: vm.isStreaming`；hover 文案换成读屏提示 `chat.context.compactTip` |
| 定序 | `Sources/HarnaxFeatures/Chat/ChatViewModel.swift:1471-1486`、计数器 `:149` | 每次询问递增代数，落地时同时校验会话 id 与代数：后问先答的旧读数丢弃，切走后的答复不挂到新会话标题下 |
| 补读时机 | 进入会话 `Sources/HarnaxFeatures/Chat/ChatView.swift:115`（`.task(id: conversation)`）；本轮结束 `Sources/HarnaxFeatures/Chat/ChatViewModel.swift:733`、`:764`；压缩返回 `:1573`；**压缩被取消也补读** `:1540` | 取消丢掉的只是横幅与气泡：命令已在服务端跑过 |
| 应答文案 | `Sources/HarnaxFeatures/Chat/ChatViewModel.swift:1613-1630`（点按四支）；手敲走通用链 `:1587-1595` | 点按以横幅呈现（压成与「还太短」平静色、被拒警示色），手敲作为一行助手说明进气泡 |

### 画面不变量

压缩只重写 `AgentState.context`；页面气泡读逐条追加的归档，因此压缩后屏幕仍是全部原始消息，且不出现摘要气泡。iOS 侧由 `Tests/HarnaxFeaturesTests/ChatHistoryLoadTests.swift:217`（`testACompactionLeavesEveryStoredBubbleOnScreen`）钉住，其余判据的承载：旗标与计数 `Tests/HarnaxCoreTests/CompactionOutcomeTests.swift`、比值格式与钳位 `Tests/HarnaxCoreTests/ContextUsageTests.swift`、线上形状 `Tests/HarnaxAPITests/ContextUsageEndpointTests.swift`、明细行 `Tests/HarnaxFeaturesTests/ContextUsageReadoutTests.swift`、文案分支与定序 `Tests/HarnaxFeaturesTests/ChatViewModelTests.swift`、标题栏只 unwrap 一次 `Tests/HarnaxFeaturesTests/ContextUsageViewGateTests.swift`。

---

## 本会话自写技能（交叉引用）

对话页标题栏最后一枚入口打开的那块列表，行为逐条写在 `FUNCTIONS.md` §3.17，队列侧的契约写在 `07-skill-draft-review.md`；本节只登记它跨的三条路由与一条读侧不变量。

- 读走 `SessionSkillReading` 一个门面、两条腿：提名腿 `GET /api/admin/skill-drafts?status=PENDING&sessionId=`（admin 队列的会话谓词），目录腿 `GET /api/router/agent/session-skills/{sessionId}`（`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:199` → `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/SessionSkillController.kt:29`）。目录里出现即「已启用」，应答不带开关。
- 写只有启用一条：`POST /api/router/agent/session-skills/{sessionId}/{name}/enable`（`AgentProxyController.kt:215`），没有请求体——操作者由 router 自己的鉴权上下文命名，这边发什么都是主张不是事实。
- **两份读各走各的失败**：一条腿挂了只丢它那半，另一条腿答上来的行照常可点；只有两腿都没答才说「读不出来」。这条规矩存在是因为一份失败绝不能替另一份去回答「这个会话没提出过技能」。

---

## 图片与附件

### imageUrls 是 base64 data URL，不是上传后的 URL

- 前端构造路径（这就是任务要求「给出前端构造该字段的代码路径」的答案）：`harnax-webui/src/pages/session/components/ChatWindow.tsx:2442-2468` `handleImageUpload` 用 **`FileReader.readAsDataURL(file)`** 生成 `data:image/...;base64,...` 字符串，`push` 进 `imageUrls` state（state 声明 `harnax-webui/src/pages/session/components/ChatWindow.tsx:584`）。
- **不存在任何图片上传接口**：整个 session 目录与 services 中无图片上传调用；`/chat/stream` 直接把 `imageUrls` 数组随 body 发出。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1353-1358`（`chatBody` 含 `imageUrls`）。
- 移除图片：`handleRemoveImage`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2470-2476`。预览条 + 删除按钮：`:3463-3477`。
- 隐藏文件选择：`<input type="file" accept="image/*" multiple>`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3692-3699`。图片按钮 gating：`!modelSupportVision` → disabled 样式 + warning toast，**不阻止已存在的 state，只阻止再选文件**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3493-3505`。
- 协议侧字段语义确认：`ChatAgentRequest.imageUrls` 注释为「List of image URLs or base64 data URLs」。锚点 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:50-58`（字段与注释 `:53`）。
- 后端解析（**不是远程 URL 下载**）：`HarnessAgentWrapper.imageBlock` 只在 `startsWith("data:image")` 时按 base64 解析（`url.split(",")`，part[0] 中 `:` 与 `;` 之间为 mimeType，part[1] 为 base64）；否则当**本地文件路径** `Files.readAllBytes(Paths.get(url))` 并硬编码 `image/png`。锚点 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt:814-829`；`callStream(prompt, imageUrls)` 入口 `:345-354`。
  - **因此 iOS 只能传 base64 data URL**；传 http(s) URL 会被服务端当本地路径读，必然失败。

### 图片不持久化

- `UserMessageLog` 只有 `message`/`timestamp`/`source`，**无 imageUrls 字段** → 历史回放不含图片。锚点 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:34-40`。
- iOS 结论：发送后本地图保留在内存气泡；重进会话必然丢图，需在 UI 上明确这个行为（与 webui 一致）。

### EndEvent.attachments

- `EndEvent{tokenUsage, attachments: List<FileAttachment>}`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:145-151`），**webui 未渲染 attachments**。iOS 若要增强可展示，但对齐范围内忽略。

---

## 历史回放映射

### 接口与传输

- `GET /api/router/agent/chat/history/{sessionId}`，返回 `ResultVo<List<Any>>`，**后端原样透传 MessageLog 列表**（不做强类型收敛）。锚点 `harnax-webui/src/services/ant-design-pro/chat.ts:35-43`（走 `buildRouterOptions` = `X-Api-Key` + skipAuthorization，`:21-30`、`:5-16`）；后端 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:144-152`。
- 每条日志基础字段：`role`（USER/ASSISTANT/TOOL/SYSTEM）、`timestamp`、`source`。锚点 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:11-24`（接口）、`:66-71`（Role 枚举）。
  - `UserMessageLog{message, timestamp, source}` `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:34-40`
  - `AssistantMessageLog{thinking, text, toolUseLog[{name,input}], timestamp, source}` `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:42-52-55`
  - `ToolResultMessageLog{name, result, ...}` `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:57-64`
- 时间格式：`timestamp` 上线是 **Long epoch 毫秒**（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:13,28,36,46,60`）。`yyyy-MM-dd HH:mm:ss.SSS` 只是**入向**的 `Msg.timestamp`，被 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLogConverter.kt:20-38` 解析后即丢弃，从不上线；两个既有消费方都直接把该值喂给 `new Date()`（`harnax-webui/src/pages/session/components/ChatWindow.tsx:764`、`harnax-wechat-app/miniprogram/pages/session/chat/index.ts:32-37`）。
- 解析失败静默回退 `System.currentTimeMillis()`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLogConverter.kt:29,36`），因此**多条日志可以共享同一时间戳**；团队回放的 `interleave` 又刻意不拆开 assistant 与其 tool 行，序列**本身就不是时间单调**（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/TeamHistoryReplay.kt:91-122`，`harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/runner/TeamHistoryReplayTest.kt:114` 断言 `[100, 200, 500, 300, 600]`）。结论：客户端只按数组下标顺序消费，绝不按 timestamp 重排，也不拿它单独当 id。

### 前端映射算法（iOS 需 1:1 复刻）

锚点总览 `harnax-webui/src/pages/session/components/ChatWindow.tsx:741-933`。

- 消息 id 合成：`${role.lowercase}-${timestamp || now}-${index}`。锚点 `:764`。
- **顺序扫描 + 索引跳过**：`lastProcessedIndex` 保证被吸收的 TOOL 日志不重复处理。锚点 `:757`、`:760-761`、`:785`、`:910`、`:915`、`:921`、`:928`。
- USER 分支：结束当前 assistant 聚合、**清空 memberBubbles 与 memberBubbleKey**、生成单 text 段用户气泡。锚点 `:774-785`。
- ASSISTANT 分支：
  - `runId = log.source?.childRunId`；带 runId 即成员输出。锚点 `:765`。
  - 气泡键规则：`continuesRun = logs[i-1].source?.childRunId === runId`；连续同 runId → 复用同一气泡键，否则新键 `${runId}#${i}`（**子会话会被多次委派复用，runId 本身不足以区分委派**）。锚点 `:766-772`、`:749-752`。
  - 主管日志（无 runId）先记录 `delegatedTasksOf(log)` → `lastTaskByMember[memberAgentId] = task`（因为主管日志先落库、成员日志排在之后）。锚点 `:787-792`；`delegatedTasksOf` 只扫 `role==='ASSISTANT'` 的 `toolUseLog` 找 `team_delegate`，锚点 `harnax-webui/src/pages/session/components/teamRun.ts:96-105`；`parseDelegateCall` 取 `member_agent_id`（**容忍引号/空格**：`Number(String(id).replace(/["'\s]/g,''))`）与 `task`，锚点 `harnax-webui/src/pages/session/components/teamRun.ts:71-80`；工具名常量 `TEAM_DELEGATE_TOOL = 'team_delegate'` `harnax-webui/src/pages/session/components/teamRun.ts:33`。
  - 成员气泡创建：`id = member-${bubbleKey}`、挂 `teamSource`、`teamRun{status:'done', startedAt/endedAt=stamp, toolCount:0, task: lastTaskByMember[...]}`、`parentToolId = claimDelegateCard(...)`；插入位置在主管气泡**之前**（与实时流一致：主管一轮要等成员跑完才收尾）。锚点 `:795-823`；`claimDelegateCard` 顺序认领未被占用的 `type==='tool_call' && toolName==='team_delegate' && toolId` 且 `delegateMemberOf(seg.content)===memberAgentId` 的卡，锚点 `harnax-webui/src/pages/session/components/teamRun.ts:133-146`；`claimedDelegates` 去重 `:756`。
  - 段生成顺序：**thinking → tool_call[] → text**。
    - thinking：末段是 thinking 则合并（用 `'\n\n'` 拼接），否则新段。锚点 `:838-849`。
    - tool_call：向后连续收集 `role==='TOOL'` 且 `source.childRunId` 相同的结果行（`:855-861`）；逐条 `toolUseLog` 生成段，`content = JSON.stringify(tool.input ?? {}, null, 2)`，**`toolId` 合成 `${msgId}-t${replaySegSeq++}`**（历史无 toolId，成员挂卡要靠它）。锚点 `:852-897`（`:877-882`）；无 name 跳过 `:867-869`；`isPlanRelatedTool` 跳过 `:872-874`；匹配结果 `!used && (!r.log.name || r.log.name===toolName)` `:884-886`；**匹配不到 result → `interrupted = true`**（说明那一轮被打断，不能一直显示调用中）`:890-893`。
    - text：`log.text` 非空则 push text 段。锚点 `:900-902`。
  - 成员气泡的 teamRun 汇总：`toolCount = tool_call 段数`，`endedAt = max(已有, log.timestamp)`。锚点 `:905-908`。
- TOOL 分支：`isPlanRelatedTool` 跳过（`:912-917`）、无 name 跳过（`:919-923`）、**其余一律不渲染独立 tool_result 段**（已被前一条 ASSISTANT 吸收）。锚点 `:911-929`（注释 `:925-926`）。

### 团队 childSession 合并规则（服务端已完成，前端只管 source）

- 历史入口就是合并结果：`loadHistory = merge(lead)`。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:338-341`。
- `TeamHistoryReplay.merge`：member 日志**仅保留 ASSISTANT / TOOL**（丢弃 SYSTEM/USER）。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/TeamHistoryReplay.kt:36-56`（`:46`）；文档 `:12-23`。
- `resolveSources`：为每条成员日志打 `source`，且 **`childRunId = childSessionId`**（历史没有真实 runId，用子会话 id 顶替）。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/TeamHistoryReplay.kt:65-89`（`:81-82`）；团队配置读不到时返回 null → **只回退 lead，成员历史整体消失** `:65-89`。
- 子会话 id 格式：`"team-$rootSessionId-m$memberAgentId"`（即任务中的 `team-<root>-m<agentId>`）。锚点 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt:55-63`。
- 交错算法 `interleave`：按 turn block 交错，**绝不拆开 assistant 与其 tool result**。锚点 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/TeamHistoryReplay.kt:101-122`；`splitTurns` `:128-138`。
- iOS 结论：合并与交错由服务端负责，客户端只需按 `source.childRunId` 分气泡 + 按顺序认领 `team_delegate` 卡；但要注意「同 childRunId 连续段 = 一个气泡」这个判据只在服务端不重排的前提下成立，回放里 runId 实为 childSessionId，因此**同一成员的多轮委派会共用同一个 runId**，前端必须继续靠「上一条是否同 runId」断开气泡（`harnax-webui/src/pages/session/components/ChatWindow.tsx:766-772`），iOS 若简化成 `Map<runId, bubble>` 会把多轮委派错误合并成一个气泡。

---

## 流式行为

### 传输与分帧

- 使用 `fetch` + `response.body.getReader()`（**不是 EventSource**），因此可带自定义 header 与 POST body。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1361-1370`。
- 请求：`POST /api/router/agent/chat/stream`，headers = `Content-Type: application/json` + `Accept: text/event-stream` + `getRouterHeaders()` + `AbortController.signal`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1361-1370`。
- 分帧算法（iOS 必须等价）：`buffer += decoder.decode(chunk)` → `split('\n')` → `buffer = lines.pop()`（**末行不完整则留待续接**）→ 只处理以 `data:` 开头的行 → `substring(5).trim()` → `JSON.parse`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1380-1394`。
- 事件类型判别靠 `eventType` 字段（Jackson `JsonSubTypes`），8 个分支。锚点 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:18-32`（判别注册）、`:210-219`（EventType）、`:33-45`（interface：eventType/tokenUsage/source）。

### 事件处理矩阵

| 事件 | 行为 | 锚点 |
|---|---|---|
| `TextEvent` | 续接/新开 text 段；`isLast===true` 丢弃内容并复位 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1405-1442` |
| `ThinkingEvent` | 同上，thinking 段 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1444-1485` |
| `CallToolEvent` | toolId 缺失回退 `tool-${Date.now()}`；计划工具触发面板；非计划工具重置 text/think 连续性；`noteDelegateCall` 登记委派目标；三级去重后原地更新或新增 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1487-1542`（`:1489`、`:1500-1504`、`:1507`、`:1513-1518`、`:1520`、`:1523-1541`） |
| `ToolResultEvent` | 计划工具特殊分支（plan_exit 收尾）；非计划写 `toolResult`，`data.success===false → confirmStatus='rejected'`；`completeDelegateResult` | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1544-1617`（`:1553-1593`、`:1566-1591`、`:1596-1617`、`:1602`） |
| `ToolConfirmEvent` | 弹主管确认框，阻塞读流（详见「工具确认」） | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1657-1738` |
| `ErrorEvent` | 追加 text 段「发生错误: ${message ?? code}」+ toast 8s + `setLoading(false)` + `streamTerminated = true` + `closeAllMemberRuns(false)` | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1619-1633` |
| `EndEvent` | `streamTerminated = true` + `closeAllMemberRuns(true)` + 过滤空 text/thinking 段 + **立即 setLoading(false)** | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1635-1655` |
| `KeepAliveEvent` | **主管侧完全无分支 → 被忽略**（既不渲染也不结束） | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1395-2360` 区间内无 keepalive 处理（协议要求见下） |

- KeepAlive 协议语义（务必遵守）：成员停在确认时不产生任何事件，router `stream-idle-timeout=120s`、channel 180s 会切断静默流；**消费者不得渲染、不得当作结束，该事件不带内容与 tokenUsage**。锚点 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:178-191`。
- 成员 KeepAlive 风险：`TeamOrchestrator` 发布的 KeepAlive **带 source（含 childRunId）**（锚点 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamOrchestrator.kt:427`），因此会先命中「member 事件优先分流」（`harnax-webui/src/pages/session/components/ChatWindow.tsx:1400-1403`）进入 `handleMemberEvent` 的无分支路径，可能凭空画出/重绘一个空成员气泡。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1122-1145`（`paintMemberRun`）。iOS 应在入口显式丢弃 `eventType == 'keepalive'`。
- `tokenUsage`：每事件都带（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:33-45`、`TokenUsage{inputTokens,outputTokens,totalTokens,costTime,timestamp}` `:62-81`），**webui 不展示 token 统计**。

### 生命周期与收尾

- `finally` 必做：`setLoading(false)`、`abortRef = null`、`answerMemberConfirmRef = null`、`closeAllMemberRuns(false)`、`markOpenToolCards`（把主管未收口卡标为 interrupted）、`enablePlan && currentPlanExpanded && !planExitedRef` 时刷新计划。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2375-2395`。
- 组件卸载即 abort。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:672-680`；`abortRef` 声明 `:600`。
- 会话切换重置所有流式 state。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:683-705`。
- **已核实的 webui 缺陷（iOS 不要复刻）**：`ChatWindow` 在切换会话时组件不卸载（父页面只换 `sessionId` prop，锚点 `harnax-webui/src/pages/session/index.tsx:309`），而 abort 只写在依赖数组为 `[]` 的卸载 effect 里（锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:672-680`）；`sessionId` 变化的 effect（锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:683-705`）**不调用 `abortRef.current?.abort()`**。因此上一会话正在进行的 SSE 会继续将事件写进新会话的消息区。iOS 必须在 `sessionId` 变更的 `onChange` 里先 abort 旧任务再加载，并同时重置 `loading` 与分段累计变量。

### 停止 / 中断

- `handleStop = abortRef.current?.abort()` + `sendSilentCommand('INTERRUPT')` + `setLoading(false)`（**三路并发，不等待**）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2401-2405`。
- 发送按钮在 loading 时切换为 Stop 图标 + danger 样式 + `onClick = handleStop`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:3679-3686`。

### 断线与重连

- **webui 没有任何重连/重试逻辑**（无 EventSource、无 reconnect/backoff），唯一表现是「断连提示」。锚点：断连判定 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2361-2366`（`!streamTerminated && currentSegs.length === 0` → 插入断连文案气泡）、确认流断连 `:2328-2331`、`:2344-2347`。
- 对应服务端硬限制（iOS 文案与超时设计依据）：router `stream-idle-timeout-seconds: 120`、`stream-max-duration-minutes: 30`。锚点 `harnax-session-router/src/main/resources/application.yml:112-113`；`request-timeout: 1800000`（30min）`:20-22`；JSON 调用 `read-timeout-ms: 600000`（仅非流式）`:103`。
- iOS 建议（超出 webui 现状，标注为改进项）：可在 `!streamTerminated` 断连气泡上提供「继续」按钮，通过 `INTERRUPT` + 重新发送实现；但默认规格以 webui 行为为准。

### 自动滚动策略

- 阈值 `NEAR_BOTTOM_THRESHOLD = 120`（px）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:612`。
- `scrollToBottom(force)`：仅当用户接近底部（距底 < 120）时自动滚。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:614-617`、messages 变化触发 `:630-632`（仅 nearBottom 为真）。
- `handleScroll` 计算距底距离，同时维护「回到底部」浮钮可见性。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:620-627`；浮钮渲染 `:3452-3456`，点击强制回底 `:635-639`。
- iOS 映射：`ScrollView` + `onGeometryChange`/`GeometryReader` 计算 content inset 距底；不要用 `ScrollViewReader` 无条件 scrollTo。

### 输入区门控矩阵（模型能力四开关）

| 控件 | 门控条件 | 禁用/特殊行为 | 锚点 |
|---|---|---|---|
| 图片按钮 | `modelSupportVision` | 关 → disabled 样式 + warning toast，不阻止已选图 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3493-3505` |
| 深度思考 | `modelSupportReasoning` | 关 → disabled + toast；`modelThinkingMode===2` → info「必须深度思考，无法关闭」并 return（不禁用外观但不可关） | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3506-3572` |
| 联网搜索 | `modelSupportInternet` | 关 → disabled + toast | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3573-3589` |
| 启用计划 | 无 | 始终可用 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3590-3600` |
| 权限模式 | 无（值受服务端白名单校验） | Dropdown 5 项，乐观更新 + 失败回滚 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3601-3638` |
| 停止沙箱 | 运行态 | 先查 status，active 才 `Modal.confirm` | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3639-3664` |
| 清空记录 | 无 | Popconfirm | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3665-3677` |
| 发送 | `loading` 或 `!inputValue.trim() && imageUrls.length===0` | loading 时变 Stop | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3679-3686` |
| TextArea | — | `autoSize {minRows:2, maxRows:6}`；loading 时 placeholder 切换 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:3480-3489` |
| Enter 键 | `loading` | Enter 发送 / Shift+Enter 换行；loading 时禁止 | `harnax-webui/src/pages/session/components/ChatWindow.tsx:2432-2439` |

- 输入框长文本：webui 是 `autoSize` 固定 6 行，iOS 改为**半屏封顶、超出内部滚动**。可容纳行数由测量高度推：`行数 = (屏高 × 0.5 − 20) / 正文行高`，下限 2 行；测不到屏高（尚未布局）时退回 6 行。阈值与公式在纯函数 `Sources/HarnaxFeatures/Chat/ChatComposerGrowth.swift`，单测 `Tests/HarnaxFeaturesTests/ChatComposerGrowthTests.swift`。屏高取本视图自身 bounds（`Sources/HarnaxFeatures/Chat/ChatView.swift:72-78` 的 `GeometryReader`），并只接受历史最大值——键盘把帧压缩时上限不跟着抖（`:18-31`），算出的行数交给 `ChatInputBar`（`:70`）落成 `.lineLimit(2 ... lineCap)`（`:287`）。
- 空状态快捷建议 4 条，仅在无消息时显示，点击直接 `doSend`（**不填输入框**）。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:566-571`（文案）、`:3327-3333`（渲染条件）。
- 主管气泡标签：团队会话用 `leadLabel = config.name`。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:720-738`、`:610`。

### 成员运行渲染要点（团队会话）

- 气泡插入位置在主管气泡之前。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1122-1145`（`paintMemberRun`，id = `member-${runId}`）。
- 成员 text/thinking 仅在 `isLast !== true && message` 时追加。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1236-1239`。
- 成员 End 只过滤空段，**不解除主管 loading**。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:1285-1290`。
- 嵌套区块仅在主管卡真实存在时挂载。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2717-2736`（`nestedRunsByTool`）、`:2904-2940`（`renderNestedRuns`）。
- run 摘要：状态文案与颜色 `harnax-webui/src/pages/session/components/ChatWindow.tsx:2870-2889`；`→ name +N` 委派提示 `:2892-2896`；`isRunExpanded` 未点过时「运行中展开、结束折叠」`:2864-2868`（override state `:608`）。
- 时长格式：`<60s → "Ns"`，否则 `"Xm YYs"`。锚点 `harnax-webui/src/pages/session/components/teamRun.ts:41-46`；`MemberRunStatus = 'running'|'awaiting_confirm'|'done'|'failed'` `harnax-webui/src/pages/session/components/teamRun.ts:20`；`MemberRunInfo` `harnax-webui/src/pages/session/components/teamRun.ts:23-30`。
- 任务首行截断：`firstTaskLine(task, maxChars = 48)` 取首个非空行按字符截断加 `…`。锚点 `harnax-webui/src/pages/session/components/teamRun.ts:54-62`。

---

## 接口清单

鉴权体系：`UnifiedAuthFilter` 同时接受 Bearer JWT 与 `X-Api-Key`（缺失时报「Provide a Bearer JWT (Authorization header) or X-Api-Key header.」）。锚点 `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt:73`（读 X-Api-Key）、`:102`（错误文案）。
- admin 侧（前端 axios 全局拦截器）：`Authorization: Bearer ${tokenInfo.accessToken}` + `X-Tenant-ID: tokenInfo.currentUser.currentTenantId`，`withCredentials: true`，`Accept-Language` 取 `umi_locale`（默认 zh-CN）。锚点 `harnax-webui/src/requestErrorConfig.ts:33`、`:49-56`、`:64-65`。
- router 侧：优先 `X-Api-Key: tokenInfo.routerApiKey`（登录时下发，锚点 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/LoginResponse.kt:32`），回退 Bearer。锚点 `harnax-webui/src/pages/session/components/ChatWindow.tsx:153-164`（`getRouterHeaders`，回退 `getAuthHeaders()` 于 `:163`）、`harnax-webui/src/pages/session/components/ChatWindow.tsx:137-150`（`getAuthHeaders`）；axios 版走 `buildRouterOptions`（`skipAuthorization: true` + `X-Api-Key`），锚点 `harnax-webui/src/services/ant-design-pro/chat.ts:5-16`、`:21-30`。

### 会话（admin，Bearer + X-Tenant-ID）

| 方法 | 路径 | 请求 | 响应 | 用途 / 锚点 |
|---|---|---|---|---|
| GET | `/api/admin/sessions/page` | `pageNum, pageSize, keyword?, status?` | `ResultVo<Page<SessionItem>>`，`create_time DESC`，可见性 `is_public=1 OR creator=自己` | 列表；`harnax-webui/src/services/ant-design-pro/session.ts`（`getSessionPage`）、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:29-53`、`harnax-entity/src/main/resources/mapper/SessionMapper.xml:104-118` |
| GET | `/api/admin/sessions/{id}` | 数字 id | `ResultVo<SessionResponse>` | 详情；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:55-66` |
| GET | `/api/admin/sessions/check-title` | `title` | `ResultVo<Boolean>` | 名称查重；`harnax-webui/src/services/ant-design-pro/session.ts`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:68-78` |
| POST | `/api/admin/sessions` | `{title(@NotBlank,max100), sessionDescription?, agentId?, teamId?}` | `ResultVo<Void>` | 新建；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:80-89`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionCreateRequest.kt:13-30`、`harnax-webui/src/pages/session/components/SettingsModal.tsx:95-101` |
| PUT | `/api/admin/sessions/update/{id}` | 更新体 | `ResultVo` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:91-109`（webui session 页未用） |
| GET | `/api/admin/sessions/{sessionId}/config` | 字符串 sessionId | `ResultVo<SessionResponse>`（含 modelSupport*/enable*/permissionMode/isPublic/name） | 会话配置；`harnax-webui/src/services/ant-design-pro/chat.ts:48-56`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:112-126`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionResponse.kt:35-60`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt:270-281`（tenant 校验 `:273`、status≠1 → 403 `:277-279`） |
| PUT | `/api/admin/sessions/{sessionId}/config` | `{enableThink?,enableSearch?,enablePlan?,permissionMode?}` | `ResultVo` | **webui 未调用**；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:128-141`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionChatUpdateRequest.kt:5-17`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt:284-305` |
| PUT | `/api/admin/sessions/toggle/{id}?status=` | — | `ResultVo` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:143-161` |
| DELETE | `/api/admin/sessions/{id}` | 数字 id | `ResultVo`（失败带 message） | 删除；`harnax-webui/src/services/ant-design-pro/session.ts`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:163-172`、`harnax-webui/src/pages/session/index.tsx:80-99` |

### 团队产物（admin）

| 方法 | 路径 | 鉴权头 | 响应 | 锚点 |
|---|---|---|---|---|
| GET | `/api/admin/team-artifacts?sessionId=` | Bearer + X-Tenant-ID（拦截器） | `ResultVo<List<TeamArtifactResponse>>`（不含 objectKey） | `harnax-webui/src/services/ant-design-pro/team.ts:79-85`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt:59-70`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamArtifactResponse.kt:9-32` |
| GET | `/api/admin/team-artifacts/{fileId}?sessionId=` | **仅 Bearer**（裸 fetch） | 二进制流 | `harnax-webui/src/services/ant-design-pro/team.ts:91-113`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt:72-100`（400/403/404 语义 `:78-86`） |

### Agent / Team 下拉（admin）

| 方法 | 路径 | 参数 | 锚点 |
|---|---|---|---|
| GET | `/api/admin/agents/page` | `pageNum,pageSize,status` | `harnax-webui/src/pages/session/components/SettingsModal.tsx:69-71` |
| GET | `/api/admin/agents/{id}` | — | `harnax-webui/src/pages/session/components/DetailModal.tsx:130-144` |
| GET | `/api/admin/teams/page` | `current,size,status`（service 内部转 pageNum/pageSize） | `harnax-webui/src/services/ant-design-pro/team.ts:6-25`、`harnax-webui/src/pages/session/components/SettingsModal.tsx:82` |
| GET | `/api/admin/teams/{id}` | — | `harnax-webui/src/services/ant-design-pro/team.ts:27-30`、`harnax-webui/src/pages/session/components/DetailModal.tsx:114-128` |

### Router（X-Api-Key 优先）

| 方法 | 路径 | 请求 | 响应 | 锚点 |
|---|---|---|---|---|
| POST | `/api/router/agent/chat/stream` | `{type:'CHAT', sessionId, message, imageUrls[], requestId?, userId?}` | `Flux<ChatEvent>`（SSE） | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1353-1370`、`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:50-58`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:89-100` |
| POST | `/api/router/agent/chat` | 同上 | 非流式 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:75-83`（webui 未用） |
| POST | `/api/router/agent/command` | `{type:'COMMAND', sessionId, command, args?, userId?}` | `ResultVo`（含 `data.success`/`data.message`） | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1003-1010`、`:2408-2430`、`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:74-119`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:105-113` |
| POST | `/api/router/agent/confirm` | `{type:'CONFIRM', sessionId, isConfirmed, toolInfoList[], toolResults?, childRunId?}` | `Flux<ChatEvent>`（SSE） | `harnax-webui/src/pages/session/components/ChatWindow.tsx:1730-1749`、`:1295-1349`、`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:136-145`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:118-126` |
| GET | `/api/router/agent/chat/history/{sessionId}` | — | `ResultVo<List<Any>>`（原样透传 MessageLog） | `harnax-webui/src/services/ant-design-pro/chat.ts:35-43`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:144-152` |
| GET | `/api/router/agent/session/{sessionId}/plans` | — | `ResultVo<List<PlanNote>>` | `harnax-webui/src/pages/session/components/ChatWindow.tsx:2603-2648`（fetch 于 `:2610` 附近）、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:157-165` |
| GET | `/api/router/agent/session/{sessionId}/current-plan` | — | `ResultVo<PlanNote?>` | `harnax-webui/src/pages/session/components/ChatWindow.tsx:2479-2600`（fetch `:2485`）、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:170-178` |
| DELETE | `/api/router/agent/session/{sessionId}` | — | `ResultVo` | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:131-139`（webui session 页未用） |
| GET | `/api/router/agent/context/{sessionId}` | — | `ResultVo<ContextUsageResponse>`，从未绑定实例时 `data:null` | `harnax-webui/src/services/ant-design-pro/chat.ts:49-60`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:183-191` |
| GET | `/api/router/agent/workspace/{sessionId}/files?path=` | path 默认 `/workspace` | `ResultVo<List<WorkspaceFile>>` | `harnax-webui/src/services/ant-design-pro/workspace.ts:38-51`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:198-207` |
| GET | `/api/router/agent/workspace/{sessionId}/read?path=` | — | `ResultVo<WorkspaceFileContent{content,truncated,size}>` | `harnax-webui/src/services/ant-design-pro/workspace.ts:56-69`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:212-221`、`harnax-webui/src/services/ant-design-pro/typings.d.ts:351-355` |
| GET | `/api/router/agent/workspace/status?sessionIds=<id>` | 逗号拼接可批量 | `ResultVo<Map<String,WorkspaceStatus>>`（前端取单值） | `harnax-webui/src/services/ant-design-pro/workspace.ts:74-92`、`:98-110`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:226-241`（空 sessionIds → error）、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:246-256`（单会话）、`harnax-webui/src/services/ant-design-pro/typings.d.ts:357-364` |
| POST | `/api/router/agent/workspace/{sessionId}/upload` | FormData `file` + `path`，headers 仅 `X-Api-Key` + skipAuthorization | `ResultVo` | `harnax-webui/src/services/ant-design-pro/workspace.ts:115-138`、`harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:153-174`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:261-273` |
| GET | `/api/router/agent/workspace/{sessionId}/download?path=` | **裸 fetch，仅 `X-Api-Key`** | 二进制 + `Content-Disposition`；>50MB→413；不存在→404 | `harnax-webui/src/services/ant-design-pro/workspace.ts:143-165`、`harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:279-304`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:291-315`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:41`、`:281-286` |

### userId 语义

- `resolveUserId`：**已登录用户会覆盖 body 里的 userId**，客户端传值不可信也不必要。锚点 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:50-70`。iOS 可不传。

---

## iOS 适配注意点

1. **SSE 解析器**：用 `URLSession.bytes(for:)` + `for try await line in bytes.lines` 无法直接得到「不完整末行」语义；建议自持 `Data` buffer，按 `\n` 切分并把末段留buffer，与 webui 一致（`harnax-webui/src/pages/session/components/ChatWindow.tsx:1380-1394`）。注意只处理 `data:` 前缀行，忽略 `event:`/`id:`/注释行（webui 同样如此）。
2. **解码必须 UTF-8 增量**：`String(decoding: bytes, as: UTF8.self)` 追加到文本 buffer，避免多字节字符跨 chunk 撕裂。
3. **事件建模**：`eventType` 作为判别键做 `Codable` 多态解码（对照 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:18-32`），未知 eventType 容错忽略（webui 因 switch-default 天然忽略）。
4. **确认弹窗阻塞读流**：Swift 侧用 `CheckedContinuation` 复刻「await UI 决策」，注意 continuation 逃逸与视图销毁时的 resume（webui 靠 finally 清 `answerMemberConfirmRef`，`harnax-webui/src/pages/session/components/ChatWindow.tsx:2375-2395`）。若采用非阻塞实现（确认期间仍继续读流并缓冲），会造成与 webui 可见的行为差异，须在 spec 评审时明确取舍。
5. **`isLast` 丢弃语义**：`TextEvent`/`ThinkingEvent` 的 `isLast=true` 帧不可作为内容来源（`harnax-webui/src/pages/session/components/ChatWindow.tsx:1422-1428`）。这是最容易写错的一条。
6. **KeepAlive 显式丢弃**：包括带 `childRunId` 的成员 KeepAlive（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamOrchestrator.kt:427`），否则会产生空成员气泡。
7. **超时设计**：客户端 idle 超时应 > 120s（router idle `harnax-session-router/src/main/resources/application.yml:112`），整体上限可对齐 30min（`:113`、`:20-22`）；iOS `URLSessionConfiguration.timeoutIntervalForRequest` 需相应放宽，`timeoutIntervalForResource` ≥ 30min。
8. **自动滚动**：复刻 120px「接近底部」阈值与浮钮；`ScrollView` 上用内容高度差判断，不要用 `withAnimation` 打断用户手势。
9. **图片输入**：`PhotosPicker` → 读取为 JPEG/PNG Data → 拼 `data:image/<mime>;base64,<b64>`（与 `FileReader.readAsDataURL` 输出等价，`harnax-webui/src/pages/session/components/ChatWindow.tsx:2442-2468`）。**绝不可传 http URL**（后端会把非 `data:image` 串当本地路径并硬编码 image/png，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt:814-829`）。同时注意 base64 体积直接进 body，应限制单图尺寸。
10. **下载**：两处裸 fetch（workspace download 只 `X-Api-Key`；team-artifact download 只 `Bearer`）在 iOS 各自构造 `URLRequest` 并只加对应一个头，别统一加全家桶（尤其别给 router download 加 Bearer 之外的 Tenant 头，行为未定义）。
11. **复制**：整条气泡与代码块都由长按菜单交给 `HXPasteboard.copy`（`Sources/HarnaxFeatures/Chat/ChatTranscriptRows.swift:704`）。气泡文案由 `ChatTurn.copyableText` 组装（`Sources/HarnaxFeatures/Chat/ChatTranscript.swift:229`）：按流式顺序取 text 段、以 `\n` 相连，thinking、工具卡、附件、计划块一律不进剪贴板；组装结果为空就不弹菜单。webui 只有代码块一个复制入口（`harnax-webui/src/pages/session/components/ChatWindow.tsx:180-184`）。
12. **Markdown**：气泡正文由本仓的渲染器画——块结构 `Sources/HarnaxKit/Components/HXMarkdownParser.swift`，绘制 `Sources/HarnaxKit/Components/HXMarkdownText.swift`；GFM 表格、任务列表、两种围栏、引用、标题、行内样式与链接都在覆盖内。iOS 17 的 `AttributedString(markdown:)` 只吃行内语法，单独用它会把表格和围栏塌成一行平文，所以解析层不可省。会话侧的接线、围栏底色档与「正文不可选中 / 不做语法高亮」两条取舍见「分段渲染规格 · text 渲染（Markdown）」。
13. **状态默认值一致性清单**：thinking 默认展开（`harnax-webui/src/pages/session/components/ChatWindow.tsx:211-228`）、工具卡默认折叠但 busy 自动展开（`:305-307`）、主管确认默认展开参数表（`:538-548`）、成员内联确认默认展开（`:422`）、历史计划 Collapse 只展开最新一条（`:3041-3290`）。iOS 逐个对齐。
14. **列表一次 100 条**：iOS 首版同样一次拉 100 且不实现搜索/排序/分组，避免与后端能力错配。锚点 `harnax-webui/src/pages/team/index.tsx:39`。
15. **创建后无法拿到新会话 id**：必须重拉列表（`harnax-webui/src/pages/session/components/SettingsModal.tsx:107` + `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:80-89`）。
16. **team 与 agent 互斥用字符串编码**：`"agent:<id>"` / `"team:<id>"` 是 webui 的 UI 层编码（`harnax-webui/src/pages/session/components/SettingsModal.tsx:92-94`），iOS 可用 enum 内部表示，但提交体必须是 agentId/teamId 二选一。
17. **权限模式与能力开关都走 `/command`**，不要用 `PUT /config`（webui 从不调用，且 `/command` 侧有 `task-` 会话与 thinkingMode=2 保护，`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:952-954`、`:961-970`、`:1025-1027`）。
18. **本地化**：webui 依赖 `umi` 的 `useIntl`（`pages.session.*` 文案，含 `permissionMode.<MODE>` 与工具确认相关）；iOS 用 `String(localized:)` 且需保留 `zh-Hans`/`en` 两套，Accept-Language 语义参考 `harnax-webui/src/requestErrorConfig.ts:64-65`。
19. **危险视觉语言**：`isDangerous` 在主管弹窗为「高危(红)/低危(绿)」两态（`harnax-webui/src/pages/session/components/ChatWindow.tsx:485-505`），在成员内联卡只有「高危」红 Tag（`:444`）——两处不一致，iOS 按各自位置照做。
20. **不要实现「始终允许」**：`alwaysAllow` 前端不传、后端忽略（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:366-371`）。

---

## 未确认

1. **已核实**：`EndEvent.attachments: List<FileAttachment>`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:145-151`）在 webui 完全未渲染。类型定义在 `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/FileAttachment.kt:14-22`，七个字段：`fileId`（下载 URL 的路径段）、`fileName`、`filePath`（沙箱内路径）、`fileSize`（字节）、`mimeType`、`url`（WebUI 用的稳定下载地址）、`objectKey`（MinIO 对象键，缺省空串）。下载走 `GET /api/output-files/{sessionType}/{sessionId}/{fileId}?name=`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/OutputFileController.kt:66-73`），但整控制器带 `@ConditionalOnProperty(prefix = "minio", name = ["enabled"], havingValue = "true")`（`:38`）——MinIO 未开启时该路由不存在，iOS 的附件收取要先按 404 处理。`sessionType` 只接受 `web`/`task`/`channel`，`fileId` 必须是 UUID，`channel` 类型要求渠道归属当前用户（`:51-64`）。
2. **已核实**：`MessageSegment.type === 'tool_result'`（`harnax-webui/src/pages/session/components/ChatWindow.tsx:62`）与 `toolResultExpanded`（`:72`）都是遗留。全仓仅 `harnax-webui/src/pages/session/components/ChatWindow.tsx` 引用这两个名字，共四处：类型联合 `:62`、历史映射注释 `:926`、渲染分支 `:2771`-`:2773`（该分支 `return null`）；`toolResultExpanded` 声明后无任何读写点。iOS 建模可以省略这两个字段。
3. `harnax-webui/src/pages/session/components/teamRun.test.ts`（约 2795B）未读，`teamRun` 纯函数的边界用例未纳入规格。`ChatWindow.less` 的气泡段已逐行读入 §气泡容器（`:245-284`：宽上限 78%、用户/主管/成员三套圆角与内边距、成员左侧 3px 色带），该文件其余部分（颜色变量 `--vip-*` 的取值、间距标度、非气泡卡片的边框）仍未纳入规格。
4. **已核实**：`GET /api/admin/sessions/{sessionId}/config` 返回 `ResultVo<SessionResponse>`，读不到会话时是 `code = 404` 而不是异常（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:111`-`:126`）；`PUT` 同路径返回 `ResultVo<Void>`，成功只有信封没有体（`:128`-`:141`）。因此 iOS 改完配置后必须重读一次才能拿到新值，不能从 PUT 响应里取。
5. `/api/router/agent/session/{sessionId}/plans` 与 `/current-plan` 返回体的 JSON 具体键名（`planId` 是否在响应顶层、`subtasks` 是否可能为 null）只由前端 TS 类型（`harnax-webui/src/pages/session/components/ChatWindow.tsx:110-132`）与前端消费代码推断，未核对后端 PlanNote DTO 的 Jackson 序列化配置（如字段名策略）。
6. `listWorkspaceFiles` 对 `type === 'symlink' | 'unknown'` 的图标/交互在 `harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx` 中的具体分支未逐行引用（只确认排序与目录优先）。
7. webui 主管确认在「成员 run 也同时 pending」时是否存在两个确认 UI 竞争（`answerMemberConfirmRef` 单一引用，`harnax-webui/src/pages/session/components/ChatWindow.tsx:606`）：只从代码结构看是「成员内联卡不阻塞、主管弹窗阻塞」，但未实跑验证并发场景，iOS 需以实测为准。
8. `REFRESH`/`DENY` 未在 webui slash 表（`harnax-webui/src/pages/session/components/ChatWindow.tsx:946-956`）：结论可靠。`handleApproveOrDeny`（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:998-…`）的完整分支本次只读了入口与行号范围，未逐行读完其内部实现细节（如是否写 alwaysAllow 缓存）。`COMPACT` 已在真栈跑通并落在上一节的 `handleCompact` 锚点，不再是未验证项。
