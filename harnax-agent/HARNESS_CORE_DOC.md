# harnax-harness-core 模块文档

> Harnax Harness Core — 基于 agentscope-harness 的分布式智能体运行时模块，支持 Docker 沙箱、MinIO 远程存储和 MySQL 会话持久化。

## 1. 模块定位

`harnax-harness-core` 是 Harnax 平台的**核心智能体执行引擎**，基于 `agentscope-java` 的 `agentscope-harness` 子框架构建。  
它封装了完整的 Agent 生命周期管理：模型配置加载、工具/MCP/Skill 注册、中间件注入、沙箱隔离、分布式存储、会话持久化等能力。

与废弃的 `harnax-agent-core`（基于 `ReActAgent`）相比，`harness-core` 使用 `HarnessAgent`，原生支持分布式运行和多会话隔离。

---

## 2. 技术栈

| 技术 | 版本/说明 |
|------|---------|
| **语言** | Kotlin (JVM 21) |
| **智能体框架** | `agentscope-harness` 2.0.0 (传递依赖 `agentscope-core`) |
| **响应式** | Project Reactor (`Flux`) |
| **对象存储** | MinIO 8.5.17（S3 兼容） |
| **数据库** | MySQL（通过 HikariCP 连接池） |
| **JSON** | Jackson 3.x（`tools.jackson.*`） |
| **Spring 集成** | Spring Boot 4.x `AutoConfiguration` |

---

## 3. 目录结构

```
harnax-agent/harnax-harness-core/
└── src/main/kotlin/com/agnetix/harnax/
    ├── agent/                          # 通用 Agent 定义与配置
    │   ├── AgentSpec.kt               # Agent 规格（名称、Prompt、工具列表等）
    │   ├── ChatSpec.kt                # 单次对话规格（thinking/search/plan/权限）
    │   ├── adaptor/                   # 外部依赖适配器接口
    │   │   ├── ChatModelConfigAdaptor.kt
    │   │   ├── McpConfigAdaptor.kt
    │   │   ├── SkillAdaptor.kt
    │   │   ├── TokenStatAdaptor.kt
    │   │   ├── ProcessLogAdaptor.kt
    │   │   ├── ToolCallLogAdaptor.kt
    │   │   ├── PlanNoteAdaptor.kt
    │   │   └── token/
    │   │       └── TokenStat.kt
    │   ├── chat/                      # 消息日志模型
    │   │   ├── MessageLog.kt
    │   │   ├── MessageLogConverter.kt
    │   │   └── MsgExtractHelper.kt
    │   ├── provider/                  # 中间件与工具
    │   │   ├── ProviderConsts.kt
    │   │   ├── middleware/
    │   │   │   ├── ProcessLogMiddleware.kt
    │   │   │   └── ConfirmToolsMiddleware.kt
    │   │   └── tool/
    │   │       ├── ToolBox.kt
    │   │       ├── InterToolboxes.kt
    │   │       └── ToolCallContext.kt
    │   └── session/                   # 会话持久化
    │       ├── SessionConfig.kt
    │       ├── SessionLoader.kt
    │       └── MysqlAgentStateStore.kt
    └── harness/                        # Harness 核心引擎
        ├── HarnessAgentLauncher.kt    # 入口：创建并配置 HarnessAgent
        ├── HarnessAgentBuilder.kt     # Kotlin 风格 Builder 封装
        ├── HarnessAgentWrapper.kt     # 运行时封装（流式/非流式调用）
        ├── config/
        │   ├── HarnessConfig.kt
        │   ├── SandboxConfig.kt
        │   └── MinioConfig.kt
        ├── minio/
        │   ├── MinioBaseStore.kt      # BaseStore → MinIO KV 存储
        │   └── MinioSnapshotClient.kt # 沙箱快照上传/下载
        ├── sandbox/
        │   └── KeepAliveSandboxManager.kt  # 容器保活管理
        └── spring/
            └── HarnessAutoConfiguration.kt # Spring Boot 自动配置
```

---

## 4. 核心组件详解

### 4.1 HarnessAgentLauncher — 入口

**职责**：创建、配置并返回 `HarnessAgentWrapper` 实例。

**构造参数**（通过依赖注入）：

| 参数 | 类型 | 说明 |
|------|------|------|
| `chatModelConfigAdaptor` | `ChatModelConfigAdaptor` | 模型配置获取 |
| `mcpConfigAdaptor` | `McpConfigAdaptor` | MCP 服务配置获取 |
| `stateStore` | `AgentStateStore` | 会话状态存储（2.0.0 替代 `Session`） |
| `skillAdaptor` | `SkillAdaptor` | 技能加载适配器 |
| `tokenStatAdaptor` | `TokenStatAdaptor` | Token 统计持久化 |
| `processLogAdaptor` | `ProcessLogAdaptor` | 流程日志记录 |
| `toolCallLogAdaptor` | `ToolCallLogAdaptor` | 工具调用日志记录（可选） |
| `planNoteAdaptor` | `PlanNoteAdaptor` | 计划笔记持久化 |
| `workspaceRoot` | `Path` | 本地工作目录根路径 |
| `harnessConfig` | `HarnessConfig` | 运行时配置 |
| `minioConfig` | `MinioConfig?` | MinIO 分布式存储配置（可选） |
| `keepAliveSandboxManager` | `KeepAliveSandboxManager?` | 沙箱保活管理器（可选） |

**核心方法**：

| 方法 | 说明 |
|------|------|
| `createSingleAgent()` | 创建单个 HarnessAgentWrapper |
| `clearSession(sessionId)` | 清除会话状态、计划、快照 |
| `loadSessionMessages(sessionId)` | 从 AgentStateStore 加载历史消息 |
| `loadSessionHistoryPlan(sessionId)` | 加载计划笔记历史 |
| `loadSessionCurrentPlanNote(sessionId)` | 加载当前计划（2.0.0 返回 null） |
| `initLauncher()` (companion) | 工厂方法，替代 AscopeAgentLauncher |

**Agent 构建流程**（`createAgentBase`）：

```
HarnessAgentBuilder
  ├── name / description / maxIters / systemPrompt / workspace
  ├── stateStore(AgentStateStore)
  ├── model(ChatModelBase)            ← 从 ChatModelConfigAdaptor 获取
  ├── addMcp(McpClientWrapper)        ← 从 McpConfigAdaptor 加载
  ├── enableMetaTool(Boolean?)
  ├── addTool(ToolBox)                ← 注册内置工具（TIME_SET）
  ├── addToolContext(ToolExecutionContext)
  ├── addSkill(AgentSkill)            ← 从 SkillAdaptor 加载
  ├── addMiddleware(MiddlewareBase)   ← ProcessLog + ConfirmTools
  ├── enablePlan(true)                ← 2.0.0: enablePlanMode()
  ├── filesystem(DockerFilesystemSpec) ← sandbox 启用时
  ├── distributedStore(DistributedStore) ← 替代 SandboxDistributedOptions
  └── build() → HarnessAgent
```

---

### 4.2 HarnessAgentWrapper — 运行时封装

**职责**：封装 `HarnessAgent`，对外暴露流式/非流式调用 API，并处理事件转换、Token 统计、沙箱快照持久化。

**调用模式**：

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `callStream(prompt, imageUrls)` | `Flux<ChatEvent>` | 流式调用（文本+图片） |
| `callStream(msg)` | `Flux<ChatEvent>` | 流式调用（Msg 对象） |
| `call(prompt, imageUrls)` | `ChatResponse` | 非流式调用 |
| `call(msgs)` | `ChatResponse` | 非流式调用（Msg 列表） |

**流式调用链路**：

```
用户输入
  → Msg.builder().content(TextBlock + ImageBlock)
  → harnessAgent.streamEvents(msgs, runtimeContext)   ← 2.0.0: stream() → streamEvents()
  → Flux<AgentEvent>
  → ChatEventConverter.convert(agentEvent, dangerousTools)
  → Flux<ChatEvent>
  → extracted(tokenUsage) → TokenStatAdaptor.saveTokenStat()
  → doFinally: keepAliveSandbox.persistWorkspace()
  → concatWith(EndEventChatEvent)
  → onErrorResume: ErrorChatEvent + EndEventChatEvent
```

**RuntimeContext 构建**：
- 注入 `sessionId` 和 `userId`
- 若启用 `keepAlive`：创建/复用外部 Docker 沙箱容器，通过 `SandboxContext.externalSandbox` 注入

---

### 4.3 HarnessAgentBuilder — Kotlin 风格 Builder

**职责**：封装 `HarnessAgent.Builder`，提供流畅的 Kotlin API。

**API 分类**：

#### 基础 API（与 AscopeAgentBuilder 对齐）
| 方法 | 说明 |
|------|------|
| `name()` / `description()` | 基本信息 |
| `maxIters()` | 最大迭代次数 |
| `systemPrompt()` | 系统提示词 |
| `model()` | ChatModelBase 实例 |
| `addMcp()` | 异步注册 MCP 客户端 |
| `enableMetaTool()` | 元工具开关 |
| `addTool()` / `addToolContext()` | 工具注册 |
| `addSkill()` | 技能注册（构建时通过 InMemorySkillRepository 注入） |
| `addMiddleware()` | 中间件注册（替代 Hook） |
| `enablePlan()` | 计划模式（2.0.0: `enablePlanMode()`） |

#### Harness 专属 API
| 方法 | 说明 |
|------|------|
| `workspace()` | 工作目录 |
| `stateStore()` | AgentStateStore（替代 session()） |
| `filesystem()` | 文件系统规格（Docker 或 Remote） |
| `distributedStore()` | 分布式存储（替代 sandboxDistributed()） |
| `disableWorkspaceContext()` | 禁用工作区上下文注入 |
| `disableMemoryHooks()` | 禁用内置记忆 Hook |
| `disableSessionPersistence()` | 禁用自动会话持久化 |
| `disableFilesystemTools()` | 禁用文件系统工具 |
| `disableShellTool()` | 禁用 Shell 工具 |
| `disableSubagents()` | 禁用子 Agent |

---

## 5. 适配器接口层

所有外部依赖均通过 `fun interface` 抽象，实现与业务层的解耦：

| 接口 | 方法 | 职责 |
|------|------|------|
| `ChatModelConfigAdaptor` | `getConfig(id): ChatModelConfig?` | 获取模型配置（apiKey/modelName） |
| `McpConfigAdaptor` | `getConfig(id): McpServer?` | 获取 MCP 服务配置 |
| `SkillAdaptor` | `getSkill(id): AgentSkill?` | 获取技能定义 |
| `TokenStatAdaptor` | `saveTokenStat(stat)` | 保存 Token 消耗统计 |
| `ProcessLogAdaptor` | `emitLog(log)` | 发送流程日志 |
| `ToolCallLogAdaptor` | `emit(info)` | 发送工具调用日志 |
| `PlanNoteAdaptor` | `save/get/delete` | 计划笔记 CRUD |

---

## 6. 中间件系统（Middleware）

agentscope 2.0.0 将 `Hook` 替换为 `MiddlewareBase`，采用洋葱模型（onion pattern）拦截 Agent 生命周期。

### 6.1 ProcessLogMiddleware

**拦截点**：`onAgent` + `onActing`

**行为**：
- `onAgent`：Agent 启动/结束时记录日志；监听 `TOOL_CALL_START` / `TOOL_RESULT_END` / `TOOL_RESULT_TEXT_DELTA` 事件
- `onActing`：工具调用前记录输入参数
- `doOnError`：异常时记录 ERROR 日志

### 6.2 ConfirmToolsMiddleware

**拦截点**：`onReasoning`

**行为**：在推理阶段后检查是否调用了危险工具。实际危险工具拦截由框架内置的 `PermissionEngine` 处理，该中间件保留用于自定义推理前后逻辑。

### 6.3 全局中间件常量（ProviderConsts.kt）

```kotlin
val MIDDLEWARE_SET: Set<MiddlewareBase> = setOf(
    ProcessLogMiddleware(),
    ConfirmToolsMiddleware(),
)

val TOOL_SET = setOf(
    TimeToolBox(),
)
```

---

## 7. 工具系统（ToolBox）

### 7.1 ToolBox 抽象基类

**职责**：
- 自动扫描子类 `@NeedConfirmed` 注解方法，注册为需确认工具
- 提供 `execute()` 方法封装调用，自动记录工具调用日志（开始/结束时间、参数、结果）
- 通过 `UserIdentifier` 和 `SessionMetaContext` 注入运行时上下文

### 7.2 内置工具

| 类名 | 工具名 | 说明 |
|------|--------|------|
| `TimeToolBox` | `datetime-tool-box` | `getDate()` 和 `getDatetime()`，标记为 `@NeedConfirmed` |

### 7.3 ToolCallContext

工具运行上下文，通过 `ToolExecutionContext` 注入：

```kotlin
data class SessionMetaContext(val agentId: Long, val sessionId: String)
data class UserIdentifier(val userId: Long)
```

---

## 8. 会话持久化（Session）

### 8.1 AgentStateStore（agentscope 2.0.0）

2.0.0 引入 `AgentStateStore` 替代 `Session`，Key 模型从单一 `SessionKey` 变为 `(userId, sessionId, key)` 三元组。

### 8.2 SessionLoader

```kotlin
object SessionLoader {
    fun load(config: SessionConfig?): AgentStateStore = when (config) {
        null                → InMemoryAgentStateStore()
        is JsonSessionConfig → JsonFileAgentStateStore(Path(config.path))
        is MysqlSessionConfig → MysqlAgentStateStore(config.toDataSource())
    }
}
```

### 8.3 SessionConfig（sealed interface）

| 类型 | 字段 | 说明 |
|------|------|------|
| `MysqlSessionConfig` | `jdbcUrl, username, password, databaseName, tableName, createIfNotExist` | MySQL 持久化（HikariCP 连接池） |
| `JsonSessionConfig` | `path` | 本地 JSON 文件持久化 |

### 8.4 MysqlAgentStateStore

MySQL 实现的 `AgentStateStore`，表结构：

```sql
CREATE TABLE agent_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    state_key VARCHAR(255) NOT NULL,
    state_type VARCHAR(32) NOT NULL,       -- 'SINGLE' 或 'LIST'
    json_value LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_slot (user_id, session_id, state_key)
);
```

支持：
- `SINGLE` 值存储：直接 JSON 序列化
- `LIST` 值存储：使用 `ListStateWrapper` 包装为 JSON 数组
- 匿名用户使用 `__anon__` 作为 userId

---

## 9. Docker 沙箱与分布式存储

### 9.1 KeepAliveSandboxManager

**职责**：管理跨 Agent 调用的 Docker 容器保活机制。

使用 agentscope 的 **外部沙箱（Priority 1 user-managed）** 机制：通过 `SandboxContext.externalSandbox` 注入外部容器，框架在 `SandboxManager.release` 时跳过 stop/shutdown，保持容器运行。

| 方法 | 说明 |
|------|------|
| `getOrCreate(sessionId, workspaceSpec, snapshotSpec)` | 获取或创建保活容器 |
| `destroy(sessionId)` | 停止并销毁指定会话的容器 |
| `destroyAll()` | 停止并销毁所有容器 |

### 9.2 分布式存储架构（DistributedStore）

当沙箱启用时，构建 `DistributedStore`：

```
DistributedStore.builder()
  ├── agentStateStore(stateStore)           ← 会话状态
  ├── baseStore(MinioBaseStore)             ← KV 存储后端
  └── sandboxSnapshotSpec(snapshotSpec)     ← 快照规格
```

### 9.3 MinioBaseStore（实现 `BaseStore`）

每个 KV 对存储为 MinIO 中的 JSON 对象：

- **Object Key**：`{keyPrefix}{namespace.join("/")}/{key}`
- **示例**：`namespace=["agents","myAgent"]`, key=`MEMORY.md` → `store/agents/myAgent/MEMORY.md`

### 9.4 MinioSnapshotClient（实现 `RemoteSnapshotClient`）

- `upload(snapshotId, data)`：上传 tar 快照到 MinIO
- `download(snapshotId)`：从 MinIO 下载快照
- `exists(snapshotId)`：检查快照是否存在
- `delete(snapshotId)`：删除快照

---

## 10. 配置系统

### 10.1 HarnessConfig（顶层）

```kotlin
data class HarnessConfig(
    val sandbox: SandboxConfig = SandboxConfig(),
    val enableWorkspaceContext: Boolean = false,   // 是否注入 AGENTS.md 到 system prompt
    val enableMemoryHooks: Boolean = false,         // 是否启用内置记忆 Hook
    val enableSessionPersistence: Boolean = true,   // 是否启用自动会话持久化
)
```

### 10.2 SandboxConfig

```kotlin
data class SandboxConfig(
    val enabled: Boolean = false,
    val image: String = "python:3.11-slim",
    val workspaceRoot: String = "/workspace",
    val isolationScope: IsolationScope = IsolationScope.SESSION,
    val keepAlive: Boolean = false,
)
```

### 10.3 MinioConfig

```kotlin
data class MinioConfig(
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val snapshotBucket: String = "harnax-snapshots",
    val storeBucket: String = "harnax-store",
    val snapshotPrefix: String = "snapshots/",
    val storePrefix: String = "store/",
)
```

---

## 11. Spring Boot 自动配置（HarnessAutoConfiguration）

**触发条件**：classpath 存在 `harnax-harness-core` jar。

### Configuration Properties

| 前缀 | 类 | 说明 |
|------|---|------|
| `harness` | `HarnessProperties` | 全局开关（workspaceContext / memoryHooks / sessionPersistence） |
| `harness.sandbox` | `SandboxProperties` | 沙箱配置（enabled / image / workspaceRoot / isolationScope / keepAlive） |
| `harness.minio` | `MinioProperties` | MinIO 配置（enabled / endpoint / accessKey / secretKey / buckets） |

### 注册的 Bean

| Bean | 条件 | 说明 |
|------|------|------|
| `minioClient` | `harness.minio.enabled=true` | MinioClient 实例 |
| `minioConfig` | `harness.minio.enabled=true` | MinioConfig（自动创建 Bucket） |
| `harnessConfig` | 始终可用 | HarnessConfig 实例 |
| `harnessAgentLauncher` | 始终可用 | HarnessAgentLauncher（注入所有适配器） |

### 配置示例（application.yaml）

```yaml
harness:
  enableWorkspaceContext: false
  enableMemoryHooks: false
  enableSessionPersistence: true
  sandbox:
    enabled: true
    image: python:3.11-slim
    workspaceRoot: /workspace
    isolationScope: SESSION
    keepAlive: false
  minio:
    enabled: true
    endpoint: http://minio:9000
    accessKey: minioadmin
    secretKey: minioadmin
    snapshotBucket: harnax-snapshots
    storeBucket: harnax-store
    snapshotPrefix: snapshots/
    storePrefix: store/
```

---

## 12. 消息模型（chat 包）

### 12.1 MessageLog 体系

```
MessageLog (interface)
├── SystemMessageLog   { message }
├── UserMessageLog     { message }
├── AssistantMessageLog { thinking, text, toolUseLog: List<ToolUseLog> }
└── ToolResultMessageLog { name, result }
```

### 12.2 MsgExtractHelper

从 `Msg` 中提取内容：
- `extractText(msg)` → 文本内容
- `extractThinking(msg)` → 思考内容（ThinkingBlock）
- `extractToolOutput(result)` → 工具调用结果文本

---

## 13. agentscope 2.0.0 迁移变更汇总

| 1.x API | 2.0.0 API | 说明 |
|---------|-----------|------|
| `Session` | `AgentStateStore` | 会话接口，Key 变为 (userId, sessionId, key) |
| `InMemorySession` | `InMemoryAgentStateStore` | 内存实现 |
| `JsonSession` | `JsonFileAgentStateStore` | JSON 文件实现 |
| `MysqlSession` | `MysqlAgentStateStore`（自定义） | MySQL 实现，需自行实现 |
| `Hook` / `HookEvent` | `MiddlewareBase` | 中间件机制，洋葱模型 |
| `ProcessLogHook` | `ProcessLogMiddleware` | 流程日志中间件 |
| `ConfirmToolsHook` | `ConfirmToolsMiddleware` | 危险工具确认中间件 |
| `stream()` | `streamEvents()` | 流式 API 返回 `Flux<AgentEvent>` |
| `Event` / `EventType` | `AgentEvent` / `AgentEventType` | 事件类型重命名 |
| `SandboxDistributedOptions` | `DistributedStore` | 分布式存储配置 |
| `DockerFilesystemSpec.sandboxStateStore()` | 已移除 | 沙箱状态由 DistributedStore 管理 |
| `PlanNotebook` | `enablePlanMode()` | 计划改为 Markdown 文件模式 |
| `StructuredOutputReminder` | 已移除 | 模型层原生处理 |
| `AutoContextConfig` / `AutoContextMemory` | 已移除 | 工作区上下文改为内置 |
| `Memory` / `InMemoryMemory` | 已移除 | 状态管理由 AgentStateStore 接管 |
| `SkillBox` / `ShellCommandTool` | 已移除 | 技能由 `AgentSkillRepository` 管理 |
| `SessionManager` | 已移除 | 无直接替代 |
| `DockerFilesystemSpec` 包路径 | `io.agentscope.harness.agent.sandbox.impl.docker` | 包路径变更 |

---

## 14. 调用链路时序图

```
用户请求
  │
  ▼
HarnessAgentLauncher.createSingleAgent(agentSpec, sessionId, chatSpec)
  │
  ├── 构建 HarnessAgentBuilder
  │     ├── 注入 model / mcp / tools / skills / middleware
  │     ├── 配置 stateStore + distributedStore
  │     └── 配置 DockerFilesystemSpec + SandboxSnapshotSpec
  │
  ▼
HarnessAgentWrapper.callStream(prompt)
  │
  ├── 构建 Msg(TextBlock + ImageBlock)
  ├── buildRuntimeContext()
  │     ├── RuntimeContext(sessionId, userId)
  │     └── [keepAlive] 注入外部 Docker 沙箱
  │
  ▼
harnessAgent.streamEvents(msgs, runtimeContext)
  │
  ▼
Flux<AgentEvent>
  │
  ├── ChatEventConverter.convert(agentEvent, dangerousTools)
  │     ├── TEXT_BLOCK_DELTA → TextDeltaChatEvent
  │     ├── TEXT_BLOCK_END   → TextEndChatEvent
  │     ├── THINKING_BLOCK_DELTA → ThinkingChatEvent
  │     ├── TOOL_CALL_START → ToolCallChatEvent
  │     ├── TOOL_RESULT_END → ToolResultChatEvent
  │     └── MODEL_CALL_END  → TokenUsageChatEvent
  │
  ▼
Flux<ChatEvent>
  │
  ├── extracted(tokenUsage) → TokenStatAdaptor.saveTokenStat()
  ├── doFinally → keepAliveSandbox.persistWorkspace()
  └── concatWith(EndEventChatEvent)
```
