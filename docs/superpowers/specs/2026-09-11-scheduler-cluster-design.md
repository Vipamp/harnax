# 定时任务域集群化与业务下沉 · 设计规格

- 日期：2026-09-11
- 状态：**设计已确认，待编写实现计划**
- 范围：`harnax-scheduler`、`harnax-admin`、`harnax-entity`、`harnax-session-router`、`harnax-agent-service`、`harnax-cli`、`harnax-webui`、`docker-new`
- 关联文档：[prod_doc/agent-task-scheduler.zh-CN.md](../../../prod_doc/agent-task-scheduler.zh-CN.md)（现状调研与全流程说明）。本文取代其第 10.3、10.4、11、12 节的方案与里程碑；那份文档的其余章节仍是现状事实的来源。

## 0. 要解决的三个问题

| # | 问题 | 现状证据 |
|---|---|---|
| ① | 调度器单实例，进程挂起即停止调度 | `spring.quartz.job-store-type: memory`（`harnax-scheduler/src/main/resources/application.yml:33`）；无 `QRTZ_*` 建表脚本、无 `isClustered` |
| ② | 任务定义在 admin、调度执行在 scheduler，两边靠广播与回调缝合 | 每次 CRUD 后 `schedulerClient.reloadTasks()` 广播（`AgentTaskServiceImpl`）；agent-service 用 `task-` 前缀反查 admin 组 spec（`InternalApiController.resolveFromTask`） |
| ③ | **完成/停止流程有正确性洞** | 见第 4 节逐条 |

**目标一句话**：定时任务的调度、执行发起、状态记录全部归 `harnax-scheduler`（独立库 + Quartz JDBC 集群，compose 固定 2 实例）；`harnax-admin` 只做用户 JWT 鉴权 + 带身份转发，三客户端接口契约不变；实际跑 agent 的仍是 `harnax-agent-service`，此点不变。

## 1. 决策清单（已定稿）

| # | 决策 | 选择 | 关键理由 |
|---|---|---|---|
| D1 | scheduler 是否多实例 | **是**，Quartz 真 JDBC 集群 | 需要故障接管与 misfire 补偿，不接受"各节点各 fire + DB 抢锁"的浪费模式 |
| D2 | 部署形态 | **docker-compose 固定 2 实例** | 无 K8s/HPA，实例数不动态变化 |
| D3 | 表放哪 | **独立 `harnax_scheduler` 库** | scheduler 独占 schema 所有权 |
| D4 | sessionId 是否编码 agentId | **编码**，格式 `task-{taskId}-{agentId}-{uuid}` | 省掉 spec 热路径上的 `agent-id` 端点与一跳网络，并让 admin 不再对 `agent_task` 发 SQL。**注**：另有一条冷路径依赖（MCP 属主解析）须由 C5 承接，见 2.1 |
| D5 | 任务级权限模式 | **本轮不做**，`permissionMode = "BYPASS"` 继续硬编码在 admin 的 `resolveFromTask` | 属新功能（任务级可配 + webui 配置项），不混入架构改造；记入 fast-follow F5 |
| D6 | 停机语义 | **绝不丢执行**：`waitForJobsToCompleteOnShutdown = true` + `stop_grace_period: 360s` + 部署脚本逐台滚动 | 见 6.2、6.3 的配套约束 |
| D7 | 中断未命中的修法 | **如实返回命中结果**（跨 agent-service / router / scheduler 三侧） | 拒绝"靠超时猜"的方案，避免孤儿 sandbox |
| D8 | 数据 | **迁任务定义，不迁历史日志** | 同实例跨库 `INSERT ... SELECT` 成本极低；历史 `agent_task_log` 价值低且量大 |

D8 是本次评审中由我代替用户做的一个判断（原文档第 10.4 节写的是"不迁数据、人工重建"，但那是针对不建独立库的形态）。若要改回不迁，只需删掉 7 节 S2 的迁移步骤与发布公告，其余设计不受影响。

## 2. 目标架构

### 2.1 职责边界

| | harnax-scheduler | harnax-admin | harnax-agent-service |
|---|---|---|---|
| `agent_task` / `agent_task_log` / `agent_task_execution` / `QRTZ_*` | **唯一读写方**（`harnax_scheduler` 库，自有 Flyway） | 完全不认识这四张表 | 完全不认识 |
| 任务 CRUD 与业务校验（cron 合法性、名称唯一、属主规则） | ✅ | 转发 | — |
| Quartz 集群调度、reconcile、僵尸回收、guard 清理 | ✅ | — | — |
| 执行发起与状态机（3→1/0/4→5/2） | ✅ | — | — |
| 真正跑 agent（LLM 循环、工具、sandbox） | — | — | ✅ |
| agent-spec 装配 | — | ✅（agent/model/tool/mcp/skill/env 是它的表） | — |
| 签发 SYSTEM API Key | — | ✅ | 消费方 |

admin 在此域内的三类剩余职责：① 用户 JWT 鉴权 + 身份注入 + HTTP 转发；② 它自己的既有域（agent/model/tool/mcp/skill/channel/user/apikey/tenant）；③ `/api/admin/internal/agent-spec/{sessionId}`——**所有**会话类型建 agent 的必经入口。

精确表述：改造后 admin 仍认识 `task-` 这个**字符串前缀**（会话类型识别），但不再认识"定时任务"这个**数据域**——四张表没有一条 SQL 从 admin 发出。

**这条有一个残留，自检时发现，原文档未记**：`McpSessionOwnerResolver.fromTask`（`harnax-admin/.../util/McpSessionOwnerResolver.kt:57-64`）在解析 MCP OAuth 属主时要拿任务的 `creator` 与 `tenantId`，靠的就是 `agentTaskMapper.selectAnyById(taskId)`。D4 消不掉它——agentId 是给 spec 装配用的，属主是另一份数据。所以：

- 它**不是** hot path（只在 agent-service 为一个 task 会话解析 OAuth MCP token 时触发），与 2.2 的 spec 链路不同；
- 解法是 scheduler 提供 `GET /api/scheduler/agent-tasks/{id}/owner`（返回 `creator` + `tenantId`），即契约 **C5**；
- D4 省掉的是 spec 热路径上的那一跳，**不是**"任何 agent-id 反查端点都不需要"。上一轮我按 D4 说过"admin 对这个域的读取归零"，那句话要以 C5 补正后才成立：归零的是**表读取**，跨服务查询仍留一条冷路径。

好消息是它的解析方式不受 C1 影响：`removePrefix("task-").substringBefore('-')` 只取第一段数字，sessionId 多一段仍然正确。

### 2.2 一次执行与一次停止的调用链

```
scheduler.executeTaskOnce   sessionId = task-{taskId}-{agentId}-{uuid}
  │ POST /api/router/agent/chat        X-Api-Key = SYSTEM key（AuthContext.userId = null）
  ▼
router → agent-service  /api/agent/chat → getOrCreateAgent(Caffeine, 30min)
  │  agent-service → admin /internal/agent-spec/{sessionId}
  │       admin 从 sessionId 解出 agentId（不查库），装配 agent/model/tool/mcp/skill
  ▼
agent.call() 完整 ReAct → ChatResponse(content, tokenUsage, attachments)
  ▼
scheduler CAS 定态 → DELETE /api/router/agent/session/{id}（快照上传 + 容器销毁）

停止：scheduler.stopTask → CAS 3→4 → POST /api/router/agent/command INTERRUPT
      → agent-service 如实返回命中/未命中 → 未命中时 scheduler 直接 4→5 定态
```

改造前后**跳数不变**（仍是 scheduler→router→agent-service→admin 四段），减少的是 admin 内部的一次数据库查询，并且不再有 admin→scheduler 的新增跳转（这是 D4 的直接收益）。

### 2.3 鉴权与转发

- 客户端 → admin：既有用户 JWT 链路不变，`/api/admin/agent-tasks/**` 路径与响应契约（`records`/`total`/`id` 字段名）不变。
- admin → scheduler：复用现成 `InternalTokenProvider` 签 `typ=internal` JWT，注入 `X-Forwarded-User`、`X-Tenant-Id`。
- scheduler 侧：新增约 20 行 internal-JWT 校验拦截器覆盖 `/api/scheduler/**` 全部写面（只验签，不接入 `harnax-auth` 自动装配，`harnax.auth.enabled` 保持 `false`）。这是一条**已记录的偏离项**，完整自鉴权属 fast-follow F1。
- 生产不再把 scheduler 端口发布到宿主机、nginx 不再代理 `/api/scheduler/`（见 6.4）。

## 3. 调度引擎

### 3.1 Quartz JDBC 集群配置

```yaml
spring:
  quartz:
    job-store-type: jdbc
    jdbc:
      initialize-schema: never        # Flyway 建表，不让 Spring 再跑脚本
    properties:
      org.quartz.scheduler.instanceId: AUTO
      org.quartz.jobStore.class: 不写  # 见下方约束
      org.quartz.jobStore.isClustered: true
      org.quartz.jobStore.clusterCheckinInterval: 15000
      org.quartz.jobStore.misfireThreshold: 60000
      org.quartz.jobStore.acquireTriggersWithinLock: true
      org.quartz.jobStore.useProperties: true
      org.quartz.threadPool.threadCount: ${QUARTZ_THREAD_COUNT:10}
```

**约束 1**：**不要显式配置 `org.quartz.jobStore.class`**。Spring Boot 会用 `LocalDataSourceJobStore` 覆盖它并接上自己的数据源；写死 `JobStoreTX` 会造成连不上 Spring 管理的数据源。

**约束 2**：`waitForJobsToCompleteOnShutdown` 不是 Boot 的 `spring.quartz.*` 键，需在 scheduler 的 `@Configuration` 里用 `SchedulerFactoryBeanCustomizer` 调 `setWaitForJobsToCompleteOnShutdown(true)`（D6）。现有 `SchedulerConfig` 是加这个 bean 的位置。

**约束 3**：Hikari `maximum-pool-size` 从 10 提到 30，需 ≥ `threadCount` + 业务查询并发。

### 3.2 JobDataMap 与 `useProperties`

`useProperties: true` 下 JobDataMap 只允许字符串值，**放 Long 会抛 `ObjectNotSupportedException`**。所以 `scheduleTask` 与 `runTaskOnce` 里现有的 `jobDataMap.put("agentTask", task)`（`SchedulerServiceImpl.kt:136`、`:253`）改为只放 `taskId` 字符串，`AgentTaskJob` 在 fire 时回查 `agent_task` 拿最新定义。这顺带修掉一个既有缺陷：任务改过 prompt 之后，已注册的 job 里仍是旧实体。

### 3.3 reconcile 取代全删重建

`loadTasksToScheduler()` 现在的第 1 步是"把 `AgentTaskGroup` 里所有 job 删掉"（`SchedulerServiceImpl.kt:178-189`）。共享 JobStore 下这等价于**任一节点重启就报掉全集群任务并重注册**。改为 diff 收敛：

- 库里应有的（`task_status=1 AND active=1`）与 store 里现有的按 `jobKey` 比对；
- 缺则注册；多则删；cron 表达式或 job 类不一致则 reschedule；**一致则完全不动**，以保留 `PREV_FIRE_TIME` / `NEXT_FIRE_TIME`；
- 同一个函数复用于三处：启动加载、CRUD 之后、每 60s 的集群内 reconcile job。
- reconcile job 自身即依赖集群保证"全集群同时只有一个节点在跑"，这是本改造收益的直接演示。
- 新增指标 `scheduler.reconcile.drift{action=add|remove|update}`：CRUD 与 store 一旦脱节，先在指标上显形。

### 3.4 执行并发闸口

**用每节点 `threadCount` 当闸口，不引入集群级限流。** 2 实例 × 10 = 全集群最多 20 个并发执行。

理由：下游 agent-service 仍是单实例，每次执行要占一个 Caffeine 槽（`agent.cache.max-size` 默认 500）与一个真实 sandbox 容器（`isolation-scope: SESSION`、`SANDBOX_KEEP_ALIVE=true`）。跨节点令牌桶买到的只是"能同时打更多下游"，而那正是下游容不下的。等 agent-service 横向扩展时，只需调 `QUARTZ_THREAD_COUNT`，无需改代码。

**注意 D6 带来的一个反噬**：原计划把 threadCount 提到 25 会放大下游压力，本文定为**保持 10**。

### 3.5 超时语义统一

现状三处基准不一致：执行侧读超时走 `scheduler.timeout-seconds`（默认 300），`expireStale` 的兜底走硬编码 `DEFAULT_TIMEOUT_SECONDS = 300`，逐行则用 `agent_task.timeout_seconds`。改造：

- `expireStale` 的兜底改为读 `scheduler.timeout-seconds` 配置；
- 判定阈值 = `max(行的 timeout_seconds, 配置值) × 1.5`，为并发排队留余量——否则一次在下游排队的正常执行会被判 `2 timeout`，随后真实结果因 CAS 失配被丢弃（见 4.4）。

## 4. 执行与停止链路

### 4.1 同步执行 + 双 job 类

`AgentTaskJob.execute()` 现在起 daemon 线程后立刻返回（`AgentTaskJob.kt:55-65`），Quartz 于是认为 job 秒完、`QRTZ_FIRED_TRIGGERS` 不留行。后果是三个能力**同时失效**：故障接管接不到正在跑的执行、`waitForJobsToCompleteOnShutdown` 空转（D6 直接落空）、`@DisallowConcurrentExecution` 无对象可互斥。

改造：

- 在 Quartz 线程内同步执行完整链路（调 router → 定态 → clearSession），删除裸线程；
- 摘掉 `InterruptableJob`（其 `interrupt()` 是空实现，真中断走 INTERRUPT 命令），改回 `Job`——同时清掉文档 13.5 记的名义接口；
- 新增 `AgentTaskNonConcurrentJob`（带 `@DisallowConcurrentExecution`），注册时按 `task.concurrent` 选 job 类。现状 `concurrent=0` 只靠 `withMisfireHandlingInstructionDoNothing`，那只在触发被错过时生效，拦不住重叠执行。

**顺序要求：本项必须早于或同于 3.1 上线。** 这是本次评审对原 M1→M4 顺序的实质修正。

### 4.2 手动执行合并为 one-shot

`triggerManually`（抢锁 + 起线程）与 `runTaskOnce`（投 `AgentTaskGroup_ONCE` one-shot trigger）是两套并行逻辑，前者还绕过了 Quartz。合并为一条：只保留 one-shot 投递（非 durable、JobDataMap 只放 taskId、`startNow()`），集群里任意节点 fire，进程崩了由 Quartz 补火。`hasActiveRunningLog` 保留为"同一任务不许并发手动跑"的业务拦截，`agent_task_execution` 抢锁降级为兜底防线保留。

顺带清理：`SchedulerConfig.taskExecutor()`（core 2 / max 10 / queue 50，注释写"Async thread pool for manual task execution"）实际无人使用——裸线程路径没走它。本项做完后删除该 bean。

### 4.3 中断未命中如实定态（D7）

现状缺陷：`DefaultAgentRunner.interrupt()` 依赖 `agentCache.getIfPresent(sessionId)`，未命中时什么都不做却返回 `success("Stream interrupted")`（`DefaultAgentRunner.kt:239-254`）；scheduler 侧 `RouterClient.sendCommand` 返回 `Unit`、响应体根本未接收。后果链：agent-service 重启过或 session 映射过期被 reroute → 中断空转、执行继续 → 日志行停在 4 → `expireStale` 判 `2 timeout` → 而任务其实成功产出，结果被丢。用户视角是"点了停止，显示停止中，最后变成超时"。

修法三处：

1. agent-service：`interrupt()` 返回是否真的命中了一个活着的执行。可用信号两个——`agentCache.getIfPresent` 与 `activeCalls` 集合（blocking 调用会注册 sessionId，`DefaultAgentRunner.kt:124`）；未命中时返回 `CommandResponse.success = false` 并带明确 message。
2. router：`/api/router/agent/command` 已经把 `CommandResponse` 原样装进 `ResultVo.data` 回传（含 failover 分支的 failure），**无需改动，仅需加一条透传断言**。
3. scheduler：`RouterClient.sendCommand` 当前签名是 **返回 `Unit`、响应体压根没有接收**（`RouterClient.kt:127-143` 调完 `.body(...)` 直接丢弃，只 log 一行"命令已发送"）。改为返回 `Boolean`（取 `data.success`），`stopTask` 在拿到 `false` 时**立即** `finalizeStopped`（4→5）定态，不等 `expireStale`。

语义依据：命中不了 = 没有任何进程在推进这次执行 = 它已经死了，立即定态是陈述事实，不是猜测。这也是本修法优于"轮询几秒后自行定态"的原因——后者会在执行真的还在跑时留下孤儿 sandbox。

### 4.4 定态分支与结果不丢

`executeTaskOnce` 收尾时若 `finishExecution` 影响 0 行，现状一律按"被用户停止"处理并走 `finalizeStopped`。但那一行也可能是**已被 `expireStale` 抢先写成 2**：此时 `finalizeStopped`（`WHERE status = 4`）同样 0 行，代码只 `log.warn("already finalised elsewhere")`，**执行结果永久丢失**。

改为三条可区分的分支：被停止（4）→ `finalizeStopped`；已被判超时（2）→ 用一条覆盖 UPDATE 写回真实 status/response，并在 `error_info` 追加"曾被判超时后完成"的标记；两者都不匹配 → 按写回失败告警。

### 4.5 housekeeping

新增一个集群内 housekeeping job（与 reconcile 同族，周期 5min），职责：

- 挂上 `cleanupOldExecutions(retentionDays)`（`AgentTaskExecutionGuard.kt:70`，**当前全仓库零调用方**，导致 `agent_task_execution` 只进不出）；
- 清理超过 `timeout-seconds × 2` 仍停在 `status=0` 的 guard 泄漏行（抢锁成功但进程在执行前死亡的场景）。

`expireStale` 保留现有调用点（启动加载、并发判断前），并增加在 housekeeping 内调用。

## 5. 跨服务契约变更（需同步发布）

| # | 契约 | 变更 | 影响面 |
|---|---|---|---|
| C1 | 定时任务 sessionId 格式 | `task-{taskId}-{uuid}` → `task-{taskId}-{agentId}-{uuid}` | 生成方 1 处（`SchedulerServiceImpl.kt:317`）；**解析方 2 处**：`InternalApiController` 的 `resolveFromTask`（`split("-", limit=3)` → `limit=4`）与 `McpSessionOwnerResolver.fromTask`（`substringBefore('-')`，**无需改动**，仅纳入回归用例）；其余三处依赖都是 `startsWith("task-")`，不受影响。`agent_task_log.session_id` VARCHAR(64) → **VARCHAR(128)**（新格式实占 49~55） |
| C2 | INTERRUPT 返回值语义 | 从"恒为 success"改为"如实反映是否命中活跃执行" | agent-service 实现 + scheduler 消费；router 透传不改 |
| C3 | trigger 冲突识别 | 从字符串匹配文案改为业务码 `40901` | scheduler 出码、admin 透传、webui 改判 code（替掉 `index.tsx:122` 的 `includes('already running')`） |
| C4 | admin→scheduler 转发头 | `X-Forwarded-User` / `X-Tenant-Id` + internal JWT | scheduler 新拦截器；`X-Forwarded-Tenant` 必须忽略（浏览器可伪造，见原文档 8.2） |
| **C5** | task 属主查询 | 新增 `GET /api/scheduler/agent-tasks/{id}/owner` → `{creator, tenantId}` | admin 的 `McpSessionOwnerResolver.fromTask` 改调此端点（替代 `agentTaskMapper.selectAnyById`）。冷路径，见 2.1 的残留说明 |

C1 的格式约定是 scheduler 与 admin 之间的**隐式契约**：`resolveFromTask` 必须做显式校验（段数、两段的数字解析），失败时抛带明确文案的异常；格式本身写进本文档作为契约条目。

## 6. 部署形态（compose 固定 2 实例）

### 6.1 库

- `docker-new/sql/init-databases.sql` 增加 `CREATE DATABASE harnax_scheduler` + 对 `harnax` 用户 `GRANT`（与既有四个库同构，两行）。
- scheduler 开 Flyway：`enabled: true`、`locations: classpath:db/migration`、`table: flyway_schema_history_scheduler`（**独立 history 表名**，避免与 admin 在同一 MySQL 实例里混淆）。
- 迁移脚本：`V1__quartz_tables.sql`（官方 11 张 `QRTZ_*`，剥掉所有 DROP 语句、补齐每表 COMMENT）、`V2__agent_task_domain.sql`（三张业务表按库表规范重写，含 `session_id VARCHAR(128)`）。

### 6.2 两实例与优雅停机（D6 配套）

- 删 `container_name: harnax-scheduler`（compose:270）与宿主映射 `28084:8084`（compose:294），改 `expose: ["8084"]`。
- **不在 compose 里声明 `deploy.replicas`**，实例数由部署脚本显式 `--scale scheduler=2` 决定。理由：6.3 的逐台滚动要在"停掉其中一台、补齐到 2"之间来回切换，声明式 replicas 会让 `--no-recreate` 的收敛行为变得难以推理。
- `stop_grace_period: 360s`。**为什么不是 310s**：同步执行后一次执行占用 = chat 读超时 300s + `clearSession`（快照上传 + 容器销毁，代码注释自述 10s+）+ 状态写回，310s 只剩 10 秒余量，会在正常长任务上被 SIGKILL，D6 落空。
- 时钟：compose 已统一挂载 `/etc/localtime`；集群要求各节点时钟偏差 < 1s，宿主机 NTP 记入运维 checklist（Quartz 集群对时钟敏感，NTP 步进会造成误判接管）。

### 6.3 部署脚本必须逐台滚动

`docker-new/deploy-service.sh:132` 现在是 `up -d --force-recreate --no-deps scheduler`，`--force-recreate` 作用于整个 service → **两实例同时下线**，最长 360s 全集群无调度。触发在 `QRTZ_TRIGGERS` 堆积，节点回来后按 misfire 处理，而 `concurrent=0` 用的是 `withMisfireHandlingInstructionDoNothing` → **错过的触发被永久跳过**，与 D6"绝不丢执行"的初衷正好相反。

改为逐台：`docker stop -t 360 <其中一台容器>` → `docker-compose up -d --no-deps --scale scheduler=2 --no-recreate scheduler`（补齐缺失的那台，不动仍在跑的那台）。

### 6.4 暴露面

- 删除 `docker-new/nginx.conf:178` 的 `location /api/scheduler/`（admin 走 `HARNAX_SCHEDULER_URL: http://scheduler:8084` 内网服务名，不需要 nginx）。
- scheduler 的 swagger 与 actuator 生产面收窄：`SWAGGER_ENABLED=false`、`management.endpoints.web.exposure` 去掉 `prometheus` 的匿名暴露（现随 8084 匿名可达）。属 F2，本轮只做部署层。

## 7. 里程碑

原 M1→M5 的顺序按"改动类型"分组而非按"依赖"分组，有两处会导致中间态比现状更危险，本表已修正：

- **修正 A**：同步执行（原 4.4）提前到 JDBC 集群之前，见 4.1 的顺序要求。
- **修正 B**：reconcile（原 4.1）必须与 JDBC store 同期上线，否则存在"共享 store + 全删重建"的窗口，任一节点重启会报掉全集群任务。

| 步 | 内容 | 人日 | 可独立发布 |
|---|---|---|---|
| **S0 行为修复** | C3（40901 三端同步）；CLI `logId`→`id` **并补 `task stop` 子命令**（当前 task.go 里不存在该命令，所谓"CLI 停止从未生效"的真实原因是功能缺失而非字段名）；删死代码（admin `AgentTaskLogService.save()`、`/internal/agent-tasks/{taskId}/spec` 端点及测试、`SchedulerClientImpl.kt:35` 与事实相反的注释） | 0.5 | ✅ |
| **S1 执行语义** | 4.1 同步执行 + 双 job 类；4.3 中断命中语义（D7，跨三侧）；4.4 定态分支 + 3.5 超时统一；4.5 housekeeping | 2.5 | ✅ |
| **S2 库 + 集群 + 对账 + 部署形态** | 6.1 建库与两个 Flyway 脚本；3.1 quartz 集群配置 + 约束 2/3；3.3 reconcile；D8 数据迁移（`agent_task` 一次性 `INSERT ... SELECT`）；6.2/6.3 compose 与逐台滚动；6.4 删 nginx 与宿主映射 | 4 | ✅（内部必须原子） |
| **S3 域搬迁** | 三实体 + 三 mapper + 三 XML 从 `harnax-entity` 移入 scheduler（含 `@MapperScan`、`type-aliases-package`、XML 全限定 type）；scheduler 自带 `Page`/异常副本 + pagehelper；`/api/scheduler/agent-tasks/**` 12 端点 + C5 owner 端点 + 2.3 拦截器；admin `AgentTaskController` 瘦身为鉴权+转发、删 service/DTO/广播；C1 sessionId 编 agentId；**admin 侧 24 处 mapper 引用全部清零（实测 4 个文件：`AgentTaskServiceImpl` 12、`InternalApiController` 4（含 290 与 713 两处 `selectAnyById`）、`AgentTaskLogServiceImpl` 5、`McpSessionOwnerResolver` 3）** | 3.5 | ❌ 必须单 PR（admin 编译断裂） |
| **S4 收口** | 4.2 one-shot 合并 + 删 `taskExecutor`；`scheduler.reconcile.drift` 指标；健康/指标改集群语义；第 8 节测试补全；文档同步 | 2 | ✅ |

合计约 **12.5 人日**。要点是 **S0+S1 = 3 人日即可独立上线并解决全部问题③**——原计划把这些排在 M0 与最末的 M4，等于正确性修复要等 14 人日的搬迁走完才对用户生效。

回滚点：S0/S1/S4 各自独立可 revert；S2 回滚 = 指回 `harnax_admin` 库 + `job-store-type: memory`（旧库数据保留不删）；S3 回滚 = revert 整个 PR。`harnax_admin` 里的三张旧表**本轮不 DROP**，留到 S2 上线并观察过至少一个完整 cron 周期后由运维签认再单独合迁移脚本——原 M5.1 的硬约束仍然有效。

## 8. 测试

集成测试放 scheduler 模块，testcontainers 起真 MySQL，走 failsafe profile（`-Pintegration-test`，形态照 `harnax-admin/pom.xml:344-393`）。本域难点（集群抢锁、CAS 定态、diff 收敛）全部 mock 不出来。

| 用例 | 断言 | 为什么值得写 |
|---|---|---|
| IT-1 集群单触发 | 两实例连同库，1s cron 跑 8s：`agent_task_log` 行数 ≈ 触发次数（不是 2×）；`QRTZ_FIRED_TRIGGERS` 里出现过两个实例名 | "真集群"与"各节点各 fire"的唯一硬证据。**必须建在 S1 之后**，否则同步执行没做，这个测试会被假通过 |
| IT-2 对账收敛 | SQL 手造漂移（多删/少建/改 cron）→ `reconcile()` → store 收敛，且未变更 job 的 `PREV_FIRE_TIME` 未被清零 | 后半句是"没有回归成全删重建"的直接证据，缺了它这个测试会被错误实现骗过 |
| IT-3 越权 | 非属主读/改/删拿不到且数据未变；`is_public=1` 可读 | 已完成项（属主校验）的回归护栏 |
| IT-4 one-shot | 连发两次 trigger：store 出现 `_ONCE` trigger、第二发返回 40901；投递后 kill 进程，重启补火一次且仅一次 | 覆盖 4.2 与 D6 的补火语义 |
| IT-5 guard 清理 | 造过期 `agent_task_execution` 行 → 跑 housekeeping → 过期行删除、未过期保留、`status=0` 泄漏行被清 | `cleanupOldExecutions()` 此前零调用方 |
| **IT-6 中断未命中** | 缓存无该 session 时 `interrupt()` 返回未命中，scheduler 立即把行 4→5，`expireStale` 之后不再改动它 | 覆盖 D7。这是本轮唯一跨三个服务的断言，也是用户可见的直接收益 |
| **IT-7 超时竞争** | 执行结果返回前行已被 `expireStale` 写成 2 → 真实结果与状态被覆盖写回，`error_info` 含超时标记 | 覆盖 4.4"结果不丢" |

既有测试处置：`AgentTaskServiceImplTest`(603) 迁 scheduler 重写为真库 IT；`SchedulerClientImplTest`(452) 随广播删除、保留转发用例；`AgentTaskLogServiceImplTest`(271) 删除（被测对象是死代码）；`AgentTaskControllerTest`(657) 拆为 admin 转发契约测试 + scheduler CRUD 测试；`AgentTaskSchedulerIT`(208)、`AgentTaskCrudIT`(171) 迁入 scheduler；`harnax-entity` 的 `AgentTaskMapperTest`/`AgentTaskLogMapperTest` 随迁、`schema-test.sql` 删对应段与种子；scheduler 现有 3 个 Mockito 单测（startup-load / stop 状态机 / health）保留并按新签名调整。

## 9. Fast-follow（本轮明确不做，只登记）

1. **F1 scheduler 服务自鉴权**：真正装配 `harnax.auth.enabled=true` 的完整方案、端点级 `@InternalOnly`。本轮只有 2.3 的 ~20 行验签拦截器。
2. **F2 生产匿名面收窄**：swagger、`/actuator/prometheus`。本轮只删 nginx location 与宿主端口映射。
3. **F3 `agent-spec` 属主校验缺失**：`sessionId` 由调用方任意传入，伪造 `task-{真实taskId}-...` 即可拿到该任务的 systemPrompt/model/tools。D4 之后这个洞**不增不减**（要猜的仍是自增 ID）。真要修，需给 task 会话引入签名或建立时登记 sessionId。
4. **F4 `agent_task_log.agent_task_id` 列**：现在靠解析 sessionId 字符串定位任务，应改为显式外键列。
5. **F5 任务级权限模式**：`agent_task` 加 `permission_mode` 列，scheduler 建会话时带上，替代 admin 侧硬编码的 `BYPASS`（D5 的产物）。
6. **F6 status 取值收敛**：`0/1`、`0/1/2`、`3/4/5` 三套散落字面量收敛为共享枚举。
7. **F7 日志脱敏**：`SELECT *` 全文下发 `prompt`/`response`/`errorInfo`，无租户/属主过滤。
8. **F8 域内无细粒度授权**：整域只要求"已登录"，`MybatisTenantInterceptor.intercept()` 实为 no-op，属主条件是目前唯一隔离手段。

## 10. 数据迁移（D8）

- `agent_task`：**迁移**。同 MySQL 实例内跨库一次性 `INSERT INTO harnax_scheduler.agent_task SELECT * FROM harnax_admin.agent_task`，`harnax` 用户已对两库都有权限（`init-databases.sql:33-36` 已 GRANT，新库补一行即可）。迁移后必须跑一次 `reconcile()`，因为从旧库带过来的 `QRTZ_*` 不存在、store 需按新库重建。
- `agent_task_log`、`agent_task_execution`：**不迁移**。旧表保留在 `harnax_admin` 直到运维签认 DROP，历史日志在页面上会表现为"任务存在但无历史"，发布公告需写明。
- 发布顺序：建库授权 → 起 scheduler 单实例（Flyway 自动建表）→ 迁 `agent_task` → reconcile 生效 → 起第二实例 → 核对 `QRTZ_SCHEDULER_STATE` 两行 → 观察一个完整 cron 周期。

## 11. 验收标准

1. `mvn verify -Pintegration-test -pl harnax-scheduler` 通过，IT-1~IT-7 全绿。
2. `docker-compose up -d --scale scheduler=2` 后 `QRTZ_SCHEDULER_STATE` 两行；kill 其一，另一个在 15~75s 内接管其未完成 trigger。
3. 带执行中任务重启 scheduler：该行最终为真实结果（1 或 0），**不是** 2 timeout；`stop_grace_period` 生效证据可见于容器退出耗时。
4. 对一个正在执行的任务点"停止"：agent-service 已重启过的场景下，日志状态在秒级变为 5，不出现 timeout。
5. webui 列表（含 `lastRunStatus`/`lastRunTime`）、创建/编辑/启停/删除、立即执行（含二次确认与冲突提示走 40901）、日志弹窗轮询全通；CLI 与小程序各跑一遍。
6. `grep -rn "agentTaskMapper\|agentTaskLogMapper\|AgentTaskExecution" harnax-admin/src/main` **零命中**（改造前实测 24 处 / 4 个文件）；nginx 无 `/api/scheduler/` location；宿主 28084 不可达。
7. **C5 回归**：一个绑定了 OAuth MCP 的 agent 被定时任务调用时，agent-service 仍能解析出正确的 `sys_user.id` 与 `tenantId`（数据来自 scheduler 的 owner 端点而非本地表）。这条必须单独验，因为它的故障形式是"OAuth 工具静默不可用"，不会让任务失败。
