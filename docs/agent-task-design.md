# 智能体定时任务方案设计

## 背景

现有定时任务系统（`sys_job`）是通用 Quartz Job 框架，需要用户填写 Java 类全限定名（`jobClass`），对普通用户不友好。需要设计一套面向业务的「智能体定时任务」，让用户选择 Agent + 输入 prompt，定时自动调用 Agent 执行任务。

## 需求

- 用户选择一个已有的 Agent，输入一段自然语言 prompt
- 配置 cron 表达式设定执行频率
- 每次触发时：创建临时 session → 通过 Router 调用 Agent → 收集执行结果 → 记录日志 → 清理 session
- Agent 执行过程中可能调用工具（tool use）
- 执行日志可查看每次的 prompt、Agent 回复、耗时、状态

## 整体架构

```
Admin Service (Quartz + AgentTaskJob)
  │
  ├─ 定时触发 AgentTaskJob.execute()
  │    │
  │    ├─ 1. 创建临时 Session（复制 Agent 配置）
  │    │
  │    ├─ 2. 通过 RestClient 调用 Router
  │    │       POST {routerUrl}/api/router/agent/chat
  │    │       Body: { sessionId, message }
  │    │       Header: X-Api-Key: {systemApiKey}
  │    │
  │    ├─ 3. 等待 Agent 响应（非流式模式）
  │    │
  │    ├─ 4. 记录执行日志（prompt、回复、耗时、状态）
  │    │
  │    └─ 5. 删除临时 Session
  │
  └─ 前端管理页面（创建/编辑/启停/查看日志）
```

## 一、数据模型

### 1.1 新建 `agent_task` 表

不复用 `sys_job` 表，独立建表以支持业务字段：

```sql
CREATE TABLE agent_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT DEFAULT 1,
    name            VARCHAR(128) NOT NULL COMMENT '任务名称',
    agent_id        BIGINT NOT NULL COMMENT '关联 Agent ID',
    agent_name      VARCHAR(128) COMMENT 'Agent 名称快照',
    prompt          TEXT NOT NULL COMMENT '定时执行的 prompt 内容',
    cron_expression VARCHAR(128) NOT NULL COMMENT 'Cron 表达式',
    task_status     TINYINT NOT NULL DEFAULT 0 COMMENT '0=暂停, 1=运行中',
    concurrent      TINYINT NOT NULL DEFAULT 0 COMMENT '0=不允许并发, 1=允许',
    timeout_seconds INT DEFAULT 300 COMMENT '超时秒数，默认5分钟',
    description     VARCHAR(512) DEFAULT '' COMMENT '任务描述',
    is_public       TINYINT DEFAULT 0,
    creator         VARCHAR(64) DEFAULT '',
    active          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_agent_id (agent_id),
    UNIQUE KEY uk_name (name)
) COMMENT '智能体定时任务';
```

### 1.2 新建 `agent_task_log` 表

```sql
CREATE TABLE agent_task_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT NOT NULL COMMENT '关联 agent_task.id',
    task_name       VARCHAR(128) COMMENT '任务名称',
    prompt          TEXT COMMENT '本次执行的 prompt',
    response        TEXT COMMENT 'Agent 回复内容',
    session_id      VARCHAR(64) COMMENT '临时 session ID',
    status          TINYINT DEFAULT 1 COMMENT '0=失败, 1=成功, 2=超时',
    error_info      TEXT COMMENT '异常信息',
    token_usage     VARCHAR(512) COMMENT 'Token 使用 JSON',
    start_time      DATETIME COMMENT '开始时间',
    end_time        DATETIME COMMENT '结束时间',
    duration_ms     BIGINT COMMENT '执行耗时(毫秒)',
    creator         VARCHAR(64) DEFAULT '',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) COMMENT '智能体定时任务执行日志';
```

## 二、后端模块结构

```
harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/
├── job/
│   └── AgentTaskJob.kt              # Quartz Job，执行核心逻辑
├── service/
│   ├── AgentTaskService.kt          # 接口
│   ├── AgentTaskLogService.kt       # 日志接口
│   └── impl/
│       ├── AgentTaskServiceImpl.kt  # 实现
│       └── AgentTaskLogServiceImpl.kt
├── controller/
│   └── AgentTaskController.kt       # REST API
├── client/
│   └── RouterClient.kt              # 调用 Router 的 HTTP 客户端
├── dto/
│   ├── AgentTaskCreateRequest.kt
│   ├── AgentTaskUpdateRequest.kt
│   ├── AgentTaskResponse.kt
│   └── AgentTaskLogResponse.kt

harnax-entity/
├── entity/
│   ├── AgentTask.kt
│   └── AgentTaskLog.kt
└── mapper/
    ├── AgentTaskMapper.kt + XML
    └── AgentTaskLogMapper.kt + XML
```

## 三、核心实现

### 3.1 AgentTaskJob（Quartz Job）

```kotlin
@Component
class AgentTaskJob : Job {
    @Autowired lateinit var sessionService: SessionService
    @Autowired lateinit var routerClient: RouterClient
    @Autowired lateinit var agentTaskLogService: AgentTaskLogService

    override fun execute(context: JobExecutionContext) {
        val task = context.jobDetail.jobDataMap["agentTask"] as AgentTask
        val log = AgentTaskLog(taskId=task.id, taskName=task.name,
                               prompt=task.prompt, startTime=now())
        try {
            // 1. 创建临时 session
            val session = sessionService.createForAgent(task.agentId, task.creator)

            // 2. 调用 Agent（非流式，等待完成）
            val response = routerClient.chat(
                sessionId = session.sessionId,
                message = task.prompt,
                timeoutSeconds = task.timeoutSeconds
            )

            // 3. 记录结果
            log.response = response.content
            log.tokenUsage = objectMapper.writeValueAsString(response.tokenUsage)
            log.status = 1  // 成功

            // 4. 清理 session
            sessionService.deleteSession(session.id)
        } catch (e: Exception) {
            log.status = 0  // 失败
            log.errorInfo = e.message?.take(4000)
        } finally {
            log.endTime = now()
            log.durationMs = Duration.between(log.startTime, log.endTime).toMillis()
            agentTaskLogService.save(log)
        }
    }
}
```

### 3.2 RouterClient（Admin → Router HTTP 客户端）

复用 channel 模块的调用模式，Admin 通过 Router 的非流式 chat 端点调用 Agent：

```kotlin
@Component
class RouterClient(
    private val restClient: RestClient,
    @Value("\${harnax.router.url}") private val routerUrl: String,
    @Value("\${admin.internal-api.secret}") private val internalSecret: String,
) {
    fun chat(sessionId: String, message: String, timeoutSeconds: Int): ChatResponse {
        val request = ChatAgentRequest(sessionId, message)
        // POST {routerUrl}/api/router/agent/chat
        // Header: X-Api-Key: {systemApiKey}
        // 返回非流式 ChatResponse
    }
}
```

> 需要 Admin 获取一个系统级 API Key 来调用 Router。可通过 `POST /api/admin/internal/api-keys/system-key` 获取。

### 3.3 Session 创建与清理

```kotlin
// SessionService 中新增方法
fun createForAgent(agentId: Long, creator: String): Session {
    val agent = agentService.getAgent(agentId)
        ?: throw BizException("Agent not found")
    val session = Session()
    session.sessionId = UUID.randomUUID().toString()
    session.agentId = agentId
    session.title = "AgentTask-${LocalDateTime.now()}"
    session.name = agent.name
    session.systemPrompt = agent.systemPrompt
    session.modelId = agent.modelId
    session.mcpList = agent.mcpList
    session.skillList = agent.skillList
    session.owner = creator
    session.status = 1
    session.creator = creator
    sessionMapper.insert(session)
    return session
}
```

## 四、API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/admin/agent-tasks/page` | 分页列表 |
| `GET` | `/api/admin/agent-tasks/{id}` | 详情 |
| `POST` | `/api/admin/agent-tasks` | 创建 |
| `PUT` | `/api/admin/agent-tasks/{id}` | 更新 |
| `DELETE` | `/api/admin/agent-tasks/{id}` | 删除 |
| `POST` | `/api/admin/agent-tasks/{id}/start` | 启动 |
| `POST` | `/api/admin/agent-tasks/{id}/pause` | 暂停 |
| `POST` | `/api/admin/agent-tasks/{id}/run` | 立即执行一次 |
| `GET` | `/api/admin/agent-tasks/{id}/logs` | 执行日志 |
| `GET` | `/api/admin/agent-tasks/agents` | 可选 Agent 列表 |

## 五、前端设计

### 5.1 任务管理页面

- 左侧：任务列表（同现有 Skill 页面的仓库列表风格）
- 右侧：选中任务的执行日志列表
- 创建/编辑弹窗：
  - 任务名称（必填）
  - 选择 Agent（下拉，从 `/agents` 接口获取）
  - Prompt 输入（多行文本框）
  - Cron 表达式（复用现有 JobForm 的 cron 选择器）
  - 超时秒数
  - 并发模式

### 5.2 执行日志

- 表格列：状态标签、prompt 摘要、回复摘要、耗时、开始时间
- 点击展开查看完整 prompt 和 Agent 回复

## 六、配置文件变更

Admin `application.yml` 新增：

```yaml
# 定时任务调用 Agent 的配置
agent-task:
  router-url: ${HARNAX_ROUTER_URL:http://localhost:8081}
  timeout-seconds: ${AGENT_TASK_TIMEOUT:300}
```

## 七、向后兼容

- 现有 `sys_job` 系统保持不变
- 新增 `agent_task` 独立表和功能
- 现有前端 Job 管理页面保留不动
- 新增独立的 AgentTask 管理页面
