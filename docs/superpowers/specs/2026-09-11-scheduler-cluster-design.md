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
| D6 | 停机语义 | **绝不丢执行**：`waitForJobsToCompleteOnShutdown = true` + `stop_grace_period: 400s` + 部署脚本逐台滚动。**保护范围 = Quartz 认得的路径**（cron 与 `/run-once` one-shot）；手动 `/trigger` 仍起裸 daemon 线程，停机不等它，S4 把它并入 Quartz 之前不受本条保护 | 见 6.2、6.3 的配套约束 |
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

**约束 2**：`waitForJobsToCompleteOnShutdown` **不需要** `SchedulerFactoryBeanCustomizer`——它就是 Boot 的标准属性 `spring.quartz.wait-for-jobs-to-complete-on-shutdown`（Boot 4.0.1 的 `spring-boot-quartz` 模块，`QuartzProperties`；早先这里写的"不是 `spring.quartz.*` 键"是错的）。真正的约束是它的**默认值为 `false`**：必须显式设成 `true`，且必须与容器侧 `stop_grace_period` 配套——job 在 Quartz 线程内同步执行之后，这个开关才有东西可等，而等待一旦超过 `stop_grace_period` 就会被 SIGKILL 截断，落在中间的 execution 与 task_log 行仍要等 housekeeping 收。本分支已按属性落地（`harnax-scheduler/src/main/resources/application.yml` 的 `${QUARTZ_WAIT_FOR_JOBS:true}` + compose 的 `stop_grace_period: 400s`，取值见那里的注释）。

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

**改造前**的缺陷（D7 要消灭的就是它；T5/G5 已修，下面按改之前的样子陈述，判据见下一条修法）：`DefaultAgentRunner.interrupt()` 只看 `agentCache.getIfPresent(sessionId)`，未命中时什么都不做却返回 `success("Stream interrupted")`；scheduler 侧 `RouterClient.sendCommand` 返回 `Unit`、响应体根本未接收。今天的对应物：`interrupt()` 已是返回 `Boolean` 的那一个（`DefaultAgentRunner.kt:253`），`sendCommand` 返回 `CommandDelivery`（`RouterClient.kt:168`）。后果链（若不修就会发生）：agent-service 重启过或 session 映射过期被 reroute → 中断空转、执行继续 → 日志行停在 4 → `expireStale` 判 `2 timeout` → 而任务其实成功产出，结果被丢。用户视角是"点了停止，显示停止中，最后变成超时"。

修法三处：

1. agent-service：`interrupt()` 返回是否真的命中了一个活着的执行。**存活信号两个——在途流 || `activeCalls`**（`DefaultAgentRunner.kt:266`：前者是该 session 还挂在 `activeStreams` 上的 `Subscription`，后者由 blocking 调用注册）。**`agentCache` 命中不算存活证据**——G5 已把这条臂从判据里去掉；缓存里的 wrapper 仍然会被调一次真 `interrupt()`（`:255-256`，那是真中断动作，不是判据）。未命中时返回 `CommandResponse.success = false` 并带明确 message。
   - **为什么缓存那条臂要去掉**：`agentCache` 是 30 分钟 TTL 的缓存，命中只说明"本实例曾服务过该 session"，不说明"现在有东西在推进这次执行"。把它算作命中，会在 owning 节点已经消失后仍然答复"已送达"，那一行于是既没有 owner 也没有终态，最后被回收器写成 `2 timeout`——正是 D7 要消灭的表象。
   - **为什么 `registerCall` 必须早于 agent 构建**（`DefaultAgentRunner.kt:131-135`）：spec 组装 + sandbox 创建要几秒，全程都算"执行在途"。若登记晚于构建，这段窗口里的 session 在判据眼里就是未命中，一次落在构建期的停止请求会把刚起跑的执行当场定成 5，而 sandbox 已经建起来且此后无人释放——留下一个孤儿容器。构建期抛异常时也要靠 `finally` 摘掉登记。
2. router：`/api/router/agent/command` 已经把 `CommandResponse` 原样装进 `ResultVo.data` 回传（含 failover 分支的 failure），**无需改动，仅需加一条透传断言**。
3. scheduler：`RouterClient.sendCommand` 当前签名是 **返回 `Unit`、响应体压根没有接收**（调完 `.body(...)` 直接丢弃，只 log 一行"命令已发送"）。改为返回 `Boolean`（取 `data.success`），`stopTask` 在拿到 `false` 时**立即** `finalizeStopped`（4→5）定态，不等 `expireStale`。**已实现，且落地比这句更强**：返回的是三态 `CommandDelivery`（`RouterClient.kt:168`）——`Delivered` / `Missed` / `Unanswered`，只有 `Missed`（有实例明确答"我这儿没有在途执行"）才当场 4→5；`Unanswered`（命令压根没送到）留在 4 交给执行节点或回收扫描。把它压回 `Boolean` 会把这两种情形混成一谈，于是"路由器不可达"也会被当成"执行已死"而定态。

语义依据：命中不了 = 没有任何进程在推进这次执行 = 它已经死了，立即定态是陈述事实，不是猜测。这也是本修法优于"轮询几秒后自行定态"的原因——后者会在执行真的还在跑时留下孤儿 sandbox。

### 4.4 定态分支与结果不丢

`executeTaskOnce` 收尾时若 `finishExecution` 影响 0 行，现状一律按"被用户停止"处理并走 `finalizeStopped`。但那一行也可能是**已被 `expireStale` 抢先写成 2**：此时 `finalizeStopped`（`WHERE status = 4`）同样 0 行，代码只 `log.warn("already finalised elsewhere")`，**执行结果永久丢失**。

改为三条可区分的分支：被停止（4）→ `finalizeStopped`；已被判超时（2）→ 用一条覆盖 UPDATE 写回真实 status/response，并在 `error_info` 追加"曾被判超时后完成"的标记；两者都不匹配 → 按写回失败告警。

### 4.5 housekeeping

**已实现**（`SchedulerHousekeepingJob.kt`，5 分钟 `SimpleTrigger` 而非 cron——这个清扫与任何用户的日程无关，且用 cron 会在"恰好是留下僵尸的那次重启"期间漏掉一轮）。四条职责，按此顺序：

1. `expireStaleExecutions()`：回收节点已死的 `status=3` 日志行，否则任务永远显示"运行中"；
2. `cleanupOldExecutionLogs(90)`：执行日志保留 **90 天**（`LOG_RETENTION_DAYS`）——每行都是一份 prompt 加完整回复，这张表此前**没有任何删除路径**；
3. `cleanupOldExecutions(7)`：guard 行是记账不是用户数据，**7 天**过期（`GUARD_RETENTION_DAYS`）；`AgentTaskExecutionGuard.cleanupOldExecutions` 此前全仓库零调用方；
4. `cleanupLeakedLocks()`：清理超过 `timeout-seconds × 2` 仍停在 `status=0` 的抢锁泄漏行（抢锁成功但进程在执行前死亡）。

job 注册在 `SchedulerSystemGroup`，与用户任务的 `AgentTaskGroup` 分开——后者每次 reload 会整组删除，混进去的清扫 job 会在下一次 reload 被抹掉且不再重建（注册只发生在启动时）。类上带 `@DisallowConcurrentExecution`：一轮慢过周期的清扫不该再起第二轮，两个多行 DELETE 会互相阻塞，死锁的代价是两轮都没了。

RAM store 下**每个实例各自扫**（四个操作都幂等，但确有重复功）；换到 JDBC store 后同一份注册自动变成集群单例，这里一行都不用改。

`expireStale` 保留现有调用点（启动加载、并发判断前），并增加在 housekeeping 内调用。

## 5. 跨服务契约变更（需同步发布）

| # | 契约 | 变更 | 影响面 |
|---|---|---|---|
| C1 | 定时任务 sessionId 格式 | `task-{taskId}-{uuid}` → `task-{taskId}-{agentId}-{uuid}` | 生成方 1 处（`SchedulerServiceImpl.kt:317`）；**解析方 2 处**：`InternalApiController` 的 `resolveFromTask`（`split("-", limit=3)` → `limit=4`）与 `McpSessionOwnerResolver.fromTask`（`substringBefore('-')`，**无需改动**，仅纳入回归用例）；其余三处依赖都是 `startsWith("task-")`，不受影响。`agent_task_log.session_id` VARCHAR(64) → **VARCHAR(128)**（新格式实占 49~55）。**并给 `resolveFromTask` 加 agentId 一致性校验**（F3 的第三刀 C）：sessionId 的 `{agentId}` 段今天被完全忽略，只用 `parts[1]`，改为与该行 `agent_task.agent_id` 比对、不一致即拒绝，把"任意字符串"收成"scheduler 真发出来的形态"。**此项只能与 C1 同批**：格式仍是三段时先收紧校验，会把所有真实任务会话一并拒掉 |
| C2 | INTERRUPT 返回值语义 | 从"恒为 success"改为"如实反映是否命中活跃执行" | agent-service 实现 + scheduler 消费；router 透传不改 |
| C3 | trigger 冲突识别 | 从字符串匹配文案改为业务码 `40901` | scheduler 出码、admin 透传、webui 改判 code（替掉 `index.tsx:122` 的 `includes('already running')`） |
| C4 | admin→scheduler 转发头 | `X-Forwarded-User` / `X-Tenant-Id` + internal JWT | scheduler 新拦截器；`X-Forwarded-Tenant` 必须忽略（浏览器可伪造，见原文档 8.2） |
| **C5** | task 属主查询 | 新增 `GET /api/scheduler/agent-tasks/{id}/owner` → `{creator, tenantId}` | admin 的 `McpSessionOwnerResolver.fromTask` 改调此端点（替代 `agentTaskMapper.selectAnyById`）。冷路径，见 2.1 的残留说明 |

**已落地的业务码（S0/S1）**：

| 码 | 常量 | 出码方 | 语义 |
|---|---|---|---|
| `40901` | `CODE_EXECUTION_IN_PROGRESS`（`SchedulerController.kt:162`） | scheduler，admin 原样透传 | 该任务已有活着的执行：既覆盖本实例 `concurrent=0` 的业务拦截，也覆盖集群抢锁失败——两者对用户的含义相同，重试或回滚都不是调用方的问题 |
| `40902` | `CODE_SCHEDULER_SYNC_FAILED`（`AgentTaskServiceImpl.kt:291`） | admin | **定义已存库、但没有任何 scheduler 重载它**。与"没保存成功"必须可区分：前者数据是对的、只需重试调度，后者要重来一遍 |
| `40903` | `CODE_SCHEDULER_DISABLED`（`SchedulerController.kt:169`） | scheduler，admin 透传 | 目标实例 `scheduler.enabled=false`，拒绝一切调度写操作。启停/触发/reload 都出这个码，前端按提示展示而非报 500 |

C1 的格式约定是 scheduler 与 admin 之间的**隐式契约**：`resolveFromTask` 必须做显式校验（段数、两段的数字解析），失败时抛带明确文案的异常；格式本身写进本文档作为契约条目。

## 6. 部署形态（compose 固定 2 实例）

### 6.1 库

- `docker-new/sql/init-databases.sql` 增加 `CREATE DATABASE harnax_scheduler` + 对 `harnax` 用户 `GRANT`（与既有四个库同构，两行）。
- scheduler 开 Flyway：`enabled: true`、`locations: classpath:db/migration`、`table: flyway_schema_history_scheduler`（**独立 history 表名**，避免与 admin 在同一 MySQL 实例里混淆）。
- 迁移脚本：`V1__quartz_tables.sql`（官方 11 张 `QRTZ_*`，剥掉所有 DROP 语句、补齐每表 COMMENT）、`V2__agent_task_domain.sql`（三张业务表按库表规范重写，含 `session_id VARCHAR(128)`）。

### 6.2 两实例与优雅停机（D6 配套）

- 删 `container_name: harnax-scheduler`（compose:270）与宿主映射 `28084:8084`（compose:294），改 `expose: ["8084"]`。
  - 状态：映射改 `expose` 已由第二轮 R1 落地（compose 里已无 `28084`，故上面两处行号会漂移），`container_name` 仍待 S1。
- **不在 compose 里声明 `deploy.replicas`**，实例数由部署脚本显式 `--scale scheduler=2` 决定。理由：6.3 的逐台滚动要在"停掉其中一台、补齐到 2"之间来回切换，声明式 replicas 会让 `--no-recreate` 的收敛行为变得难以推理。
- `stop_grace_period: 400s` + `spring.quartz.wait-for-jobs-to-complete-on-shutdown=true`。**均已落地**（compose 的 scheduler 服务、`harnax-scheduler/src/main/resources/application.yml`，两处都有把算式写出来的注释）。**400 = 300 + 60 + 20 + 8 + 4**：chat 读超时 300s + `clearSession` 上限 60s + 两次调用各 10s 的 connect 预扣 20s + 定态写回与释放抢锁行 8s + Spring 关停钩子 4s（合计 392，向上取整到 400s；逐项推导在 compose 那段注释里，改任何一个数都要回到那里重算）。`clearSession`（快照上传 + 容器销毁）现在有自己独立的读超时 `min(scheduler.clear-session-timeout-seconds=60, timeout-seconds)`，不再沿用 chat 的 300s——沿用的话一次执行最坏占用是 600s，旧推导"300 + 40 + 20 = 360s"两头都不成立（clearSession 不是 40s，360s 也远小于真实的 600s），SIGKILL 会正好落在 DELETE 中间。**为什么不是刚好等于 timeout**：超时只管得到 router 调用，之后那几十秒才是把一次跑完的执行落成定态行的动作，砍掉它就把正常完成记成超时。
  - **保护范围只到 Quartz 认得的路径**（cron 与 `/run-once`）。手动 `/trigger` 走 `SchedulerServiceImpl.triggerManually` 起的裸 daemon 线程，Quartz 不知道它在跑，因此 `waitForJobsToCompleteOnShutdown` 不等它、grace 也不覆盖它：执行中被重启就是 `status=3` 的行 + `status=0` 的锁行。S4 把 one-shot 并入 Quartz 之前，**不要在任务执行中重启 scheduler**。
  - 这一项原先归在 S2，实际与 S1 同期做掉了：job 一旦在 Quartz 线程内同步执行，"停机不等就等于把一次正常执行切成僵尸行"立刻成立，不必等 JDBC store。属性方式即可（见 3.1 约束 2），`SchedulerFactoryBeanCustomizer` 是多余的。
  - 本项剩下的只有 6.3 的逐台滚动脚本。
- 时钟：compose 已统一挂载 `/etc/localtime`；集群要求各节点时钟偏差 < 1s，宿主机 NTP 记入运维 checklist（Quartz 集群对时钟敏感，NTP 步进会造成误判接管）。

### 6.3 部署脚本必须逐台滚动

`docker-new/deploy-service.sh:132` 现在是 `up -d --force-recreate --no-deps scheduler`，`--force-recreate` 作用于整个 service → **两实例同时下线**，最长 400s 全集群无调度。触发在 `QRTZ_TRIGGERS` 堆积，节点回来后按 misfire 处理，而 `concurrent=0` 用的是 `withMisfireHandlingInstructionDoNothing` → **错过的触发被永久跳过**，与 D6"绝不丢执行"的初衷正好相反。

改为逐台：`docker stop -t 400 <其中一台容器>` → `docker-compose up -d --no-deps --scale scheduler=2 --no-recreate scheduler`（补齐缺失的那台，不动仍在跑的那台）。

### 6.4 暴露面

- 删除 `docker-new/nginx.conf:178` 的 `location /api/scheduler/`（admin 走 `HARNAX_SCHEDULER_URL: http://scheduler:8084` 内网服务名，不需要 nginx）。
  - 状态：第二轮 R1 已删除该 location（原位留了一段禁止回加的注释，行号已漂移），本条只剩下面那项未做。
- scheduler 的 swagger 与 actuator 生产面收窄：`SWAGGER_ENABLED=false`、`management.endpoints.web.exposure` 去掉 `prometheus` 的匿名暴露（现随 8084 匿名可达）。属 F2，本轮只做部署层。

## 7. 里程碑

原 M1→M5 的顺序按"改动类型"分组而非按"依赖"分组，有两处会导致中间态比现状更危险，本表已修正：

- **修正 A**：同步执行（原 4.4）提前到 JDBC 集群之前，见 4.1 的顺序要求。
- **修正 B**：reconcile（原 4.1）必须与 JDBC store 同期上线，否则存在"共享 store + 全删重建"的窗口，任一节点重启会报掉全集群任务。

| 步 | 内容 | 人日 | 可独立发布 | 状态（`fix/scheduler-exec-semantics`） |
|---|---|---|---|---|
| **S0 行为修复** | C3（40901 三端同步）；CLI `logId`→`id` **并补 `task stop` 子命令**（当前 task.go 里不存在该命令，所谓"CLI 停止从未生效"的真实原因是功能缺失而非字段名）；删死代码（admin `AgentTaskLogService.save()`、`/internal/agent-tasks/{taskId}/spec` 端点及测试、`SchedulerClientImpl.kt:35` 与事实相反的注释） | 0.5 | ✅ | **已完成**。死代码那条比原清单多删一处：admin 端点的唯一"调用方" `AdminApiClient.getTaskAgentSpec` 自身也是 `@Deprecated` + 全仓零引用，整条死链一并摘掉 |
| **S1 执行语义** | 4.1 同步执行 + 双 job 类；4.3 中断命中语义（D7，跨三侧）；4.4 定态分支 + 3.5 超时统一；4.5 housekeeping | 2.5 | ✅ | **已完成**，另把 D6 的优雅停机配置（3.1 约束 2 + 6.2 的 `stop_grace_period`）提前做了——同步执行一落地，"停机不等"就立刻制造僵尸行 |
| **S2 库 + 集群 + 对账 + 部署形态** | 6.1 建库与两个 Flyway 脚本；3.1 quartz 集群配置 + 约束 2/3；3.3 reconcile；D8 数据迁移（`agent_task` 一次性 `INSERT ... SELECT`）；6.2/6.3 compose 与逐台滚动；6.4 删 nginx 与宿主映射 | 4 | ✅（内部必须原子） | ⏳ 未开始（其中 6.4 的暴露面与 6.2 的停机两项已提前落地） |
| **S3 域搬迁** | 三实体 + 三 mapper + 三 XML 从 `harnax-entity` 移入 scheduler（含 `@MapperScan`、`type-aliases-package`、XML 全限定 type）；scheduler 自带 `Page`/异常副本 + pagehelper；`/api/scheduler/agent-tasks/**` 12 端点 + C5 owner 端点 + 2.3 拦截器；admin `AgentTaskController` 瘦身为鉴权+转发、删 service/DTO/广播；C1 sessionId 编 agentId；**admin 侧 24 处 mapper 引用全部清零（实测 4 个文件：`AgentTaskServiceImpl` 12、`InternalApiController` 4（含 290 与 713 两处 `selectAnyById`）、`AgentTaskLogServiceImpl` 5、`McpSessionOwnerResolver` 3）** | 3.5 | ❌ 必须单 PR（admin 编译断裂） | ⏳ 未开始 |
| **S4 收口** | 4.2 one-shot 合并 + 删 `taskExecutor`；`scheduler.reconcile.drift` 指标；健康/指标改集群语义；第 8 节测试补全；文档同步 | 2 | ✅ | ⏳ 未开始。`scheduler.jobs.scheduled` **仍是本实例视图**（store 还是 RAM，见第 9 节 F9）；IT-1/2/4/5 依赖真库，与 S2 同批 |

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
| **IT-6 中断未命中** | 触发条件按 4.3 的判据写：**该 session 既没有在途流也没有在途调用**时 `interrupt()` 返回未命中（缓存里还留着 wrapper **不算**命中，G5），scheduler 立即把行 4→5，`expireStale` 之后不再改动它 | 覆盖 D7。这是本轮唯一跨三个服务的断言，也是用户可见的直接收益 |
| **IT-7 超时竞争** | 执行结果返回前行已被 `expireStale` 写成 2 → 真实结果与状态被覆盖写回，`error_info` 含超时标记 | 覆盖 4.4"结果不丢" |

既有测试处置：`AgentTaskServiceImplTest`(603) 迁 scheduler 重写为真库 IT；`SchedulerClientImplTest`(452) 随广播删除、保留转发用例；`AgentTaskLogServiceImplTest`(271) 删除（被测对象是死代码）；`AgentTaskControllerTest`(657) 拆为 admin 转发契约测试 + scheduler CRUD 测试；`AgentTaskSchedulerIT`(208)、`AgentTaskCrudIT`(171) 迁入 scheduler；`harnax-entity` 的 `AgentTaskMapperTest`/`AgentTaskLogMapperTest` 随迁、`schema-test.sql` 删对应段与种子；scheduler 现有 3 个 Mockito 单测（startup-load / stop 状态机 / health）保留并按新签名调整。

## 9. Fast-follow（本轮明确不做，只登记）

1. **F1 scheduler 服务自鉴权**：真正装配 `harnax.auth.enabled=true` 的完整方案、端点级 `@InternalOnly`。本轮只有 2.3 的 ~20 行验签拦截器。
2. **F2 生产匿名面收窄**：swagger、`/actuator/prometheus`。本轮只删 nginx location 与宿主端口映射。
3. **F3 `agent-spec` 属主校验缺失**：🟡 **前缀策略已落地，但只覆盖 `task-`（`SessionAccessGuard` + `PrivilegedSessionPrefixes`，本批只做 B）**。原本的状况：`sessionId` 由调用方任意传入，router 侧唯一的归属校验 `SessionAccessGuard.requireAccessible` 只认 admin 的 `session` 表，而 `task-`/`chn-` 按设计**不在这张表里**（它们来自 `agent_task` / `channel`）→ lookup 恒为 `Unknown`、而 `Unknown` 是放行。于是任何一枚有效凭据——用户自己的登录 JWT 就够——伪造 `task-{受害者taskId}-...` 即可拿到该任务的 systemPrompt/model/tools/skills/MCP 清单，且 `resolveFromTask` 硬编码 `permissionMode = "BYPASS"`（免工具确认）。守卫只认一张表、admin 的解析认四张表，这个不对称就是洞本身。
   已落地的 B：`task-` 归为"由服务端自行决定的会话"，只有 `AuthContext.userId == null` 的内部调用方（SYSTEM key、内部服务令牌）能用；带终端用户身份的调用方（登录 JWT 或代表用户的外部 API key，即 `userId != null`）在 **lookup 之前**按前缀拒绝（判定同时位于 `tenantId ?: return` 之前，无租户的终端用户 key 也拦得住）。`web-`/`mp-` 行为完全不变，仍走租户比较。stream 端点（`/chat/stream`、`/confirm`）把拒绝转成 `ErrorChatEvent`（`FORBIDDEN`）+ `EndEventChatEvent`，不再让异常落到 `GlobalExceptionHandler` 上把 JSON 错误体写成 event-stream。
   **`chn-` 已从特权前缀集合移出**——首版（`89138c3`）把它与 `task-` 一并拦下，过宽。两个前缀的**可利用性不对等**：`task-{taskId}` 的 `{taskId}` 是自增整数，一枚有效凭据就能从 1 数到 N，读遍别人的任务配置，这是必须堵的枚举面；`chn-{uuid}`（生成处 `ChannelServiceImpl.kt:145`）是 UUID，不可枚举，能命名它的人本来就早已掌握那个具体 id。而 `chn-` 侧**确有合法的终端用户只读流量**：webui 频道管理页拿 `batchGetWorkspaceStatus(channels[].sessionId)` 查沙箱状态（`pages/channel/index.tsx:112-127`），并把同一个 sessionId 交给 `WorkspaceDrawer`（`:311-343` 的 Sandbox 列与 Workspace 按钮、`:538-542` 挂载 → `files`/`read`/`download`，`pages/session/components/WorkspaceDrawer.tsx:50,94,291`）；这些 sessionId 就是 `chn-{uuid}`，凭据是登录时下发的**用户绑定 key**（`AuthServiceImpl.kt:104-127` → `ApiKeyServiceImpl.kt:196` 写入 `userId`），即 `userId != null`，正好落在首版的拒绝里。净效果是 Sandbox 列恒为 `-`、Workspace 按钮恒 `disabled`，且失败被 `index.tsx:125-127` 的空 catch 吞掉，**静默**。而这次拒绝换来了什么？**没有任何可验证的授权**：admin 的 `/sessions/{id}/info` 只查 `session` 表，对 `chn-` 恒为 `Unknown`，这里根本不存在一个能被保护的归属查询。一条拦不住攻击者、又关掉真实功能的规则不值其代价，所以 `chn-` **恢复本分支之前的行为**（走既有租户比较分支，与本分支之前完全一致）；`chn-` 真正的归属保护归入下面的 A——那是"加检查"而不是"撤访问"，才是有信息量的修法。
   **剩余面（A，本批不做）**：`/internal/sessions/{id}/info` 仍只查 `session` 表，应扩为按前缀解析 `agent_task` / `channel` 的归属租户，使跨租户判定对四类会话全部生效——**`chn-` 的终端用户跨租户读就挂在这一条上**：它今天的实际状态与前缀规则封堵之前的 `task-` 相同（lookup 恒 `Unknown` → 放行）。此项改变的是既有跨租户策略（今天 `chn-` 的跨租户访问被静默允许），单独一刀做会连带影响未参与本次改动的行为，需要单独设计与验证；`SessionAccessGuardTest` 已钉住"一旦 admin 能回答 `chn-`，跨租户即拒"。纵深防御的 C（`resolveFromTask` 校验 sessionId 的 agentId 段与该行 `agent_id` 一致）**前提未成立**，随 C1 一起落地，见 C1 条目。
4. **F4 `agent_task_log.agent_task_id` 列**：现在靠解析 sessionId 字符串定位任务，应改为显式外键列。
5. **F5 任务级权限模式**：`agent_task` 加 `permission_mode` 列，scheduler 建会话时带上，替代 admin 侧硬编码的 `BYPASS`（D5 的产物）。
6. **F6 status 取值收敛**：`0/1`、`0/1/2`、`3/4/5` 三套散落字面量收敛为共享枚举。
7. **F7 日志脱敏**：`SELECT *` 全文下发 `prompt`/`response`/`errorInfo`，无租户/属主过滤。
8. **F8 域内无细粒度授权**：整域只要求"已登录"，`MybatisTenantInterceptor.intercept()` 实为 no-op，属主条件是目前唯一隔离手段。
9. **F9 `scheduler.jobs.scheduled` 是本实例视图，不是集群视图**：gauge 读的是本进程 Quartz store 的 `getJobKeys(AgentTaskGroup)`（`QuartzJobInventory.kt`），store 还是 `memory` 时每个实例各自注册全量任务，于是 N 台各报自己的数、看板取哪台都一样"看着对"。**S2 换 JDBC 集群 store 后同一个表达式的含义会静默跳变成集群视图**（集群里只有一台 fire，但 store 是共享的，所以数值不降反升的语义完全不同）。届时必须同时重解读既有看板与告警阈值，并把指标改按 `instanceId` 之外再打一层 store 来源标签。
10. **F10 `agent_task_execution` 缺索引**：✅ **已落地（第三批次 G4，`V27__add_agent_task_execution_sweep_indexes.sql`）**，补的正是 `KEY idx_status_create_time (status, create_time)` 与 `KEY idx_create_time (create_time)`，`harnax-entity/src/test/resources/schema-test.sql` 同步。原本的状况：V1 建的这张表只有 PK、`uk_task_trigger(task_id, trigger_time)`、`idx_task_id`、`idx_trigger_time`，**`status` 与 `create_time` 都没有索引**，而 S1 的 housekeeping 把 `deleteStaleRunning`（按 `status` + 时间）与 `deleteOldExecutions`（按 `create_time`）挂成了每 5 分钟一轮，这张表又按触发次数线性增长 → 每轮两次全表扫，且扫描范围锁与 `tryAcquireLock` 的 INSERT 在 `uk_task_trigger` 上互顶。真库上的 `EXPLAIN` 命中验证随 B1 一起在有 Docker 的环境补跑。
11. **F11 停止意图只有一个瞬态列承载**：用户"点了停止"这件事目前**只写在 `agent_task_log.status = 4` 上**，没有独立的标记位，于是它只活到下一次回收扫描为止。G6 补齐的是**能区分出来的那一半**：执行线程先读到 4、随后 4 被 sweep 改成 2，`finalizeStopped` 的 4 守卫失配 → 重读发现是 2 → 改走 `reclaimExpired` 写 5（`SchedulerServiceImpl.kt:524-550`）。**没补齐的那一半**是 sweep 在**那次重读之前**就把 4 改成 2：线程按 2 分支写回自己真实的 0/1，用户那一次停止就此从记录上消失，而且**无法与"根本没被停止过"区分**——`expireStale` 已经把 `error_info` 覆盖成超时文案，4 存在过的证据被销毁了。这不是"超时"那样的错误标签，但这一行仍然不对。根治需要给行加一个 stop/owner 标记（`stop_requested` 列，或 `agent_task_execution` 上记 owner），让停止意图不依赖 status 这一瞬态——属 S2/S3 的库改动，本轮不动行为，只在 `SchedulerServiceImpl.kt:510-561` 与 `AgentTaskLogMapper.xml` 的 `expireStale`/`reclaimExpired` 注释里标明各守住哪个窗口、哪个窗口未守。

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
