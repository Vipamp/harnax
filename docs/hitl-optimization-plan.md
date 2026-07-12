# HITL (Human-in-the-Loop) 流程优化

## 设计概述

### 核心思路

HITL 让 Agent 在执行危险工具前暂停，等用户确认后再继续。两条确认路径：

| 场景 | 交互方式 | 确认入口 |
|------|---------|----------|
| **Web session** | 流式 SSE，前端渲染确认卡片 | 用户点击按钮 → `ChatController /ai/confirm` |
| **Channel session** (飞书/微信) | 非流式纯文本 | 用户回复 `/approve` 或 `/deny` 命令 |
| **Task session** | 无人值守 | `permissionMode=BYPASS`，跳过所有确认 |

### 权限模型（已就绪，无需修改）

| 配置项 | 位置 | 默认值 |
|--------|------|--------|
| Session.permissionMode | Session 实体 | `DEFAULT` |
| Channel.permissionMode | Channel 实体 | `DEFAULT` |
| Task session | InternalApiController.resolveFromTask() | `BYPASS`（强制） |
| Tool.needConfirm | 工具元数据（Int: 0/1） | 由工具定义决定 |

**构建时注入链路**：
`Session/Channel.permissionMode` → `AgentSpecInfoResponse.permissionMode` → `ChatSpec.permissionMode` → `HarnessAgentWrapper.permissionMode`
`Tool.needConfirm=1` → `HarnessAgentLauncher.createAgentBase()` 收集 `needConfirmedTools` → `HarnessAgentWrapper.dangerousTools`

### agentscope-java 两次 call 模型

1. **第一次 call** → Agent 检测到危险工具 → 发射 `RequireUserConfirmEvent(replyId, toolCalls)` + `RequestStopEvent(PERMISSION_ASKING)` → 暂停
2. **第二次 call** → 调用方构造 `Msg` 携带 `metadata["agentscope_confirm_results"] = List<ConfirmResult>` → 恢复执行
3. `ConfirmResult(confirmed, toolCall, rules)` 支持 per-tool 决策 + 附加 `PermissionRule`（始终允许）

### 两条确认链路（重要）

agent-service 中有 **两个 confirm 入口**，都需修复：

| 链路 | Controller | DTO | Runner |
|------|------------|-----|--------|
| Web 直连 | `ChatController /ai/confirm` (JWT 认证) | `ConfirmRequest` | `ChatService.confirm()` |
| Router 代理 | `AgentController /api/agent/confirm` (内部认证) | `ConfirmAgentRequest` | `DefaultAgentRunner.confirm()` |

Channel 确认走 Router 链路：`Channel → RouterClient → Router /api/router/agent/confirm → AgentController → DefaultAgentRunner`

---

## 现状：3 处断裂点

| 断裂点 | 位置 | 问题 |
|--------|------|------|
| 事件转换 | `ChatEventConverter.kt` | `REQUIRE_USER_CONFIRM` 落入 `else -> Flux.empty()`，ToolConfirmChatEvent 从未发射 |
| 确认回传 | `DefaultAgentRunner.confirm()` + `ChatService.confirm()` | 两处 confirm 都未构造 agentscope 要求的 `ConfirmResult` + `METADATA_CONFIRM_RESULTS` metadata |
| Channel 层 | `RouterAgentAdaptor.convertChatEvent()` | `else -> null` 跳过所有工具事件，Channel 无法感知 HITL |

---

## Phase 1: 修复基础链路 — Web 端 HITL（P0）

> 目标：让 Web session 的确认流程完整跑通

### 1.1 ChatEventConverter — 处理 REQUIRE_USER_CONFIRM 事件

**文件**: `harnax-protocol/.../ChatEventConverter.kt`

在 `convert()` 的 `when` 中新增 `REQUIRE_USER_CONFIRM` 分支，将 agentscope 的 `RequireUserConfirmEvent` 转换为 harnax 的 `ToolConfirmChatEvent`：

```kotlin
AgentEventType.REQUIRE_USER_CONFIRM -> {
    val confirmEvent = event as RequireUserConfirmEvent
    val pendingTools = confirmEvent.toolCalls.map { toolUse ->
        PendingCallTool(
            toolId = toolUse.id,
            toolName = toolUse.name,
            arguments = convertInput(toolUse.input),
            isDangerous = toolUse.name in dangerousTools,
        )
    }
    Flux.just(ToolConfirmChatEvent(pendingCallTools = pendingTools, tokenUsage = null))
}
```

### 1.2 协议层扩展 — per-tool 决策

**文件 1**: `harnax-protocol/.../AgentRequest.kt` — `ConfirmAgentRequest` 新增 `toolResults`

```kotlin
data class ConfirmAgentRequest(
    override val sessionId: String,
    val isConfirmed: Boolean,           // 保留兼容：全部通过/全部拒绝
    val toolInfoList: List<ToolInfo> = emptyList(),
    val toolResults: List<ToolConfirmResult> = emptyList(),  // 新增：逐个工具决策
) : AgentRequest()

data class ToolConfirmResult(
    val toolId: String,
    val toolName: String,
    val confirmed: Boolean,
    val alwaysAllow: Boolean = false,  // 是否添加 PermissionRule
)
```

**文件 2**: `harnax-agent-service/.../dto/ConfirmRequest.kt` — Web DTO 同步新增 `toolResults`

```kotlin
data class ConfirmRequest(
    val sessionId: String,
    val isConfirmed: Boolean = false,
    val toolInfoList: List<ToolInfo> = listOf(),
    val toolResults: List<ToolConfirmResult> = listOf(),  // 新增
    val enableThink: Boolean = false,
    val enableSearch: Boolean = false,
)
```

### 1.3 HarnessAgentWrapper — 缓存 pendingToolCalls

**文件**: `harnax-harness-core/.../HarnessAgentWrapper.kt`

在 `callStreamInternal()` 的事件流中拦截 `RequireUserConfirmEvent`，缓存 toolCalls 供后续 confirm 使用：

```kotlin
@Volatile
private var pendingToolCalls: List<ToolUseBlock> = emptyList()

fun getPendingToolCalls(): List<ToolUseBlock> = pendingToolCalls

// 在 callStreamInternal() 的 .doOnNext 中拦截 AgentEvent（需要改为先拦截再转换）：
.doOnNext { agentEvent ->
    if (agentEvent is RequireUserConfirmEvent) {
        pendingToolCalls = agentEvent.toolCalls
    }
}
.flatMap { agentEvent -> ChatEventConverter.convert(agentEvent, dangerousTools) }
```

> 注意：拦截点必须在 `flatMap` **之前**，因为 `flatMap` 后已经变成了 `ChatEvent`，丢失了原始的 `AgentEvent`。
> 当前代码中 `.doOnNext { extracted(it) }` 是在 `flatMap` 之后，作用于 `ChatEvent`，不能复用。
> 需要在 `harnessAgent.streamEvents()` 返回的原始 `Flux<AgentEvent>` 上加拦截。

### 1.4 confirm() — 两处都构造 ConfirmResult metadata

**文件 1**: `harnax-agent-service/.../DefaultAgentRunner.kt` (Router 链路)

当前 confirm 方法：`isConfirmed=true` → `agent.callStream()`（空 msg），`isConfirmed=false` → 构造 ToolResultBlock("cancelled")。
需改为构造 agentscope 期望的 `ConfirmResult` metadata：

```kotlin
override fun confirm(request: ConfirmAgentRequest): Flux<ChatEvent> {
    val sessionId = request.sessionId
    val agent = agentCache.getIfPresent(sessionId) ?: getOrCreateAgent(sessionId, UserIdentifier(0))
    val pendingToolCalls = agent.getPendingToolCalls()

    // 构造 per-tool ConfirmResult 列表
    val confirmResults = if (request.toolResults.isNotEmpty()) {
        request.toolResults.map { tr ->
            val toolUseBlock = pendingToolCalls.find { it.id == tr.toolId }
            ConfirmResult(tr.confirmed, toolUseBlock, null)
        }
    } else {
        // 兼容：全部通过或全部拒绝
        pendingToolCalls.map { ConfirmResult(request.isConfirmed, it, null) }
    }

    val msg = Msg.builder()
        .name("user").role(MsgRole.USER)
        .textContent("[confirm]")
        .metadata(mapOf(Msg.METADATA_CONFIRM_RESULTS to confirmResults))
        .build()
    return agent.callStream(msg = msg)
        .doOnSubscribe { ... }
        .doFinally { ... }
}
```

**文件 2**: `harnax-agent-service/.../ChatService.kt` (Web 直连链路)

同样的问题，`ChatService.confirm(ConfirmRequest)` 需同步修复。区别在于：
- 入参是 `ConfirmRequest` DTO（不是 `ConfirmAgentRequest`）
- 需从 `ConfirmRequest.toolResults` 读取 per-tool 决策
- 其余构造 ConfirmResult metadata 逻辑完全相同

> **注意**：两处 confirm 共享相同的 `HarnessAgentWrapper`，`getPendingToolCalls()` 只需实现一次。

---

## Phase 2: Channel 纯文本确认交互（P1）

> 目标：让飞书/微信等 Channel 通过 `/approve` 命令完成工具确认
>
> Channel 是非流式的纯文本交互，无法渲染确认卡片，需要：
> 1. 将待确认工具转换为纯文本消息发送给用户
> 2. 用户通过 `/approve` 或 `/deny` 命令回复确认

### 2.1 AgentStreamEvent — 新增 ToolConfirmStreamEvent

**文件**: `harnax-channel/harnax-channel-sdk/.../AgentStreamEvent.kt`

```kotlin
data class ToolConfirmStreamEvent(
    val pendingTools: List<PendingToolInfo>,
) : AgentStreamEvent()

data class PendingToolInfo(
    val toolId: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    val isDangerous: Boolean,
)
```

> **注意**：`AgentStreamEvent` 是 sealed class，新增子类不影响现有 when 表达式的编译安全性。
> 但 `ChannelChatService.streamAndSend()` 中的 `when(event)` 需要新增分支处理。

### 2.2 RouterAgentAdaptor — 转换 ToolConfirmChatEvent

**文件**: `harnax-channel/harnax-channel-service/.../RouterAgentAdaptor.kt`

`convertChatEvent()` 新增分支（当前 `else -> null` 跳过了所有工具事件）：

```kotlin
is ToolConfirmChatEvent -> AgentStreamEvent.ToolConfirmStreamEvent(
    pendingTools = event.pendingCallTools.map {
        PendingToolInfo(it.toolId, it.toolName, it.arguments, it.isDangerous)
    }
)
```

### 2.3 PendingConfirm 状态管理

**存储位置**: `ChannelManager`（channel-service 层），而非 `ChannelSessionManager`（SDK 层）

理由：PendingConfirm 是 channel HITL 的实现细节，不属于 session 管理的通用抽象。
放在 `ChannelManager` 避免污染 SDK 接口，且 `ChannelManager` 已持有 `routerClient` 和 `sessionManager`。

```kotlin
// ChannelManager 内部
data class PendingConfirm(
    val sessionId: String,
    val pendingTools: List<PendingToolInfo>,
    val createdAt: Long = System.currentTimeMillis(),
)

private val pendingConfirms = ConcurrentHashMap<String, PendingConfirm>()
```

### 2.4 ChannelChatService — 处理确认事件 + 发送确认文本

**文件**: `harnax-channel/harnax-channel-sdk/.../ChannelChatService.kt`

在 `streamAndSend()` 的 when 分支中新增处理 `ToolConfirmStreamEvent`：

```kotlin
is AgentStreamEvent.ToolConfirmStreamEvent -> {
    // 1. 构造纯文本确认消息
    val confirmText = buildConfirmText(event.pendingTools)
    // 2. 发送给用户
    channelAdaptor.sendMessage(channel, message.sessionId, confirmText)
    // 3. 通过回调通知上层保存 PendingConfirm 状态
    onPendingConfirm?.invoke(message.sessionId, event.pendingTools)
    // 4. 保存 AI 确认提示消息到历史
    saveAssistantMessage(message, confirmText, channel)
}
```

确认文本格式：
```
⚠️ AI 需要执行以下工具，请确认后继续：

1. execute [高危] — command: "rm -rf /tmp/data"
2. read_file — path: "/etc/config.yaml"

请回复 /approve 确认执行，或 /deny 拒绝执行。
```

> **约束**：Channel HITL 仅在 `streamAndSend()` 路径下生效。
> `batchSend()` 路径调用的是非流式 `process()` → `agent-service /api/agent/chat`，
> 返回的是 `ChatResponse`（纯文本），丢失了 `ToolConfirmChatEvent`。
> 当前飞书/微信的 `shouldUseStreaming()` 均返回 `true`，因此此约束不影响已知渠道。
> 若未来新增非流式渠道需要 HITL，需扩展 `batchSend()` 或改用流式路径。

**ChannelChatService 构造器**需新增可选回调：

```kotlin
open class ChannelChatService(
    protected val sessionManager: ChannelSessionManager,
    protected val onPendingConfirm: ((sessionId: String, tools: List<PendingToolInfo>) -> Unit)? = null,
)
```

### 2.5 /approve 命令拦截处理

**拦截点**: `ChannelManager.handleMessage()`（channel-service 层）

理由：
- `/approve` 已被 `CommandAgentRequest.parse()` 解析为 `CommandType.APPROVE`
- 如果走正常链路 → `RouterAgentAdaptor` → `Router /api/router/agent/command` → `DefaultAgentRunner.executeCommand(APPROVE)`，
  但 agent-service 不知道 channel 层的 pending confirm 状态
- 必须在 channel 层拦截，转换为 `ConfirmAgentRequest` 发送到 confirm 接口

**处理流程**：

```kotlin
// ChannelManager.handleMessage()
suspend fun handleMessage(message: ChannelMessage, channel: ChannelSpec) {
    val agentRequest = messageParser.parse(message)

    // ★ HITL 拦截：检查是否有 pending confirm + 命令是 /approve 或 /deny
    val pending = pendingConfirms[message.sessionId]
    if (pending != null && agentRequest is CommandAgentRequest) {
        when (agentRequest.command) {
            CommandType.APPROVE -> {
                pendingConfirms.remove(message.sessionId)
                val confirmRequest = ConfirmAgentRequest(
                    sessionId = message.sessionId,
                    isConfirmed = true,
                )
                // 通过 Router 发送到 agent-service confirm 接口
                handleConfirmResponse(message, channel, confirmRequest)
                return
            }
            CommandType.INTERRUPT -> {  // /deny 复用 INTERRUPT? 或新增 DENY 命令
                pendingConfirms.remove(message.sessionId)
                val confirmRequest = ConfirmAgentRequest(
                    sessionId = message.sessionId,
                    isConfirmed = false,
                )
                handleConfirmResponse(message, channel, confirmRequest)
                return
            }
            else -> { /* 其他命令正常走 agent */ }
        }
    }

    // 正常消息处理
    chatService.chat(message, channel, routerAgentAdaptor, channelAdaptor, agentRequest)
}
```

### 2.6 RouterClient — 新增 streamConfirm 方法

**文件**: `harnax-channel/harnax-channel-service/.../RouterClient.kt`

当前 `RouterClient.streamRequest()` 对 `ConfirmAgentRequest` 直接 `TODO()`，需补全：

```kotlin
fun streamRequest(request: AgentRequest, agentId: Long): Flow<ChatEvent> = when (request) {
    is ChatAgentRequest -> streamToAgent(...)
    is CommandAgentRequest -> { ... }
    is ConfirmAgentRequest -> streamConfirm(request, agentId)  // ★ 新增
    else -> TODO()
}

fun streamConfirm(request: ConfirmAgentRequest, agentId: Long): Flow<ChatEvent> {
    val url = "$routerUrl/api/router/agent/confirm"
    return webClient.post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .retrieve()
        .bodyToFlux(ChatEvent::class.java)
        .asFlow()
}
```

### 2.7 ChannelManager — 处理 confirm 响应

```kotlin
private suspend fun handleConfirmResponse(
    message: ChannelMessage,
    channel: ChannelSpec,
    confirmRequest: ConfirmAgentRequest,
) {
    val channelAdaptor = getAdaptor(channel.type)
    val fullContent = StringBuilder()

    routerClient.streamConfirm(confirmRequest, channel.agentId).collect { chatEvent ->
        when (chatEvent) {
            is StreamTextChatEvent -> fullContent.append(chatEvent.message)
            is EndEventChatEvent -> {
                channelAdaptor.sendMessage(channel, message.sessionId, fullContent.toString())
            }
            is ErrorChatEvent -> {
                channelAdaptor.sendMessage(channel, message.sessionId,
                    "[${chatEvent.code}] ${chatEvent.message}")
            }
            else -> { /* skip */ }
        }
    }
}
```

---

## Phase 3: 前端交互增强（P2，可选）

### 3.1 ToolConfirmCard — 逐个审批

**文件**: `harnax-webui/src/pages/session/components/ChatWindow.tsx`

当前：Modal 弹窗，仅"全部允许"/"全部拒绝"两个按钮。

优化为：
- 每个工具行增加"允许"/"拒绝"单独按钮
- 保留顶部"全部允许"/"全部拒绝"快捷操作
- 新增"始终允许此工具"勾选框（对应 `alwaysAllow: true`）
- 显示工具参数详情

```
┌─────────────────────────────────────────┐
│ AI 想调用以下工具，请确认是否允许执行：     │
│                                         │
│ [✓] execute (ShellExecuteTool)  [高危]   │
│     command: "rm -rf /tmp/data"         │
│     [允许] [拒绝] [始终允许]              │
│                                         │
│ [✓] read_file (FileReadTool)            │
│     path: "/etc/config.yaml"            │
│     [允许] [拒绝] [始终允许]              │
│                                         │
│         [全部拒绝]  [全部允许]            │
└─────────────────────────────────────────┘
```

### 3.2 confirm 接口请求体更新

前端发送 confirm 请求时携带 per-tool 决策：

```json
{
  "sessionId": "web-xxx",
  "isConfirmed": false,
  "toolResults": [
    { "toolId": "tc_1", "toolName": "execute", "confirmed": true, "alwaysAllow": false },
    { "toolId": "tc_2", "toolName": "read_file", "confirmed": false }
  ],
  "toolInfoList": [...]
}
```

---

## Phase 4: 体验增强（P3，可选）

### 4.1 超时自动拒绝
- 前端：ToolConfirmCard 增加倒计时（默认 120s），超时自动拒绝
- Channel：pending confirm 5 分钟超时自动拒绝（在 ChannelManager 中定时扫描）

### 4.2 PermissionRule 持久化
- 用户选择"始终允许"时，通过 `ConfirmResult.rules` 传递 `PermissionRule`
- agentscope 的 `PermissionEngine` 自动记忆，后续同一工具不再询问
- 作用域为当前 agent session 生命周期

### 4.3 Channel 端确认消息美化
- 飞书：使用 Interactive Card（按钮式交互卡片）代替纯文本
- 微信：纯文本（微信不支持交互卡片）

---

## 实施顺序

| 步骤 | 阶段 | 范围 | 改动文件 | 依赖 |
|------|------|------|---------|------|
| 1 | Phase 1.1 | ChatEventConverter | `ChatEventConverter.kt` | 无 |
| 2 | Phase 1.2 | 协议扩展 | `AgentRequest.kt`, `ConfirmRequest.kt` | 无 |
| 3 | Phase 1.3 | pendingToolCalls 缓存 | `HarnessAgentWrapper.kt` | 无 |
| 4 | Phase 1.4 | ConfirmResult 构造 | `DefaultAgentRunner.kt`, `ChatService.kt` | 步骤 2, 3 |
| 5 | Phase 2.1 | StreamEvent 扩展 | `AgentStreamEvent.kt` | 无 |
| 6 | Phase 2.2 | Adaptor 转换 | `RouterAgentAdaptor.kt` | 步骤 5 |
| 7 | Phase 2.3+2.4 | 确认文本 + PendingConfirm | `ChannelChatService.kt`, `ChannelManager.kt` | 步骤 5, 6 |
| 8 | Phase 2.5+2.6 | /approve 拦截 + streamConfirm | `ChannelManager.kt`, `RouterClient.kt` | 步骤 7 |
| 9 | Phase 3 | 前端交互 | `ChatWindow.tsx` | Phase 1 |

**实施策略**：Phase 1（Web HITL 跑通）→ Phase 2（Channel /approve 确认）→ Phase 3（前端增强，可选）

---

## 方案审查记录

以下是相比初版方案发现并修正的问题：

| # | 问题 | 初版描述 | 修正 |
|---|------|---------|------|
| 1 | **两条 confirm 链路未区分** | 只提到 `DefaultAgentRunner.confirm()` | 补充了 `ChatService.confirm()` (Web 直连链路)，两者都需修复 |
| 2 | **RouterClient.streamRequest() 缺失 ConfirmAgentRequest** | 未提及 | `streamRequest()` 中 `ConfirmAgentRequest` 分支为 `TODO()`，需新增 `streamConfirm()` 方法 |
| 3 | **/approve 拦截层级错误** | 初版建议在 MessageParser 中识别确认回复 | 改为在 `ChannelManager.handleMessage()` 中拦截 `CommandType.APPROVE`，因为 PendingConfirm 是 channel-service 层的概念，agent-service 不感知 |
| 4 | **PendingConfirm 存储位置不当** | 初版建议放在 ChannelSessionManager (SDK 接口) | 改为放在 `ChannelManager` (service 层)，PendingConfirm 是 HITL 实现细节，不属于 session 管理通用抽象 |
| 5 | **batchSend 路径无法感知 HITL** | 未提及 | `batchSend()` 调用非流式 `process()`，返回 `ChatResponse` 会丢失 ToolConfirmChatEvent。补充了约束说明：当前飞书/微信均走 streaming 路径，不受影响 |
| 6 | **HarnessAgentWrapper 拦截点位置** | 未区分拦截时机 | 拦截 `RequireUserConfirmEvent` 必须在 `flatMap` **之前**的原始 `Flux<AgentEvent>` 上，不能在转换后的 `Flux<ChatEvent>` 上 |
