# HITL (Human-in-the-Loop) 流程优化

## 现状分析

当前 HITL 链路存在 3 处断裂，导致确认流程**完全不可用**：

| 断裂点 | 位置 | 问题 |
|--------|------|------|
| 事件转换 | `ChatEventConverter.kt` | `REQUIRE_USER_CONFIRM` 落入 `else -> Flux.empty()`，ToolConfirmChatEvent 从未发射 |
| 确认回传 | `DefaultAgentRunner.confirm()` | 未构造 agentscope 要求的 `ConfirmResult` + `METADATA_CONFIRM_RESULTS` metadata |
| Channel 层 | `RouterAgentAdaptor.convertChatEvent()` | `else -> null` 跳过所有工具事件，Channel 无法感知 HITL |

**agentscope-java 期望的 HITL 协议**：
1. Agent 检测到危险工具 → 发射 `RequireUserConfirmEvent(replyId, toolCalls)` + `RequestStopEvent` → 暂停
2. 调用方构造 `Msg` 携带 `metadata[agentscope_confirm_results] = List<ConfirmResult>` → 第二次 `call(msgs)` 恢复
3. `ConfirmResult(confirmed, toolCall, rules)` 支持 per-tool 决策 + 附加 `PermissionRule`（始终允许）

---

## Phase 1: 修复基础链路（P0）

### 1.1 ChatEventConverter — 处理 REQUIRE_USER_CONFIRM 事件

**文件**: `harnax-protocol/src/main/kotlin/.../ChatEventConverter.kt`

在 `convert()` 的 `when` 中新增：

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

同时 `ToolCallStartEvent` 处理中补充 arguments 提取（目前始终为 `emptyMap()`）。

### 1.2 DefaultAgentRunner.confirm() — 正确构造 ConfirmResult

**文件**: `harnax-agent/harnax-agent-service/.../DefaultAgentRunner.kt`

当前 confirm 方法：
- `isConfirmed=true` → `agent.callStream()` (空 msg)
- `isConfirmed=false` → 构造 ToolResultBlock("cancelled")

需要改为构造 agentscope 期望的 `ConfirmResult` metadata：

```kotlin
override fun confirm(request: ConfirmAgentRequest): Flux<ChatEvent> {
    val agent = agentCache.getIfPresent(sessionId) ?: getOrCreateAgent(...)
    
    // 从 agent 的 pendingToolCalls 获取 ToolUseBlock（需要新增暴露方法）
    // 构造 List<ConfirmResult> 放入 Msg.metadata
    val confirmResults = request.toolResults.map { toolResult ->
        ConfirmResult(toolResult.confirmed, findToolUseBlock(toolResult.toolId))
    }
    val msg = Msg.builder()
        .name("user").role(MsgRole.USER)
        .metadata(mapOf(Msg.METADATA_CONFIRM_RESULTS to confirmResults))
        .build()
    agent.callStream(msg = msg)
}
```

### 1.3 ConfirmAgentRequest — 支持 per-tool 决策

**文件**: `harnax-protocol/.../AgentRequest.kt`

扩展 `ConfirmAgentRequest`，新增 `toolResults` 字段（per-tool 决策列表）：

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

### 1.4 HarnessAgentWrapper — 暴露 pendingToolCalls

**文件**: `harnax-agent/harnax-harness-core/.../HarnessAgentWrapper.kt`

新增方法获取 agentscope 内部的 pending ToolUseBlocks，供 confirm 时构造 ConfirmResult：

```kotlin
fun getPendingToolCalls(): List<ToolUseBlock> {
    return harnessAgent.pendingToolCalls ?: emptyList()
}
```

> 需要验证 agentscope `HarnessAgent` 是否有 API 暴露 pending tool calls；如果没有，需要在 `REQUIRE_USER_CONFIRM` 事件到达时缓存 toolCalls。

---

## Phase 2: 前端交互增强（P1）

### 2.1 ToolConfirmCard — 逐个审批

**文件**: `harnax-webui/src/pages/session/components/ChatWindow.tsx`

当前：Modal 弹窗，仅"全部允许"/"全部拒绝"两个按钮。

优化为：
- 每个工具行增加"允许"/"拒绝"单独按钮
- 保留顶部"全部允许"/"全部拒绝"快捷操作
- 新增"始终允许此工具"勾选框（对应 `alwaysAllow: true`）
- 显示工具参数详情（当前 `arguments` 为 `emptyMap()`，Phase 1 修复后将有真实数据）

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

### 2.2 confirm 接口请求体更新

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

## Phase 3: Channel 纯文本确认交互（P1）

### 3.1 AgentStreamEvent — 新增 ToolConfirmStreamEvent

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

### 3.2 RouterAgentAdaptor — 转换 ToolConfirmChatEvent

**文件**: `harnax-channel/harnax-channel-service/.../RouterAgentAdaptor.kt`

```kotlin
is ToolConfirmChatEvent -> AgentStreamEvent.ToolConfirmStreamEvent(
    pendingTools = event.pendingCallTools.map {
        PendingToolInfo(it.toolId, it.toolName, it.arguments, it.isDangerous)
    }
)
```

### 3.3 ChannelChatService — 处理确认事件

**文件**: `harnax-channel/harnax-channel-sdk/.../ChannelChatService.kt`

在 `streamAndSend()` 中处理 `ToolConfirmStreamEvent`：

1. 发送文本格式确认请求给用户：
```
⚠️ AI 需要执行以下工具，请回复"确认"或"拒绝"：

1. execute (高危) — command: "rm -rf /tmp/data"
2. read_file — path: "/etc/config.yaml"

回复格式：
- "确认" → 全部允许
- "拒绝" → 全部拒绝
- "确认 1, 拒绝 2" → 逐个决定
```

2. 等待用户下一条消息
3. 解析用户回复 → 构造 `ConfirmAgentRequest` → 调用 confirm 接口

### 3.4 Channel 确认等待机制

由于 Channel 是无状态的文本流，需要在 `ChannelChatService` 中引入 **pending confirm 状态**：

**方案**: 在 `ChannelSessionManager` 中记录 pending confirm 状态：
```kotlin
data class PendingConfirm(
    val sessionId: String,
    val channelId: String,
    val pendingTools: List<PendingToolInfo>,
    val createdAt: Long,
)
```

- 收到 `ToolConfirmStreamEvent` 时保存 pending confirm + 给用户发送确认消息
- 用户下一条消息到达时，`MessageParser` 检测到 pending confirm → 解析为确认/拒绝指令
- 超时（如 5 分钟无响应）→ 自动拒绝并通知用户
- Channel 层的 `ConfirmAgentRequest` 通过 `RouterClient` 发到 agent-service 的 confirm 接口

### 3.5 MessageParser — 识别确认回复

在 Channel 的 `MessageParser` 中新增逻辑：

```
if (session has pendingConfirm) {
    if message matches "确认/yes/y/同意/允许" → ConfirmAgentRequest(isConfirmed=true)
    if message matches "拒绝/no/n/取消/deny" → ConfirmAgentRequest(isConfirmed=false)
    if message matches "确认 1, 拒绝 2" → per-tool ConfirmAgentRequest
    else → 提示用户按格式回复
}
```

---

## Phase 4: 体验增强（P2，可选）

### 4.1 超时自动拒绝
- 前端：ToolConfirmCard 增加倒计时（默认 120s），超时自动拒绝
- Channel：pending confirm 5 分钟超时自动拒绝

### 4.2 PermissionRule 持久化
- 用户选择"始终允许"时，通过 `ConfirmResult.rules` 传递 `PermissionRule`
- agentscope 的 `PermissionEngine` 自动记忆，后续同一工具不再询问
- 作用域为当前 agent session 生命周期

### 4.3 Channel 端确认消息美化
- 飞书：使用 Interactive Card（按钮式交互卡片）代替纯文本
- 微信：纯文本（微信不支持交互卡片）

---

## 实施顺序

| 阶段 | 范围 | 改动文件 | 依赖 |
|------|------|---------|------|
| 1.1 | ChatEventConverter | `ChatEventConverter.kt` | 无 |
| 1.2 | ConfirmResult 构造 | `DefaultAgentRunner.kt` | 1.4 |
| 1.3 | 协议扩展 | `AgentRequest.kt` | 无 |
| 1.4 | pendingToolCalls 暴露 | `HarnessAgentWrapper.kt` | 无 |
| 2.1-2.2 | 前端交互 | `ChatWindow.tsx` | Phase 1 |
| 3.1 | StreamEvent 扩展 | `AgentStreamEvent.kt` | 无 |
| 3.2 | Adaptor 转换 | `RouterAgentAdaptor.kt` | 3.1 |
| 3.3-3.5 | Channel 确认流程 | `ChannelChatService.kt`, `MessageParser`, `ChannelSessionManager` | 3.1, 3.2 |
| Phase 4 | 增强体验 | 多文件 | Phase 1-3 |

**建议先实施 Phase 1 + Phase 2**（让 WebUI 的 HITL 跑通），Phase 3 作为第二步迭代。
