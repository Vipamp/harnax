# harnax-client SDK 模块设计方案

## 概述

harnax-client 是面向外部服务的 SDK，提供对 Harnax Router 的 HTTP 客户端封装。外部服务通过该 SDK 以 `X-Api-Key` 认证方式调用 Router 代理的全部 Agent 接口（含 SSE 流式对话），无需了解 Harnax 内部架构。

模块独立于 Harnax 内部服务，不依赖 harnax-protocol / harnax-common / agentscope 等内部库。

## 模块结构

```
harnax-client/                          (pom, packaging=pom)
├── harnax-client-common/               (共享 DTO + 接口定义)
├── harnax-single-client/               (纯 Java HttpClient，不绑定 Spring)
└── harnax-springboot-client/           (Spring Boot auto-configuration + WebClient/RestClient)
```

在根 `pom.xml` 中新增 `<module>harnax-client</module>`。

---

## 1. harnax-client-common

**职责**: 定义轻量级 DTO、统一接口、工具类。不依赖 harnax-protocol / harnax-common / agentscope。

### 依赖
- `kotlin-stdlib-jdk8`
- `tools.jackson.core:jackson-databind` (JSON 序列化)
- `com.fasterxml.jackson.core:jackson-annotations` (多态反序列化)
- `org.slf4j:slf4j-api`

### 核心类设计

```
com.agnetix.harnax.client/
├── HarnaxClientConfig.kt          # 配置类：baseUrl, apiKey, timeout 等
├── HarnaxClient.kt                # 统一接口定义（Kotlin interface）
├── dto/
│   ├── ChatRequest.kt             # 聊天请求
│   ├── ChatResponse.kt            # 非流式聊天响应
│   ├── CommandRequest.kt          # 命令请求
│   ├── CommandResponse.kt         # 命令响应
│   ├── ConfirmRequest.kt          # 确认请求
│   ├── ChatEvent.kt               # SSE 事件（精简版，不含 agentscope 依赖）
│   ├── ResultVo.kt                # 轻量级统一响应（独立定义，不依赖 harnax-common）
│   └── WorkspaceModels.kt         # 文件列表、状态等 DTO
├── exception/
│   ├── HarnaxClientException.kt   # 客户端异常
│   └── HarnaxServerException.kt   # 服务端异常（HTTP 4xx/5xx）
└── sse/
    └── SseEventParser.kt          # SSE text/event-stream 解析工具
```

### HarnaxClient 接口

```kotlin
interface HarnaxClient {
    // Chat
    suspend fun chat(request: ChatRequest): ResultVo<ChatResponse>
    fun chatStream(request: ChatRequest): Flow<ChatEvent>   // Kotlin Flow, 跨平台

    // Command
    suspend fun command(request: CommandRequest): ResultVo<CommandResponse>

    // Confirm (SSE streaming)
    fun confirm(request: ConfirmRequest): Flow<ChatEvent>

    // Session
    suspend fun clearSession(sessionId: String): ResultVo<String>
    suspend fun getHistory(sessionId: String): ResultVo<List<ChatEvent>>
    suspend fun getPlans(sessionId: String): ResultVo<List<Any>>
    suspend fun getCurrentPlan(sessionId: String): ResultVo<Any?>

    // Workspace
    suspend fun listFiles(sessionId: String, path: String = "/workspace"): ResultVo<List<FileInfo>>
    suspend fun readFile(sessionId: String, path: String): ResultVo<FileContent>
    suspend fun getWorkspaceStatus(sessionIds: List<String>): ResultVo<Map<String, WorkspaceStatus>>
    suspend fun getWorkspaceStatus(sessionId: String): ResultVo<WorkspaceStatus>
    suspend fun uploadFile(sessionId: String, path: String, fileName: String, bytes: ByteArray): ResultVo<UploadResult>
    suspend fun downloadFile(sessionId: String, path: String): DownloadResult
}
```

> 使用 Kotlin `Flow` 而非 Reactor `Flux`，避免 common 模块绑定 Reactor。single-client 和 springboot-client 各自将 Flow 适配为自身的流式机制。

---

## 2. harnax-single-client

**职责**: 纯 HTTP 客户端实现，不依赖 Spring Boot。适用于任何 JVM 项目。

### 依赖
- `harnax-client-common`
- `java.net.http.HttpClient` (JDK 21 内置，零额外依赖)
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` (suspend + Flow)

### 核心实现

```
com.agnetix.harnax.client.single/
├── SingleHarnaxClient.kt          # HarnaxClient 实现，基于 JDK HttpClient
├── SingleClientBuilder.kt         # Builder 模式构建客户端
└── sse/
    └── JdkSseStreamReader.kt      # JDK HttpClient SSE 流读取 → Flow<ChatEvent>
```

### 使用示例

```kotlin
val client = SingleHarnaxClient.builder()
    .baseUrl("http://router-host:8081")
    .apiKey("your-api-key")
    .connectTimeout(Duration.ofSeconds(5))
    .readTimeout(Duration.ofSeconds(30))
    .build()

// 同步调用
val result = client.chat(ChatRequest(sessionId = "sess-1", message = "Hello"))

// SSE 流式
client.chatStream(ChatRequest(sessionId = "sess-1", message = "Hello"))
    .collect { event -> println(event) }
```

---

## 3. harnax-springboot-client

**职责**: Spring Boot starter，自动配置，使用 RestClient（同步）+ WebClient（SSE 流式）。

### 依赖
- `harnax-client-common`
- `spring-boot-starter` (auto-configuration)
- `spring-boot-starter-webflux` (WebClient for SSE)
- `spring-web` (RestClient for sync calls)

### 核心实现

```
com.agnetix.harnax.client.spring/
├── SpringHarnaxClient.kt          # HarnaxClient 实现，RestClient + WebClient
├── HarnaxClientAutoConfiguration.kt  # @AutoConfiguration
├── HarnaxClientProperties.kt      # @ConfigurationProperties(prefix = "harnax.client")
└── resources/
    └── META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 配置

```yaml
harnax:
  client:
    base-url: http://router-host:8081
    api-key: your-api-key
    connect-timeout: 5s
    read-timeout: 30s
    stream-timeout: 10m
```

### 使用方式

```kotlin
// 在 Spring Bean 中直接注入
@Service
class MyService(private val harnaxClient: HarnaxClient) {
    suspend fun askAgent(sessionId: String, msg: String) =
        harnaxClient.chat(ChatRequest(sessionId, msg))
}
```

---

## Router API 覆盖范围

所有接口调用 Router 的 `/api/router/agent/` 前缀，通过 `X-Api-Key` 请求头认证：

| HTTP 方法 | Router 路径 | Client 方法 | 类型 |
|---|---|---|---|
| POST | `/api/router/agent/chat` | `chat()` | 同步 |
| POST | `/api/router/agent/chat/stream` | `chatStream()` | SSE |
| POST | `/api/router/agent/command` | `command()` | 同步 |
| POST | `/api/router/agent/confirm` | `confirm()` | SSE |
| DELETE | `/api/router/agent/session/{id}` | `clearSession()` | 同步 |
| GET | `/api/router/agent/chat/history/{id}` | `getHistory()` | 同步 |
| GET | `/api/router/agent/session/{id}/plans` | `getPlans()` | 同步 |
| GET | `/api/router/agent/session/{id}/current-plan` | `getCurrentPlan()` | 同步 |
| GET | `/api/router/agent/workspace/{id}/files` | `listFiles()` | 同步 |
| GET | `/api/router/agent/workspace/{id}/read` | `readFile()` | 同步 |
| GET | `/api/router/agent/workspace/status` | `getWorkspaceStatus(list)` | 同步 |
| GET | `/api/router/agent/workspace/{id}/status` | `getWorkspaceStatus(single)` | 同步 |
| POST | `/api/router/agent/workspace/{id}/upload` | `uploadFile()` | 同步 |
| GET | `/api/router/agent/workspace/{id}/download` | `downloadFile()` | 同步 |
