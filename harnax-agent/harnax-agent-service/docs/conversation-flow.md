# Harnax Agent Service 对话链路文档

## 整体架构

### 服务拓扑

系统由 3 个微服务 + 若干共享模块组成：

```
                    +------------------+
                    |  Channel Service |  (port 8083)
                    |  飞书/微信/企微   |
                    +--------+---------+
                             |
                             v
                    +--------+---------+
        HTTP -----> |  Session Router  |  (port 8081)
        Client      |  路由 + 负载均衡  |
                    +--------+---------+
                             |
                             v
                    +--------+---------+
                    |  Agent Service   |  (port 8082)
                    |  AI Agent 执行    |
                    +------------------+
```

两条入口链路，从 Router 开始完全一致：

```
链路 1 (Channel):  channel-service  -->  router  -->  agent-service
链路 2 (HTTP):     HTTP client      -->  router  -->  agent-service
```

### 模块划分

| 模块 | 职责 |
|------|------|
| `harnax-protocol` | 跨服务协议：AgentRequest、ChatEvent、ChatResponse、CommandResponse |
| `harnax-common` | 公共 DTO：ResultVo、HarnaxErrorCode、HarnaxException |
| `harnax-agent-utils` | 模型配置适配器（ChatModelConfig）、MCP 配置适配器 |
| `harnax-agent-core` | AgentSpec、ChatSpec、ReActAgentWrapper、Hook、Session、MessageLog |
| `harnax-harness-core` | HarnessAgentLauncher、HarnessAgentWrapper、沙箱/MinIO、Spring 自动配置 |
| `harnax-agent-service` | Agent 执行引擎：AgentController、DefaultAgentRunner、沙箱管理 |
| `harnax-session-router` | 会话路由：SessionRouterController、实例注册/心跳、负载均衡/故障转移 |
| `harnax-channel` | 渠道接入：飞书 WebSocket、微信长轮询、Webhook，消息标准化 |

---

## 请求类型（Protocol）

所有跨服务请求基于 `AgentRequest` 密封类，通过 `type` 字段区分：

| 类型 | 类 | 用途 |
|------|-----|------|
| `CHAT` | `ChatAgentRequest` | 发送对话消息（sessionId, message, imageUrls）|
| `COMMAND` | `CommandAgentRequest` | 执行命令：INTERRUPT / CLEAR / COMPACT / APPROVE |
| `CONFIRM` | `ConfirmAgentRequest` | 工具确认：isConfirmed, toolInfoList |

---

## Session Router — 会话路由层

### 端点列表

`@RestController @RequestMapping("/api/router")` (port 8081)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/router/agent/chat` | 代理非流式对话 |
| POST | `/api/router/agent/chat/stream` | 代理 SSE 流式对话 |
| POST | `/api/router/agent/command` | 代理命令执行 |
| POST | `/api/router/agent/confirm` | 代理工具确认（SSE 流式）|
| DELETE | `/api/router/agent/session/{sessionId}` | 代理清除会话 |
| GET | `/api/router/agent/chat/history/{sessionId}` | 代理获取历史消息 |
| GET | `/api/router/agent/session/{sessionId}/plans` | 代理获取计划历史 |
| GET | `/api/router/agent/session/{sessionId}/current-plan` | 代理获取当前计划 |
| POST | `/api/router/instance/register` | Agent 实例注册 |
| POST | `/api/router/instance/heartbeat` | Agent 实例心跳 |
| POST | `/api/router/instance/unregister` | Agent 实例注销 |
| GET | `/api/router/instance/list` | 列出健康实例 |
| GET | `/api/router/health` | Router 健康检查 |

### 路由流程

```
SessionRouterController
    |
    v
SessionRouterService
    |
    |-- 1. resolveInstance(sessionId)
    |       |-- sessionMappingService.getInstanceId(sessionId) -> MySQL session_mapping 表
    |       |-- 有绑定且实例健康 -> 使用该实例（会话粘性）
    |       |-- 无绑定或不健康:
    |       |     |-- instanceRegistry.getHealthyInstances() -> MySQL agent_instance 表
    |       |     |-- selectLeastLoadedInstance() -> 选择负载最低的实例
    |       |     +-- sessionMappingService.bindSession(sessionId, instanceId) -> 绑定
    |
    |-- 2. 构造 URL: http://{host}:{port}/api/agent/...
    |
    |-- 3. WebClient 代理请求到 agent-service
    |
    |-- 4. 成功: refreshActiveTime(sessionId), 返回响应
    |
    +-- 5. 失败: tryFailover()
              |-- sessionMappingService.rerouteSession(sessionId) -> 选择新实例
              +-- 重试一次
```

### 实例管理

```
agent_instance 表:  instance_id, host, port, status(UP/DOWN), last_heartbeat
session_mapping 表: session_id, instance_id, last_active_time

心跳检测: HeartbeatHealthChecker (@Scheduled 每 5s)
    |-- 检测超过 30s 未心跳的实例 -> 标记 DOWN
    +-- 将该实例的所有 session 批量迁移到健康实例
```

---

## Agent Service — Agent 执行引擎

### AgentController 端点列表

`@RestController @RequestMapping("/api/agent")` (port 8082)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/chat` | 非流式对话（收集事件后拼接返回 ChatResponse）|
| POST | `/api/agent/chat/stream` | SSE 流式对话（返回 Flux\<ChatEvent\>）|
| POST | `/api/agent/command` | 执行命令（返回 CommandResponse）|
| POST | `/api/agent/confirm` | 工具确认（SSE 流式，返回 Flux\<ChatEvent\>）|
| POST | `/api/agent/chat/interrupt/{sessionId}` | 中断活跃流 |
| DELETE | `/api/agent/session/{sessionId}` | 清除会话 |
| GET | `/api/agent/chat/history/{sessionId}` | 获取历史消息 |
| GET | `/api/agent/session/{sessionId}/plans` | 获取计划历史 |
| GET | `/api/agent/session/{sessionId}/current-plan` | 获取当前计划 |
| GET | `/api/agent/health` | 健康检查 |

所有端点通过 `AgentRunner` 接口委托给 `DefaultAgentRunner`。

### DefaultAgentRunner — 核心实现

```kotlin
@Service
class DefaultAgentRunner(
    private val launcher: HarnessAgentLauncher,
    private val sessionMapper: SessionMapper,
    private val skillMapper: SkillMapper,
    private val objectMapper: ObjectMapper,
) : AgentRunner
```

#### 关键数据结构

```
agentCache     = ConcurrentHashMap<sessionId, HarnessAgentWrapper>()   // Agent 对象缓存
activeStreams  = ConcurrentHashMap<sessionId, Subscription>()          // 活跃流追踪（用于中断）
```

#### Agent 创建与缓存

```
getOrCreateAgent(sessionId, userIdentifier)
    |
    +-- agentCache.computeIfAbsent(sessionId) { sid ->
          |
          |-- sessionMapper.selectBySessionIdAndStatus(sid, 1) -> DB 查 session 配置
          |
          |-- 构建 ChatSpec（enableThink/enableSearch/enablePlan 来自 DB）
          |
          |-- 构建 AgentSpec:
          |     |-- id, name, description, systemPrompt, modelId (from DB)
          |     |-- 解析 MCP 列表 (JSON 格式，从 DB 读取)
          |     +-- 解析 Skill 列表 (逗号分隔 ID，通过 skillMapper 加载)
          |
          +-- launcher.createSingleAgent(agentSpec, sessionId, chatSpec, ...)
        }
```

- 同一 sessionId 首次创建 agent，后续请求直接复用缓存实例
- `clearSession()` 时从缓存中移除

#### streamProcess 流式对话

```
streamProcess(ChatAgentRequest)
    |-- getOrCreateAgent(sessionId, userIdentifier)
    |-- agent.callStream(message, imageUrls)
    |     .doOnSubscribe { activeStreams[sessionId] = subscription }
    |     .doFinally { activeStreams.remove(sessionId) }
    +-- 异常时返回 ErrorChatEvent + EndEventChatEvent
```

#### confirm 工具确认

```
confirm(ConfirmAgentRequest)
    |-- agentCache[sessionId] 获取缓存的 agent（必须已存在）
    |-- isConfirmed=true:
    |     agent.callStream()  <- 无 msg，恢复暂停的 agent 继续执行
    +-- isConfirmed=false:
          构造 ToolResultBlock("Operation cancelled by user") for each tool
          agent.callStream(msg=cancelResult)  <- 将取消结果反馈给 agent
```

#### clearSession 清除会话

```
clearSession(sessionId)
    |-- interrupt(sessionId)              <- 取消活跃流
    |-- agentCache.remove(sessionId)      <- 移除 Agent 缓存
    +-- launcher.clearSession(sessionId)  <- 清理 session 数据
          |-- sessionManager.deleteSession()
          |-- planNoteAdaptor.deleteAll()
          +-- keepAliveSandboxManager?.destroy(sessionId)
```

---

## HarnessAgentLauncher.createAgentBase — Agent 构建过程

按顺序执行以下步骤：

### 第 1 步：初始化 HarnessAgentBuilder

```
HarnessAgentBuilder()
    .name(agentSpec.name)
    .description(agentSpec.description)
    .maxIters(harnessConfig.maxIters)
    .systemPrompt(agentSpec.systemPrompt)
    .reminder(harnessConfig.reminder)
    .workspace(harnessConfig.workspace)
    .stateStore(stateStore)                   <- AgentStateStore（MysqlAgentStateStore）
```

### 第 2 步：配置 Chat Model

```
ModelHelper.createChatModel(chatModelConfig)
    |-- DashScopeChatModel     (DashScope / 通义千问)
    |-- OpenAIChatModel        (OpenAI 兼容)
    +-- OllamaChatModel        (Ollama 本地)
```

### 第 3 步：注册 MCP 客户端

```
for each McpSpec in agentSpec.mcpServices:
    mcpConfigAdaptor.getConfig(mcpId)  -> McpConfig
    McpHelper.createMcpClient(config)  -> McpClient
    agentBuilder.addMcp(mcpClient)
```

### 第 4 步：注册工具

```
for each ToolSpec in agentSpec.toolSpecs:                 // 工具按 agent 绑定逐条装配，没有硬编码清单
    toolConfigAdaptor.getToolConfig(toolId) -> ToolConfig
    ToolConfig.status == 0 -> 跳过（管理员已停用）
    toolRegistry.createToolBoxInstance(beanName) -> ToolBox  // 同一 beanName 只注册一次
    toolBox.init(toolCallLogAdaptor, SessionMetaContext(agentId, sessionId, tenantId), userIdentifier)
    agentBuilder.addTool(toolBox)                          // addTool 装载该 ToolBox 的全部 @Tool 方法
    if toolConfig.needConfirm == 1 || toolSpec.needConfirm:
        needConfirmedTools.add(toolConfig.name)            // 用 @Tool.name，PermissionEngine 按名字匹配
// 收尾：ToolBox 里未被本 agent 授权的方法（未被选中的兄弟方法、被停用的方法）从 toolkit 移除
```

团队场景另挂角色工具：lead 用 `TeamLeadToolBox`，member 用 `TeamMemberToolBox`。

### 第 5 步：注册技能

```
for each SkillSpec in agentSpec.skills:
    skillAdaptor.getSkill(skillId) -> AgentSkill
    agentBuilder.addSkill(skill)
```

### 第 6 步：注册 Middleware 与权限规则

```
框架的执行拦截点是 `MiddlewareBase`；危险工具拦截不自建中间件，
交给内置的 PermissionEngine 按规则判定。

agentBuilder.addMiddleware(TokenStatsMiddleware(tokenStatAdaptor, tokenStatBuilder))
agentBuilder.addMiddleware(ProcessLogMiddleware())
    // 两次 addMiddleware 都是每次 build 新建实例：中间件把本轮归因（agent/session/tenant）
    // 存在字段里，共享实例会让并发会话互相改写对方的记账。

PermissionContextState 规则：
    ALLOW <- 框架自带工具（plan_enter/plan_write/plan_exit、todo_write、
             agent_spawn/agent_send/agent_list、task_output/task_list）+ 团队工具名
             没有显式 ALLOW，DEFAULT 模式下这些内部工具会被判 ASK 而卡住
    ASK   <- needConfirmedTools（工具名来自 @Tool.name）

@ToolMeta(dangerousInput = true) 的方法再包一层 DangerousInputCheckingTool，
在 checkPermissions() 里扫描字符串入参中的危险命令与路径；
已有 ASK 规则的工具跳过包装——ASK 在 checkPermissions 之前触发，包装是冗余的。
```

### 第 7 步：配置计划（Plan）

```
if chatSpec.enablePlan:
    agentBuilder.enablePlan(true)        <- 框架的 plan 模式开关
```

### 第 8 步：配置沙箱（Sandbox）

```
if 非主管 and harnessConfig.sandbox.enabled and snapshotSpec != null:
    |-- DockerFilesystemSpec
    |     .image(resolvedSandboxImage)   <- 选中 CLI 包时按包组合解析出的镜像，否则 harnessConfig.sandbox.image
    |     .workspaceRoot(...)  .environment(cliEnv)
    |     .isolationScope(harnessConfig.sandbox.isolationScope)  .snapshotSpec(snapshotSpec)
    |   -> agentBuilder.filesystem(dockerSpec)
    |-- DistributedStore.builder()
    |     .agentStateStore(stateStore)
    |     .baseStore(MinioBaseStore 或 InMemoryStore)
    |     .sandboxSnapshotSpec(snapshotSpec)
    |   -> agentBuilder.distributedStore(...)
    |
    +-- if keepAlive=true:
          保存 keepAliveSnapshotSpec 传给 HarnessAgentWrapper
          （不在 agent 层面注入，而是在 wrapper.callStreamInternal 中注入）

主管不装配 filesystem：它没有自己的工作区，框架自带的文件/shell/子代理工具一并关掉。

if 沙箱关闭 but MinIO 启用 and 非主管:
    RemoteFilesystemSpec(MinioBaseStore).isolationScope(SESSION)  <- 仅文件存储，无 Docker 隔离
```

### 第 9 步：构建 & 包装

```kotlin
val harnessAgent = agentBuilder.build()  // -> agentscope 的 HarnessAgent

return HarnessAgentWrapper(
    harnessAgent = agent,
    mcpClients = mcpClients,
    dangerousTools = needConfirmedTools + dangerousInputTools,
    sessionId = sessionId,
    keepAliveSandboxManager = keepAliveSandboxManager,
    keepAliveSnapshotSpec = snapshotSpec,
    sandboxImage = resolvedSandboxImage,
    sandboxEnv = cliEnv,
    sandboxWorkspaceRoot = harnessConfig.sandbox.workspaceRoot,
    sandboxNetwork = harnessConfig.sandbox.network,
    permissionMode = chatSpec.permissionMode,
    configuredPermissionContext = builtPermCtx,
    // 成员的产出由主管那条流负责，这两个成员侧传 null
    outputFileDetector = if (teamRole is TeamRole.Member) null else outputFileDetector,
    outputFileStore = if (teamRole is TeamRole.Member) null else outputFileStore,
)
```

---

## HarnessAgentWrapper.callStream — 核心流式引擎

### 入口方法

```kotlin
// 用户发消息
fun callStream(prompt: String, imageUrls: List<String>, options: CallOptions): Flux<ChatEvent>
    // 构建 Msg(TextBlock + ImageBlock[])
    // -> callStreamInternal(msg, options)

// 确认/拒绝工具调用
fun callStream(options: CallOptions, msg: Msg?): Flux<ChatEvent>
    // -> callStreamInternal(msg, options)
```

### callStreamInternal 详细流程

```
callStreamInternal(msg, options)
    |
    |-- 1. 构建 RuntimeContext
    |       RuntimeContext.builder()
    |           .sessionId(sessionId)
    |           .userId(userIdentifier.userId)
    |
    |-- 2. [keepAlive 模式] 注入外部沙箱
    |       if keepAliveSandboxManager != null:
    |           sandbox = keepAliveSandboxManager.getOrCreate(sessionId, ...)
    |           sandboxContext = SandboxContext.builder()
    |               .externalSandbox(sandbox)      <- Priority 1: 用户托管沙箱
    |               .client(DockerSandboxClient)
    |               .clientOptions(...)
    |               .build()
    |           ctxBuilder.put(SandboxContext::class.java, sandboxContext)
    |
    |       效果: agentscope 的 SandboxManager.release() 遇到 Priority 1 沙箱时
    |             直接 return，不调用 stop() 和 shutdown() -> 容器保持运行
    |
    |-- 3. 调用 agentscope ReAct 循环
    |       harnessAgent.stream(msg.toList(), options, runtimeCtx)
    |       |
    |       |  +------------------------------------------------------+
    |       |  |  agentscope 内部 ReAct 循环:                          |
    |       |  |                                                      |
    |       |  |  Reason (LLM 推理)                                   |
    |       |  |     |                                                |
    |       |  |  Reason 之后由 PermissionEngine 判 ALLOW/ASK/DENY    |
    |       |  |     |-- ASK -> 发 REQUIRE_USER_CONFIRM，本轮暂停     |
    |       |  |     +-- ALLOW/DENY 之外的裁决 -> 继续                |
    |       |  |     |                                                |
    |       |  |  Act (执行工具)                                      |
    |       |  |     |-- SandboxLifecycleMiddleware                   |
    |       |  |     |   |-- acquireForCall() -> 获取/创建沙箱        |
    |       |  |     |   +-- releaseForCall()                         |
    |       |  |     |       |-- Priority 1: 直接 return              |
    |       |  |     |       +-- 其他: stop + shutdown                |
    |       |  |     +-- 工具执行结果                                 |
    |       |  |     |                                                |
    |       |  |  Observe (观察结果)                                  |
    |       |  |     |                                                |
    |       |  |  每步之后框架自动把会话状态落 MySQL                  |
    |       |  |     |                                                |
    |       |  |  循环... 直到 LLM 不再产生工具调用                   |
    |       |  +------------------------------------------------------+
    |       |
    |       -> 产出 Flux<Event>
    |
    |-- 4. 事件转换
    |       .flatMap { ChatEventConverter.convert(it, dangerousTools) }
    |       |
    |       |-- REASONING 事件 ->
    |       |     |-- StreamTextChatEvent      (文本输出块, isLast)
    |       |     |-- StreamThinkingChatEvent   (思考过程块, isLast)
    |       |     +-- isLast=true 时:
    |       |           |-- 有危险工具 -> ToolConfirmChatEvent (待确认工具列表)
    |       |           +-- 无危险工具 -> CallToolChatEvent x N (每个工具一个)
    |       |
    |       +-- TOOL_RESULT 事件 ->
    |             +-- ToolResultChatEvent x N (每个工具结果一个)
    |
    |-- 5. Token 统计
    |       .doOnNext { event ->
    |           if event.tokenUsage != null:
    |               tokenStatAdaptor.saveTokenStat(...)
    |       }
    |
    |-- 6. [keepAlive 模式] 快照持久化
    |       .doFinally {
    |           if keepAliveSandbox != null:
    |               snapshot = keepAliveSandbox.state.snapshot
    |               if snapshot != null && snapshot.isPersistenceEnabled:
    |                   keepAliveSandbox.persistWorkspace()  -> tar 流
    |                       .use { archive -> snapshot.persist(archive) }  -> 上传 MinIO
    |       }
    |       注意: 只触发 persistWorkspace + persist，不调用 stop()
    |             容器状态不变，running 仍为 true
    |
    |-- 7. 追加结束事件
    |       .concatWith(Flux.just(EndEventChatEvent()))
    |
    +-- 8. 错误处理
            .onErrorResume { e ->
                ErrorChatEvent(code, message) + EndEventChatEvent()
            }
```

---

## ChatEventConverter 事件转换详情

```
agentscope Event                    ->    ChatEvent
------------------------------------------------------------------
REASONING + TextBlock               ->    StreamTextChatEvent(message, isLast=false)
REASONING + TextBlock (最后)        ->    StreamTextChatEvent(message, isLast=true)
REASONING + ThinkingBlock           ->    StreamThinkingChatEvent(message, isLast)
REASONING (isLast) + 普通工具       ->    CallToolChatEvent(toolId, toolName, arguments)
REASONING (isLast) + 危险工具       ->    ToolConfirmChatEvent(List<PendingCallTool>)
TOOL_RESULT                         ->    ToolResultChatEvent(toolId, toolName, message, success)
(手动追加)                          ->    EndEventChatEvent()
(异常)                              ->    ErrorChatEvent(code, message)
```

所有事件都携带 `TokenUsage`（inputTokens, outputTokens, totalTokens, costTime, timestamp）。

### ChatEvent 类型一览

| 事件类型 | 字段 | 说明 |
|----------|------|------|
| `StreamThinkingChatEvent` | message, isLast | 思考/推理文本块 |
| `StreamTextChatEvent` | message, isLast | 主文本输出块 |
| `ToolConfirmChatEvent` | List\<PendingCallTool\> | 危险工具待确认 |
| `CallToolChatEvent` | toolId, toolName, arguments | 普通工具调用 |
| `ToolResultChatEvent` | toolId, toolName, message, success | 工具执行结果 |
| `EndEventChatEvent` | - | 流终止标记 |
| `ErrorChatEvent` | code, message | 错误事件 |

---

## 工具确认流程

```
1. Agent 推理产出工具调用，PermissionEngine 按工具名命中 ASK 规则
       |
2. 框架发出 REQUIRE_USER_CONFIRM 事件，本轮停在 ASKING 状态（待确认调用随会话状态持久化）
       |
3. ChatEventConverter 把事件里的 toolCalls 转成 ToolConfirmChatEvent
   每个 PendingCallTool = toolId + toolName + arguments + isDangerous
   isDangerous = 工具名 ∈ dangerousTools（= needConfirmedTools + dangerousInput 包装的工具）
       |
4. 客户端展示确认 UI，POST /api/agent/confirm（对外统一经 Router 的 /api/router/agent/confirm）
   |-- toolResults 非空：按工具逐个裁决，toolId 对齐 ASKING 的 ToolUseBlock
   +-- toolResults 为空：bulk 模式，isConfirmed 一次性裁决全部待确认工具
       |
5. runner 把裁决列表写进 Msg.metadata[METADATA_CONFIRM_RESULTS]
   agent.callStream(msg) 恢复暂停的 agent 继续 ReAct 循环
   团队场景 request.childRunId 非空时走 confirmMemberRun：只登记裁决，
   被恢复的 member 输出仍回到 lead 那条流上。
```

等待期间流被切断（空闲超时、关页、进程退出）时，下一次订阅从持久化状态找回 ASKING 调用并重发同一张确认卡片；
若此时已无待确认工具，`/confirm` 返回 ErrorChatEvent 提示重发消息。

---

## 沙箱生命周期

### 普通模式 (keepAlive=false)

```
每次 agent 调用:
  SandboxLifecycleMiddleware.acquireForCall()
    -> SandboxManager.acquire() -> Priority 3/4: 从快照恢复或新建容器
  工具在沙箱中执行
  SandboxLifecycleMiddleware.releaseForCall()
    -> SandboxManager.release()
      -> sandbox.stop()       -> persistWorkspace() -> 上传 MinIO
      -> sandbox.shutdown()   -> docker stop + docker rm --force
```

### keepAlive 模式 (keepAlive=true)

```
首次调用:
  KeepAliveSandboxManager.getOrCreate(sessionId)
    -> new DockerSandbox(state)
    -> sandbox.start()  -> 创建并启动 Docker 容器
  callStreamInternal 注入 SandboxContext.externalSandbox(sandbox)
  SandboxLifecycleMiddleware.acquireForCall()
    -> SandboxManager.acquire() -> Priority 1: 直接使用外部沙箱
  工具在沙箱中执行
  SandboxLifecycleMiddleware.releaseForCall()
    -> SandboxManager.release() -> Priority 1: 直接 return（无 stop/shutdown）
  doFinally:
    -> sandbox.persistWorkspace() -> tar 流
    -> snapshot.persist(archive)  -> 上传 MinIO
    （容器继续运行）

后续调用:
  KeepAliveSandboxManager.getOrCreate(sessionId)
    -> computeIfAbsent -> 返回缓存的同一 DockerSandbox 实例
  （复用同一个容器，workspace 数据还在）

clearSession 时:
  KeepAliveSandboxManager.destroy(sessionId)
    -> sandbox.close() -> stop() + shutdown() -> 容器销毁
```

---

## Session 管理

### Session 存储

```
SessionLoader.load(MysqlSessionConfig) -> MysqlAgentStateStore -> session_record 表
框架在每步 ReAct 之后自动把会话状态写入 session_record；harnessConfig.enableSessionPersistence=false 时关掉
```

### Agent 缓存

```
DefaultAgentRunner.agentCache = ConcurrentHashMap<sessionId, HarnessAgentWrapper>()
同一 sessionId 首次创建 agent，后续请求直接复用。clearSession() 时移除。
```

### 消息历史

```
launcher.loadSessionMessages(sessionId) -> List<Msg>
MessageLogConverter.convert() -> List<MessageLog>
    |-- SystemMessageLog
    |-- UserMessageLog
    |-- AssistantMessageLog
    +-- ToolResultMessageLog
```

---

## Channel Service — 渠道接入层

### 支持的渠道与通信模式

| 渠道 | 通信模式 | 接入方式 |
|------|----------|----------|
| 飞书 (Feishu) | WebSocket | Lark SDK 长连接，daemon 线程 |
| 飞书 (Feishu) | Webhook | HTTP 回调，HmacSHA256 签名校验 |
| 微信 (WeChat) | 长轮询 | iLink Bot SDK，轮询 getUpdates |

### 消息处理流程

```
[外部平台: 飞书/微信]
    |
    | WebSocket / 长轮询 / Webhook
    v
[ChannelAdaptor] (FeishuAdaptor / WechatAdaptor)
    |-- 消息去重（ messageId 追踪）
    |-- 图片下载 -> base64 data URL
    +-- 转换为统一的 ChannelMessage
    |
    v
[MessageParser] (FeishuMessageParser / DefaultParser)
    |-- 去除 @提及
    |-- 检测 "/" 命令 -> CommandAgentRequest (CLEAR/INTERRUPT)
    +-- 普通消息 -> ChatAgentRequest
    |
    v
[ChannelChatService.chat()]
    |-- 读取 ChannelSessionManager 中的会话历史
    |-- 保存用户消息
    +-- 构建 AgentContext
    |
    v
[RouterAgentAdaptor]
    |
    v
[RouterClient] (WebClient -> session-router)
    |
    | POST http://localhost:8081/api/router/agent/chat/stream
    | Body: ChatAgentRequest
    v
[Session Router -> Agent Service -> AI 响应]
    |
    v
[ChatEvent -> AgentStreamEvent 转换]
    |-- TextStreamEvent, ThinkingStreamEvent
    |-- EndStreamEvent, ErrorStreamEvent
    |
    v
[批量发送] (飞书/微信均不支持流式输出)
    |-- 发送 "正在输入..." 状态
    |-- 缓冲所有文本事件
    +-- EndStreamEvent 时一次性发送完整回复
    |
    v
[用户收到 AI 回复]
```

---

## Spring 配置链路

```
application.yml (agent-service)
    +-- harness:
          max-iters: 30
          sandbox:
            enabled: true
            image: python:3.11-slim
            workspace-root: /workspace
            isolation-scope: SESSION
            keep-alive: false
          minio:
            endpoint: http://localhost:9000
            ...
                |
                v
HarnessAutoConfiguration (@Configuration)
    |-- SandboxProperties -> SandboxConfig
    |-- MinioProperties -> MinioConfig
    |-- HarnessConfig (聚合)
    +-- @Bean HarnessAgentLauncher
          +-- initLauncher()
                |-- 创建 KeepAliveSandboxManager (if sandbox.enabled && sandbox.keepAlive)
                |-- SessionLoader.load(sessionConfig) -> AgentStateStore
                +-- MinIO bucket 初始化
```

---

## 端到端流程图

```
HTTP Client / Channel Service
  |
  | POST /api/router/agent/chat/stream {sessionId, message, imageUrls}
  v
Session Router (port 8081)
  |-- resolveInstance(sessionId)
  |     |-- session_mapping 表查绑定
  |     +-- 无绑定: agent_instance 表查健康实例 -> 绑定
  +-- WebClient -> http://{host}:{port}/api/agent/chat/stream
       |
       v
  Agent Service (port 8082)
    |
    +-- AgentController.chatStream()
         +-- DefaultAgentRunner.streamProcess()
              |
              |-- getOrCreateAgent(sessionId) -- ConcurrentHashMap cache
              |    |-- DB: sessionMapper.selectBySessionIdAndStatus()
              |    |-- Builds AgentSpec (name, model, MCP, skills from DB)
              |    +-- HarnessAgentLauncher.createSingleAgent()
              |         |-- HarnessAgentBuilder
              |         |    .name, .description, .maxIters, .systemPrompt, .model
              |         |    .addMcp(McpClient)       -- for each MCP service
              |         |    .addTool(ToolBox)         -- per agentSpec.toolSpecs, dedup by beanName
              |         |    .addSkill(AgentSkill)     -- from SkillAdaptor
              |         |    .addMiddleware(ProcessLogMiddleware)
              |         |    .addMiddleware(TokenStatsMiddleware)
              |         |    .permissionContext(...)   -- ALLOW framework tools, ASK needConfirm tools
              |         |    .enablePlan(true)         -- if chatSpec.enablePlan
              |         |    .filesystem(DockerFilesystemSpec) -- if sandbox enabled
              |         |    .distributedStore(DistributedStore)
              |         |    .stateStore(AgentStateStore)
              |         |    .build() -> HarnessAgent
              |         +-- Wraps in HarnessAgentWrapper
              |
              +-- agent.callStream(message, imageUrls)
                   |-- Builds RuntimeContext(sessionId, userId)
                   |-- Injects keepAlive sandbox if enabled
                   |-- harnessAgent.stream(msgs, options, ctx)
                   |    |
                   |    |  [agentscope ReAct loop: Reason -> Act -> Observe -> ...]
                   |    |  session persists after each step (switch: enableSessionPersistence)
                   |    |  ProcessLogMiddleware logs each lifecycle event
                   |    |  PermissionEngine ASK rule pauses tools needing confirmation
                   |    |
                   |    v
                   |-- ChatEventConverter.convert() -> Flux<ChatEvent>
                   |-- .doOnNext -> save token stats
                   |-- .doFinally -> persist keepAlive sandbox snapshot
                   |-- .concatWith(EndEventChatEvent)
                   +-- .onErrorResume -> ErrorChatEvent + EndEventChatEvent
                   |
                   v
              Flux<ChatEvent> (SSE)
                   |
                   v
              Session Router (SSE passthrough)
                   |
                   v
              HTTP Client / Channel Service
```
