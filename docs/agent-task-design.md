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
harnax-scheduler 服务（独立进程，端口 8084，Quartz + 定时任务域本身：实体 / mapper / CRUD / 日志都在 `com.agnetix.harnax.scheduler.*`，库是它自有的 `harnax_scheduler`）
  │   既定形态两个实例，共用一个 Quartz JDBC 集群 store（11 张 QRTZ_* 表是唯一调度真相）：
  │   一次触发全集群只投递一次、只由一个节点执行，一台死了另一台接管
  │
  ├─ 到点 fire AbstractAgentTaskJob.execute()（cron 与「立即执行一次」的 one-shot 是同一条路径）
  │    │
  │    ├─ 0. 抢执行权：AgentTaskExecutionGuard.tryAcquireLock(taskId, triggerTime)
  │    │       靠 agent_task_execution 的 uk_task_trigger(task_id, trigger_time) 唯一键，抢不到就放弃
  │    │
  │    ├─ 1. 写入运行中的执行日志行（`agent_task_log` status=3），并定下本次的 sessionId
  │    │       `task-{taskId}-{agentId}-{uuid}`（契约 C1）——scheduler 不建会话行，Agent 配置由 router 侧按这个 id 解析；
  │    │       这个形态的生成与解析只有一个出处：`com.agnetix.harnax.common.session.TaskSessionId`
  │    │
  │    ├─ 2. 通过 RestClient 调用 Router（同一发调用里等执行结束）
  │    │       POST {routerUrl}/api/router/agent/chat
  │    │       Body: { sessionId, message }
  │    │       Header: X-Api-Key: {systemApiKey}
  │    │
  │    ├─ 3. 取回非流式响应，定态写回日志行与 `agent_task_execution`（成功/失败/超时，状态守卫的 UPDATE）
  │    │
  │    └─ 4. 清理会话：DELETE {routerUrl}/api/router/agent/session/{sessionId}（自带更短的读超时上限）
  │
  └─ 对 admin 暴露内部 HTTP 面（/reload、/tasks/{id}/start|pause|trigger|run-once、/tasks/logs/{id}/stop，
      以及 /api/scheduler/agent-tasks/** 的 11 条 CRUD 与日志端点 + `/agent-tasks/{id}/owner`（C5））
      ——`/api/scheduler/**` 全部要带一枚 internal JWT（C4），读面也在内，缺凭证一律 401

harnax-admin 服务
  │   只做「校验用户 JWT + 带身份转发」：/api/admin/agent-tasks/** 的契约与前端不变，
  │   11 条调用（CRUD、启停、触发、停止、日志）一律转发到 http://scheduler:8084（HARNAX_SCHEDULER_URL，
  │   容器网络，不发布宿主端口），每一发带 internal JWT + X-Forwarded-User + X-Tenant-Id（C4）；
  │   本服务对 agent_task / agent_task_log / agent_task_execution / QRTZ_* 零 SQL——
  │   「写完 agent_task 之后在事务提交后跑一轮对账」这件事在 scheduler 内部完成（`AgentTaskCrudServiceImpl` 注册 `afterCommit`，调 `TaskScheduleReconciler.reconcile()`）；节点间不广播，一致性由共享 store 给出；
  │   只有 /agents 仍是 admin 自己的域（agent 表在它手上）
  │
  └─ 前端管理页面（创建/编辑/启停/查看日志）
```

> **本文的口径**：上面的架构图是当前形态。**「一、数据模型」三张表的定义以 `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql` 为准**——库是 scheduler 自有的 `harnax_scheduler`，这个文件是该模块唯一的 schema 基线，目录里没有需要叠加的后续版本，表结构的最终态全写在这一个文件里。当前的列宽与索引：
>
> - `agent_task`：`name`、`agent_name`、`cron_expression` 为 `VARCHAR(128)`，`description` 为 `VARCHAR(512)`，`timeout_seconds` 为 `INT` 默认 300，`creator` 为 `VARCHAR(64)`；索引 `idx_tenant_id (tenant_id)`、`idx_agent_id (agent_id)`，唯一键 `uk_name (name)`。
> - `agent_task_log`：`session_id` 为 `VARCHAR(128)`，装的是带 agentId 段的 `task-{taskId}-{agentId}-{uuid}`；`task_name` 为 `VARCHAR(128)`，`token_usage` 为 `VARCHAR(512)`，`creator` 为 `VARCHAR(64)`；索引 `idx_task_id (task_id)`、`idx_status (status)`、`idx_create_time (create_time)`。
> - `agent_task_execution`：`instance_id` 为 `VARCHAR(128)`；抢执行权靠唯一键 `uk_task_trigger (task_id, trigger_time)`，另有 `idx_task_id (task_id)`、`idx_trigger_time (trigger_time)`，清扫（每 5 分钟一轮，`SchedulerServiceImpl` 的 `HOUSEKEEPING_INTERVAL_MINUTES`）要走的 `idx_status_create_time (status, create_time)` 与 `idx_create_time (create_time)` 内联在同一条建表语句里。
>
> 「二、后端模块结构」按 scheduler 进程里的真实文件给出，job 类在 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/`：`AbstractAgentTaskJob` 承载一次触发的全部逻辑，`AgentTaskJob`（`concurrent` 允许重叠的执行）与 `AgentTaskNonConcurrentJob`（带 `@DisallowConcurrentExecution`）是它的两个 Quartz 入口，同目录还有 `TaskQuartzRegistrar`、`SchedulerReconcileJob`、`SchedulerHousekeepingJob`。「三、核心实现」的调用要点由这些类承担：一次触发交给 `SchedulerService.executeTaskOnce(task, triggerTime)`，到 router 的三发调用（`POST /api/router/agent/chat`、`POST /api/router/agent/command`、`DELETE /api/router/agent/session/{sessionId}`）都在 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt`。「四、API 设计」那张表是对客户端的契约，与 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt` 的 12 条映射一致。「五、前端设计」的要素落在 `harnax-webui/src/pages/agent-task/index.tsx`：一张任务表，加 `components/TaskForm` 的创建/编辑弹窗与 `components/TaskLogModal` 的执行日志弹窗。「六、配置文件变更」的键在 `harnax-scheduler/src/main/resources/application.yml` 的顶层 `scheduler:` 下：`enabled`、`instance-id`、`reconcile-interval-seconds`、`router-url`、`api-key`、`admin-url`、`admin-secret`、`timeout-seconds`、`clear-session-timeout-seconds`、`command-timeout-seconds`。调度侧的配置项与部署约束以 `docs/deploy-harnax-scheduler.md` 为准，链路与决策以 `prod_doc/agent-task-scheduler.zh-CN.md` 与 `docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md` 为准。

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
    session_id      VARCHAR(128) COMMENT 'task-{taskId}-{agentId}-{uuid}',
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
harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/
├── job/
│   ├── AbstractAgentTaskJob.kt              # 一次触发的全部逻辑（抢权、写日志、调 router、定态写回）
│   ├── AgentTaskJob.kt                      # concurrent 允许重叠时的 Quartz 入口
│   ├── AgentTaskNonConcurrentJob.kt         # 带 @DisallowConcurrentExecution 的 Quartz 入口
│   ├── TaskQuartzRegistrar.kt               # JobDetail / Trigger 的注册与删除
│   ├── SchedulerReconcileJob.kt             # 定时对账 Quartz store 与 agent_task
│   └── SchedulerHousekeepingJob.kt          # 每 5 分钟一轮的清扫
├── service/
│   ├── SchedulerService.kt                  # 执行与停止的接口
│   ├── AgentTaskCrudService.kt              # CRUD 接口
│   ├── AgentTaskLogQueryService.kt          # 日志查询接口
│   ├── AgentTaskExecutionGuard.kt           # 靠 uk_task_trigger 抢执行权
│   ├── TaskScheduleReconciler.kt            # 一轮对账，写事务提交后由 afterCommit 触发
│   └── impl/
│       ├── SchedulerServiceImpl.kt
│       ├── AgentTaskCrudServiceImpl.kt
│       └── AgentTaskLogQueryServiceImpl.kt
├── controller/
│   ├── AgentTaskController.kt               # /api/scheduler/agent-tasks/** 的 11 条 CRUD 与日志端点
│   ├── AgentTaskOwnerController.kt          # GET /api/scheduler/agent-tasks/{id}/owner
│   └── SchedulerController.kt               # /api/scheduler 内部面（/reload、/tasks/**）
├── client/
│   ├── RouterClient.kt                      # chat / command / session 清理三发调用
│   └── CommandDelivery.kt
├── entity/
│   ├── AgentTask.kt
│   ├── AgentTaskLog.kt
│   └── AgentTaskExecution.kt
├── mapper/
│   ├── AgentTaskMapper.kt
│   ├── AgentTaskLogMapper.kt
│   └── AgentTaskExecutionMapper.kt
└── dto/
    ├── AgentTaskCreateRequest.kt
    ├── AgentTaskUpdateRequest.kt
    ├── AgentTaskResponse.kt
    ├── AgentTaskLogResponse.kt
    └── Page.kt

harnax-scheduler/src/main/resources/
├── application.yml                          # scheduler.* 配置，含到 router 的地址与各类超时
└── mapper/
    ├── AgentTaskMapper.xml
    ├── AgentTaskLogMapper.xml
    └── AgentTaskExecutionMapper.xml         # mybatis.mapper-locations: classpath*:mapper/*.xml

harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/
├── controller/AgentTaskController.kt        # /api/admin/agent-tasks/** 的 12 条对客户端契约
└── service/
    ├── SchedulerClient.kt                   # 转发接口
    └── impl/SchedulerClientImpl.kt          # 带 internal JWT + X-Forwarded-User + X-Tenant-Id 打到 scheduler
```

## 三、核心实现

### 3.1 AgentTaskJob（Quartz Job）

Quartz 自己实例化 job，协作者从 `SchedulerContext` 取（`schedulerService`、`executionGuard`、`agentTaskMapper` 三个键），不走 Spring 注入；两个子类的作用只是让一次触发同步跑在 Quartz 的工作线程上：

```kotlin
class AgentTaskJob :                                    // 任务 concurrent 允许重叠
    AbstractAgentTaskJob(),
    Job {
    override fun execute(context: JobExecutionContext) = run(context)
}

@DisallowConcurrentExecution
class AgentTaskNonConcurrentJob :                       // 任务 concurrent = 0
    AbstractAgentTaskJob(),
    Job {
    override fun execute(context: JobExecutionContext) = run(context)
}
```

`AbstractAgentTaskJob.run()` 一次触发的顺序：

1. 从 JobDataMap 读 `TaskQuartzRegistrar.KEY_TASK_ID`（存储里的 job 只带这一个字符串键），回读 `agent_task` 取当前的 prompt、cron 与状态；
2. 先过 `SchedulerService.schedulingEnabled`，再校验 `active == 1`，定时触发（job group 为 `GROUP_AGENT_TASK`）还要 `task_status == 1`；`concurrent == 0` 的任务先看是否已有活着的执行；
3. `AgentTaskExecutionGuard.tryAcquireLock(task.id, triggerTime)` 抢执行权，靠的是 `uk_task_trigger`，抢不到就放弃这一发；
4. `SchedulerService.executeTaskOnce(task, triggerTime)` 执行这一次。

`SchedulerServiceImpl.executeTaskOnce()` 的实现：先 `insertRunningLog()` 插入 status=3 的运行中行并定下 sessionId，再 `routerClient.chat(sessionId, task.prompt)`；成功把日志置 1，异常置 0 并把 `e.message` 截到 4000 字写进 `error_info`；随后先算 `end_time` 与 `duration_ms`，用 `AgentTaskLogMapper.finishExecution` 做只匹配「仍在运行」的状态守卫 UPDATE，然后 `routerClient.clearSession(sessionId)`，最后 `executionGuard.updateExecutionStatus(...)` 收尾 `agent_task_execution`。

### 3.2 RouterClient（scheduler → Router 的 HTTP 客户端）

一次执行到 router 只有三发调用，都带 `X-Api-Key`，配置键都在 `scheduler:` 下：

```kotlin
@Component
class RouterClient(
    @Value("\${scheduler.router-url:http://localhost:8081}") private val routerUrl: String,
    @Value("\${scheduler.api-key:}") private val configuredApiKey: String,
    @Value("\${scheduler.admin-url:http://localhost:8080}") private val adminUrl: String,
    @Value("\${scheduler.admin-secret:}") private val adminSecret: String,
    @Value("\${scheduler.timeout-seconds:300}") private val timeoutSeconds: Int,
    @Value("\${scheduler.clear-session-timeout-seconds:60}") private val clearSessionTimeoutSeconds: Int,
    @Value("\${scheduler.command-timeout-seconds:10}") private val commandTimeoutSeconds: Int,
) {
    fun chat(sessionId: String, message: String): ChatResponse
    // POST {routerUrl}/api/router/agent/chat，非流式，在同一发调用里等执行结束

    fun sendCommand(sessionId: String, command: CommandType): CommandDelivery
    // POST {routerUrl}/api/router/agent/command，用户点「停止」走这一发

    fun clearSession(sessionId: String)
    // DELETE {routerUrl}/api/router/agent/session/{sessionId}
}
```

> 调用 Router 用的系统级 API Key 取自 `scheduler.api-key`；这一项为空时，RouterClient 在启动期向 admin 拉一枚 SYSTEM key：`POST /api/admin/internal/api-keys/system-key`，带 `Authorization: Bearer {scheduler.admin-secret}`，请求体里的 serviceName 是 `scheduler`。

### 3.3 Session 创建与清理

scheduler 对会话表零写入，它只负责生成这一轮的 sessionId：`TaskSessionId.of(task.id, task.agentId)` 在 `SchedulerServiceImpl.insertRunningLog()` 里生成 `task-{taskId}-{agentId}-{uuid}`，写进 `agent_task_log.session_id`。id 的语法与解析只有一处定义——`harnax-common` 的 `com.agnetix.harnax.common.session.TaskSessionId`，两个 id 段都取自同一行 `agent_task`。Agent 配置由 admin 的 Internal API 按这个 id 反查任务解析（`InternalApiController` 调 `TaskSessionId.parse(sessionId)`，解析不出来直接报错）。

一轮执行结束后由 `RouterClient.clearSession(sessionId)` 发 `DELETE /api/router/agent/session/{sessionId}` 清理会话。这一发是 best-effort：它跑在状态写回之后，读超时上限取 `min(scheduler.clear-session-timeout-seconds, scheduler.timeout-seconds)`，失败只记 warn，不会改动已经落定的日志状态。

## 四、API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/admin/agent-tasks/page` | 分页列表 |
| `GET` | `/api/admin/agent-tasks/{id}` | 详情 |
| `POST` | `/api/admin/agent-tasks` | 创建 |
| `PUT` | `/api/admin/agent-tasks/{id}` | 更新 |
| `DELETE` | `/api/admin/agent-tasks/{id}` | 删除 |
| `POST` | `/api/admin/agent-tasks/toggle/{id}?status=` | 启用/停用（webui 任务页改状态走的就是这一条；上面的 `{id}/start` 与 `{id}/pause` 有服务层封装但那个页面不调它们，两者都在契约里） |
| `POST` | `/api/admin/agent-tasks/{id}/start` | 启动 |
| `POST` | `/api/admin/agent-tasks/{id}/pause` | 暂停 |
| `POST` | `/api/admin/agent-tasks/{id}/trigger` | 立即执行一次（admin 对客户端的立即执行只有这一条；`/api/scheduler/tasks/{id}/run-once` 属于 scheduler 内部面，admin 不暴露） |
| `POST` | `/api/admin/agent-tasks/logs/{logId}/stop` | 停止一次运行中的执行 |
| `GET` | `/api/admin/agent-tasks/{id}/logs` | 执行日志 |
| `GET` | `/api/admin/agent-tasks/agents` | 可选 Agent 列表 |

共 12 条，与 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt` 的映射一一对应。除 `/agents` 由 admin 自己查 `agent` 表回答（`agentService.getActiveAgents()`）外，其余 11 条都经 `SchedulerClientImpl.forward` 打到 `/api/scheduler/agent-tasks/**` 的同尾路径，每一发带一枚 internal JWT + `X-Forwarded-User` + `X-Tenant-Id`；转发链路的完整说明在 `docs/deploy-harnax-admin.md` 的定时任务域一节，本文件不重复。

## 五、前端设计

### 5.1 任务管理页面

页面在 `harnax-webui/src/pages/agent-task/index.tsx`：一张任务表（antd `Table`），行上的操作是编辑、删除（`DELETE /api/admin/agent-tasks/{id}`）、状态切换（行上的开关，走 `POST /api/admin/agent-tasks/toggle/{id}?status=0|1`）与立即执行一次（`POST /api/admin/agent-tasks/{id}/trigger`），执行日志入口打开的是独立弹窗。

- 创建/编辑弹窗（`components/TaskForm.tsx`）的表单项：
  - `name` 任务名称（必填）
  - `agentId` 选择 Agent（下拉，选项来自 `GET /api/admin/agent-tasks/agents`）
  - `prompt` 输入（多行文本框）
  - `cronExpression`（输入框加 `cronPresets` 常用预设）
  - `timeoutSeconds` 超时秒数
  - `concurrent` 并发模式
  - `description` 任务描述
  - `isPublic` 是否公开

### 5.2 执行日志

日志在独立弹窗 `components/TaskLogModal.tsx` 里：

- 表格列：状态、prompt、回复、耗时（`durationMs`）、开始时间（`startTime`）
- 完整 prompt 与 Agent 回复在 `components/LogDetailModal.tsx` 里查看
- 运行中的那一行可以停止：`POST /api/admin/agent-tasks/logs/{logId}/stop`

## 六、配置文件变更

执行链路的配置在 `harnax-scheduler/src/main/resources/application.yml` 的顶层 `scheduler:` 下：

```yaml
scheduler:
  enabled: ${SCHEDULER_ENABLED:true}
  instance-id: ${SCHEDULER_INSTANCE_ID:}                              # 抢执行权用的实例标识，留空自动生成
  reconcile-interval-seconds: ${SCHEDULER_RECONCILE_INTERVAL:60}       # Quartz store 与 agent_task 的对账周期
  router-url: ${SCHEDULER_ROUTER_URL:http://localhost:8081}
  api-key: ${SCHEDULER_API_KEY:}                                      # 为空则启动期向 admin 拉 SYSTEM key
  admin-url: ${SCHEDULER_ADMIN_URL:http://localhost:8080}
  admin-secret: ${SCHEDULER_ADMIN_SECRET:change-me-in-production-min-32-chars!!}
  timeout-seconds: ${SCHEDULER_TIMEOUT:300}                           # 一次执行的读超时上限，也是清扫判定僵死执行的基准
  clear-session-timeout-seconds: ${SCHEDULER_CLEAR_SESSION_TIMEOUT:60} # 会话清理的读超时上限，实际取 min(本值, timeout-seconds)
  command-timeout-seconds: ${SCHEDULER_COMMAND_TIMEOUT:10}            # /stop 那一发 INTERRUPT 的读超时上限
```

admin 侧只需要知道 scheduler 在哪：`harnax-admin/src/main/resources/application.yml` 的 `harnax.scheduler.url`，值为 `${HARNAX_SCHEDULER_URL:http://localhost:8084}`，单个地址——集群的一致性由共享的 Quartz store 给出，不靠客户端扇出。

## 七、向后兼容

- 现有 `sys_job` 系统保持不变
- 新增 `agent_task` 独立表和功能
- 现有前端 Job 管理页面保留不动
- 新增独立的 AgentTask 管理页面
