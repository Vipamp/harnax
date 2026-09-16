# 定时任务域集群化与业务下沉 · 设计规格

- 日期：2026-09-11
- 状态：**S0/S1 已发布（执行语义）；S2 已随发布 1 落地（`feat/scheduler-cluster-cutover`，见下方修正 C 的范围注）；S3 域搬迁与数据源切换已随发布 2 落地（同一分支，见修正 D——其中的 D8 数据迁移被用户「无历史数据」的决定取消）；S4 收口除测试外已完成**。实现计划：发布 1 = `docs/superpowers/plans/2026-09-14-scheduler-jdbc-cluster.md`，发布 2 = `docs/superpowers/plans/2026-09-14-scheduler-domain-migration.md`，发布 3 = `docs/superpowers/plans/2026-09-14-scheduler-finishing-and-ownership.md`。**S3 的"完成"同样不含集成测试**：交付机没有 Docker，`AgentTaskOwnerScopeIT`（IT-3）与 `AgentTaskMapperSemanticsIT` 与发布 1 的三个 `*IT` 一样从未执行。
- 范围：`harnax-scheduler`、`harnax-admin`、`harnax-entity`、`harnax-session-router`、`harnax-agent-service`、`harnax-cli`、`harnax-webui`、`docker-new`
- 关联文档：[prod_doc/agent-task-scheduler.zh-CN.md](../../../prod_doc/agent-task-scheduler.zh-CN.md)（现状调研与全流程说明）。本文取代其第 10.3、10.4、11、12 节的方案与里程碑；那份文档的其余章节仍是现状事实的来源。

## 0. 要解决的三个问题

| # | 问题 | 现状证据 |
|---|---|---|
| ① | 调度器单实例，进程挂起即停止调度 | **（2026-09-11 的快照，S2 已把这条翻过来）** 当时 `spring.quartz.job-store-type` 默认 `memory`、无 `QRTZ_*` 建表脚本、无 `isClustered`。现状见本文 3.1 与 `docs/deploy-harnax-scheduler.md` |
| ② | 任务定义在 admin、调度执行在 scheduler，两边靠广播与回调缝合 | **（2026-09-11 的快照）** 当时每次 CRUD 后 `schedulerClient.reloadTasks()` 广播给所有节点——S2 已把它塌缩成一次转发（见 3.3 与部署文档）；"CRUD 在 admin"这件事随 **S3（发布 2）** 消失。agent-service 用 `task-` 前缀反查 admin 组 spec（`InternalApiController.resolveFromTask`）曾是"仍在"的那半，**发布 2 的 C1 已把它退化成一次纯字符串解析**（不再读表，见 §5 C1 与残留项 F15） |
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
| D6 | 停机语义 | **绝不丢执行**：`waitForJobsToCompleteOnShutdown = true` + `stop_grace_period: 400s` + 部署脚本逐台滚动。**保护范围 = 本模块的全部执行路径**：4.2 落地之后，cron 与手动执行在 store 里是同一类对象（`/trigger` 与 `/run-once` 都只投 one-shot），停机两条都等 | 见 6.2、6.3 的配套约束。**同一事实的另一面**：一次手动执行占用一个 Quartz worker，容量规则（含"1~2 个 worker 不是可用配置"的下限）写在 6.2 与 `docs/deploy-harnax-scheduler.md` |
| D7 | 中断未命中的修法 | **如实返回命中结果**（跨 agent-service / router / scheduler 三侧） | 拒绝"靠超时猜"的方案，避免孤儿 sandbox |
| D8 | 数据 | ~~**迁任务定义，不迁历史日志**~~ → **已被用户决定取消：什么都不迁** | 原定理由是"同实例跨库 `INSERT ... SELECT` 成本极低；历史 `agent_task_log` 价值低且量大"。**用户在发布 2 实施前确认本部署没有历史数据**，于是迁与不迁都不成立：新库从空开始，切口后由用户在界面重建任务，`harnax_admin` 的旧表切口后直接 DROP（见 §10 与修正 D） |

D8 是本次评审中由我代替用户做的一个判断（原文档第 10.4 节写的是"不迁数据、人工重建"，那是针对不建独立库的形态）。**发布 2 实施前用户推翻了它**：没有历史数据，所以既没有可迁的定义也没有可失去的历史——原方案与它的前身在这里落到同一个结果，只是**不再有中间那步搬数据**，也因此不再有"历史变空"这类要公告的损失、不再有观察期、不再有运维签认的 DROP 脚本。§10 记的就是这个状态。

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

**这条有一个残留，自检时发现，原文档未记**：`McpSessionOwnerResolver.fromTask`（`harnax-admin/.../util/McpSessionOwnerResolver.kt:60-68`）在解析 MCP OAuth 属主时要拿任务的 `creator` 与 `tenantId`。改造前它靠的是本地 `agentTaskMapper.selectAnyById(taskId)`；D4 消不掉它——agentId 是给 spec 装配用的，属主是另一份数据。所以：

- 它**不是** hot path（只在 agent-service 为一个 task 会话解析 OAuth MCP token 时触发），与 2.2 的 spec 链路不同；
- 解法是 scheduler 提供 `GET /api/scheduler/agent-tasks/{id}/owner`（返回 `creator` + `tenantId`，实际形态连 `agentId` 一起给），即契约 **C5**；
- D4 省掉的是 spec 热路径上的那一跳，**不是**"任何 agent-id 反查端点都不需要"。那句话要以 C5 补正后才成立：归零的是**表读取**，跨服务查询仍留一条冷路径。

**发布 2 已按这条落地**：resolver 改走 `SchedulerClient.taskOwner`（`McpSessionOwnerResolver.kt:83-100`），拿不到就返回 null 并打 WARN（`:90`/`:94`/`:98`）——它的故障形式是"那次执行的 OAuth 工具静默不可用"，不是任务失败。**id 的读法没换**：`:61` 仍是 `removePrefix("task-").substringBefore('-').toLongOrNull()`，段数无关，而且这是有意的（同文件 `:66-67` 写明"这里只读第一段，C1 放在尾部之前的 agentId 对它保持不可见——属主是人，agent 不是"），它同时是一条被测试钉住的性质（`McpSessionOwnerResolverTest.kt:173-178`「四段 id 仍解析出同一位创建人」）。严格四段解析只在热路径那一处：`InternalApiController.kt:370-371` 用 `TaskSessionId.parse`，三段形态直接拒——修正 D 说的"混跑不可能悄悄进行"靠的是那一条，不是这里。

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
  - **落地形态（发布 2）与本句有两处不同**：`support/InternalCallerInterceptor` 实际是 147 行（多出来的是注释），且注册路径是 `/api/scheduler/**` 的**全部**接口——**读面也在内**。第二处是有意的偏离，理由是发布 2 加了 C5 那个 owner 读端点（凭一个任务 id 就能读出创建人与租户），理由与写面逐字相同就不留豁免；记在 §5 C4 与 `docs/deploy-harnax-scheduler.md` 的「认证边界」。
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

`useProperties: true` 下 JobDataMap 只允许字符串值，**放 Long 会抛 `ObjectNotSupportedException`**。所以设计时的改动是把 `scheduleTask` 与 `runTaskOnce` 里当时的 `jobDataMap.put("agentTask", task)` 换成只放 `taskId` 字符串，`AgentTaskJob` 在 fire 时回查 `agent_task` 拿最新定义。**这一条已由发布 1 落地**：`TaskQuartzRegistrar.KEY_TASK_ID` 是注册的唯一写入口，实体不再进 store。这顺带修掉一个既有缺陷：任务改过 prompt 之后，已注册的 job 里仍是旧实体。

### 3.3 reconcile 取代全删重建

**（下面是设计时的现状；本节已由发布 1 落地——`loadTasksToScheduler()` 已从 `SchedulerService` 删除，`SchedulerServiceImpl` 的对应位置换成 `reconcileTasks()`，引用的行号是当时的快照）**`loadTasksToScheduler()` 的第 1 步是"把 `AgentTaskGroup` 里所有 job 删掉"。共享 JobStore 下这等价于**任一节点重启就报掉全集群任务并重注册**。改为 diff 收敛：

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

### 4.1 同步执行 + 双 job 类 —— ✅ 已落地（S1）

**改造前**：`AgentTaskJob.execute()` 起 daemon 线程后立刻返回，Quartz 于是认为 job 秒完、`QRTZ_FIRED_TRIGGERS` 不留行。后果是三个能力**同时失效**：故障接管接不到正在跑的执行、`waitForJobsToCompleteOnShutdown` 空转（D6 直接落空）、`@DisallowConcurrentExecution` 无对象可互斥。

改造：

- 在 Quartz 线程内同步执行完整链路（调 router → 定态 → clearSession），删掉那条线程；
- 摘掉 `InterruptableJob`（其 `interrupt()` 是空实现，真中断走 INTERRUPT 命令），改回 `Job`——同时清掉文档 13.5 记的名义接口；
- 新增 `AgentTaskNonConcurrentJob`（带 `@DisallowConcurrentExecution`），注册时按 `task.concurrent` 选 job 类（`TaskQuartzRegistrar.jobClassFor`，cron 注册与手动 one-shot 共用这一个决定）。改造前 `concurrent=0` 只靠 `withMisfireHandlingInstructionDoNothing`，那只在触发被错过时生效，拦不住重叠执行。

**顺序要求：本项必须早于或同于 3.1 上线。** 这是本次评审对原 M1→M4 顺序的实质修正。

### 4.2 手动执行合并为 one-shot —— ✅ 已落地（发布 3）

**改造前**是两套并行逻辑：`triggerManually`（抢锁 + 起线程，绕过 Quartz）与 `runTaskOnce`（投 `AgentTaskGroup_ONCE` one-shot trigger），而 admin 用的是前者。

**现在的样子**：`triggerManually` 已从接口与实现里删除，`/tasks/{id}/trigger`（admin 与 CLI 在用）与 `/tasks/{id}/run-once` 都落到同一个 `SchedulerServiceImpl.runTaskOnce`，它是唯一的手动投递路径，两条 HTTP 契约不变（只有成功文案各自保留）。投出去的东西是：`AgentTask_{id}_ONCE_{uuid8}` @ `TaskQuartzRegistrar.GROUP_ONCE`，trigger 同名加 `_trigger`、`startNow()`、**不带 `storeDurably()`**（跑完的 one-shot 是可再生垃圾，durable 版会留下一行 reconcile 永远读不懂的 job），JobDataMap 只放 `taskId` 字符串，job 类仍由 `jobClassFor(task)` 按 `concurrent` 选——与 cron 那条注册完全同一个契约。集群里任意节点 fire，进程在 fire 前崩掉由 Quartz 补火（持久 + 非 durable 的 trigger 在 MisfireHandler 那一轮被重新置回 WAITING，不是丢弃）。

三道闸口的**位置**是这一节的关键，因为它们的可见性不同：

- 投递时只有一道，`blocksManualRun(task)` = `concurrent == 0 && hasActiveRunningExecution(taskId)`，命中即 `runTaskOnce` 返回 false → 业务码 `40901`。这是"同一任务不许并发手动跑"的业务拦截（原 `hasActiveRunningLog` 的当代形态）。
- fire 时另有两道，都在 `AbstractAgentTaskJob.run()` 里、**都早于插 `agent_task_log` 那行**：`concurrent=0` 且已有活执行（重叠闸口）、`guard.tryAcquireLock` 输给别的节点（集群锁，降级为兜底防线）。命中任一条就是 `log.info` + `return`，**没有日志行**。所以"HTTP 200 之后打开日志列表"完全可以是空的，这是设计而不是缺陷（运维读法见 `docs/deploy-harnax-scheduler.md` 的「优雅停机」）。
- `@DisallowConcurrentExecution` 帮不上忙：它是按 JobDetail 互斥的，而一个任务握着两个（cron 的 `AgentTask_{id}@AgentTaskGroup` 与每一次点击的 `AgentTask_{id}_ONCE_*@AgentTaskGroup_ONCE`），只有那把按 task 键的读能跨过去。

**`taskStatus` 守卫对 one-shot 例外**，且是刻意的：那个守卫存在是因为 store 里的 cron job 是 `agent_task` 的滞后副本，而一次点击的注册本身就是用户几秒前刚表达的意图，所以**任务在点击与 fire 之间被暂停也照样跑**；软删除（`active != 1`）两边都拒。判定读的是 group 常量，不是 data-map 标记——`useProperties: true` 下 JobDataMap 只能放字符串。

顺带清理**已做**：`SchedulerConfig.taskExecutor()`（core 2 / max 10 / queue 50）随本项删除，`SchedulerConfig` 现在只剩 `restClient()`。留一行事实备查：`SchedulerApplication` 上的 `@EnableAsync` 因此在本模块已无任何使用者（全模块零 `@Async`），它没有被一并删掉——删它是另一次改动，不要把它当"手动执行还在用异步池"的证据。

容量代价是同一件事的另一面，写在 6.2 与部署文档：一次手动执行占用一个 Quartz worker，`SimpleThreadPool` 无队列。

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

job 注册在 `SchedulerSystemGroup`，与用户任务的 `AgentTaskGroup` 分开——后者是 reconcile 收敛的目标：**`agent_task` 没有对应行的 job 正是 diff 要删的东西**，所以混进去的清扫 job 会在下一轮对账里被抹掉，而清扫只在启动时注册，它不会自己回来。（这一条在 S2 之前同样成立，只是机制不同：那时 reload 是"整组删掉再重建"。）类上带 `@DisallowConcurrentExecution`：一轮慢过周期的清扫不该再起第二轮，两个多行 DELETE 会互相阻塞，死锁的代价是两轮都没了。

**已按预期发生**：RAM store 下**每个实例各自扫**（四个操作都幂等，但确有重复功），换成 JDBC store 后同一份注册自动变成**集群单例**，代码一行都没改。运维后果写在 `SchedulerHousekeepingJob` 与 `SchedulerServiceImpl.registerHousekeepingJob` 的注释里：回收在"集群里至少有一台开着"时持续存在，一台禁用节点没有也不需要私有的回收路径；唯一没人回收的形态是"只有一个副本且它是关着的"。

`expireStale` 保留现有调用点（启动加载、并发判断前），并增加在 housekeeping 内调用。

## 5. 跨服务契约变更（需同步发布）

| # | 契约 | 变更 | 影响面 |
|---|---|---|---|
| C1 | 定时任务 sessionId 格式 | ✅ **已随发布 2 落地**。`task-{taskId}-{uuid}` → `task-{taskId}-{agentId}-{uuid}`。生成与解析收在唯一出处 `com.agnetix.harnax.common.session.TaskSessionId`（`harnax-common/.../session/TaskSessionId.kt`，放公共模块是因为两侧都要用而 admin 不能依赖 scheduler）：生成侧 `SchedulerServiceImpl.insertRunningLog` 一次 `of(task.id, task.agentId)`，两个段出自同一次 `selectAnyById`；消费侧 `InternalApiController.resolveFromTask`（`:369-373`）严格四段解析、拿不到就抛 `Invalid task sessionId: expected task-{taskId}-{agentId}-{uuid}`，**三段形态被拒**，混跑因此不可能悄悄进行（见修正 D）。`agent_task_log.session_id` VARCHAR(64) → **VARCHAR(128)** 已落（`V2__agent_task_domain.sql`）。其余三处依赖都是 `startsWith("task-")`，段数无关，各自补了一条"四段仍识别为任务会话"的断言。**原定的"并给 `resolveFromTask` 加 agentId 与该行 `agent_task.agent_id` 的一致性校验"（F3 第三刀 C）没有落地，也不可能在 S3 之后落地**——域搬走之后 admin 读不到那张表；替代物是同源的单源生成 + 严格格式校验 + C5 的带 agentId 冷路径核对，**残留面登记为 §9 F15** |
| C2 | INTERRUPT 返回值语义 | 从"恒为 success"改为"如实反映是否命中活跃执行" | agent-service 实现 + scheduler 消费；router 透传不改 |
| C3 | trigger 冲突识别 | 从字符串匹配文案改为业务码 `40901` | scheduler 出码、admin 透传、webui 改判 code（替掉 `index.tsx:122` 的 `includes('already running')`） |
| C4 | admin→scheduler 转发头 | ✅ **已随发布 2 落地**。`X-Forwarded-User` / `X-Tenant-Id` + internal JWT。scheduler 侧的门禁是 `support/InternalCallerInterceptor`，注册在 `config/SchedulerWebConfig.kt:51-53`；它验签用的 `InternalTokenProvider` 是本模块自己声明的那一枚 bean（`config/SchedulerConfig.kt:31-45`——`harnax.auth.enabled` 保持 `false` 时 harnax-auth 的自动装配不产出它，这一条是实施时先验证再写代码的结论），覆盖 `/api/scheduler/**` 的**全部**接口而不仅是这里写的"写面"——发布 2 加了 C5 那个读端点之后，"任何碰到 8084 的人都能凭任务 id 读出创建人与租户"就成了新的一条，读面因此一并关进去（这是对本句的一次有意偏离，正文在 `docs/deploy-harnax-scheduler.md` 的「认证边界」）。缺/错凭证回 `ResultVo(code=401)`；`X-Forwarded-Tenant` 一律不读（浏览器可伪造，见原文档 8.2）；`/actuator/**` 不在该前缀下，健康检查照旧匿名。部署后果：两边的 `HARNAX_AUTH_SECRET` 必须同值，旧 admin 的转发一律 401 |
| **C5** | task 属主查询 | ✅ **已随发布 2 落地**。`GET /api/scheduler/agent-tasks/{id}/owner` → `{creator, tenantId, agentId}`（比这里写的两个值多一个 `agentId`：既然冷路径要读这一行，就把三个段一次给全，省掉"核对用哪个 agentId"的第二次询问；DTO `com.agnetix.harnax.common.dto.AgentTaskOwner`——**注意它在 `harnax-common` 而不是计划里写的 `harnax-entity`**：那一步在同一次发布里被清空成零任务域，共享契约就与 `TaskSessionId` 一起住进 common（包名末段同为 `.dto`，最容易读漏），端点 `AgentTaskOwnerController.kt:48-58`，读 `selectAnyById`，行不存在回 `data:null` + code 200）。admin 的 `McpSessionOwnerResolver.fromTask`（`util/McpSessionOwnerResolver.kt:60-68`）改走 `SchedulerClient.taskOwner`，**失败时返回 null 并打 WARN**（`:83-100`，三处 WARN 在 `:90`/`:94`/`:98`）而不是静默 debug——它的故障形式是"该次执行的 OAuth 工具静默不可用"，任务本身仍然成功。冷路径，spec 2.1 的残留说明即此 |

**已落地的业务码（S0/S1）**：

| 码 | 常量 | 出码方 | 语义 |
|---|---|---|---|
| `40901` | `CODE_EXECUTION_IN_PROGRESS`（`SchedulerController.kt:177`，出码 `:37`/`:93`） | scheduler，admin 原样透传 | 该任务已有活着的执行：既覆盖本实例 `concurrent=0` 的业务拦截，也覆盖集群抢锁失败——两者对用户的含义相同，重试或回滚都不是调用方的问题 |
| `40902` | `CODE_SCHEDULER_SYNC_FAILED`（`AgentTaskCrudServiceImpl.kt:302`，出码 `:290`） | **发布 2 起是 scheduler**（原来是 admin 的 `AgentTaskServiceImpl`，那个类已随域搬迁删除），admin 只把它原样透传回客户端 | **定义已存库、但没有任何 scheduler 重载它**。与"没保存成功"必须可区分：前者数据是对的、只需重试调度，后者要重来一遍 |
| `40903` | `CODE_SCHEDULER_DISABLED`（`SchedulerController.kt:184`，出码 `:172` 的 `requireEnabled`） | scheduler，admin 透传 | 目标实例 `scheduler.enabled=false`，拒绝一切调度写操作。启停/触发/reload 都出这个码，前端按提示展示而非报 500 |

C1 的格式约定是 scheduler 与 admin 之间的**隐式契约**：`resolveFromTask` 必须做显式校验（段数、两段的数字解析），失败时抛带明确文案的异常；格式本身写进本文档作为契约条目。

## 6. 部署形态（compose 固定 2 实例）

### 6.1 库

- `docker-new/sql/init-databases.sql` 增加 `CREATE DATABASE harnax_scheduler` + 对 `harnax` 用户 `GRANT`（与既有四个库同构，两行）。**已落地**，发布 1 用不到它——那时数据源还在 `harnax_admin`；**发布 2 起它就是本服务的库了**。**注意这脚本只在 MySQL 首次初始化空数据目录时执行**——存量部署要手工补那两行（建库 + `FLUSH PRIVILEGES`）。
- scheduler 开 Flyway：`locations: classpath:db/migration`、`table: flyway_schema_history_scheduler`（**独立 history 表名**，避免与 admin 在同一 MySQL 实例里混淆），`enabled` 默认为真。**已落地**；决定本服务迁移的键是 `SCHEDULER_FLYWAY_ENABLED`，compose 与手工部署同一个键（`application.yml` 写的是 `${SCHEDULER_FLYWAY_ENABLED:${FLYWAY_ENABLED:true}}`，admin 那个同名键只是它未设时的回退位），compose 里另有显式的 `SPRING_FLYWAY_ENABLED` 覆盖。
- 迁移脚本：`V1__quartz_tables.sql`（官方 11 张 `QRTZ_*`，剥掉所有 DROP 语句、补齐每表 COMMENT，另含官方脚本自带的 20 条 `CREATE INDEX`）——**已落地**。`V2__agent_task_domain.sql`（三张业务表按库表规范重写，含 `session_id VARCHAR(128)`）——**已落地（发布 2）**。两张脚本现在都由同一个 Flyway 应用进 `harnax_scheduler`，这个库里不再有第二个迁移工具。`agent_task_log` 仍然建，哪怕它注定空着：`AgentTaskMapper.xml` 的 `selectTaskList` 自联它取 `lastRunStatus`/`lastRunTime`，缺表是列表页 500 而不是某列空着。
- **发布 1 的实际位置（修正 C，历史）**：那时数据源仍是 `harnax_admin`，所以 11 张 `QRTZ_*` 与本服务自己的历史表都落在 admin 库里。Quartz 的行是可再生数据（reconcile 按 `agent_task` 重建全部 job），所以 S3 切库时不搬 `QRTZ_*`；**又因为用户确认无历史数据、D8 的迁移整项取消**，`harnax_admin` 里那三张业务表与 11 张 `QRTZ_*` 在切口后没有任何活着的读者，**直接 DROP，不需要观察期**（原计划的"另存 DROP 脚本、运维签认后执行"随之取消）。切口步骤正文：`docs/deploy-harnax-scheduler.md` 的「发布 2 切口」。

### 6.2 两实例与优雅停机（D6 配套）

- 删 `container_name: harnax-scheduler`（compose:270）与宿主映射 `28084:8084`（compose:294），改 `expose: ["8084"]`。
  - 状态：**两项都已落地**——映射改 `expose` 由第二轮 R1 做掉，`container_name` 由发布 1 删掉（留着它 Docker 会直接拒绝 `--scale`，两副本这个形态就不成立）。上面两处行号是 2026-09-11 当时的快照，此后该文件一直在长，读现在的 scheduler 段注释为准。
- **不在 compose 里声明 `deploy.replicas`**，实例数由部署脚本显式 `--scale scheduler=2` 决定。理由：6.3 的逐台滚动要在"停掉其中一台、补齐到 2"之间来回切换，声明式 replicas 会让 `--no-recreate` 的收敛行为变得难以推理。
- `stop_grace_period: 400s` + `spring.quartz.wait-for-jobs-to-complete-on-shutdown=true`。**均已落地**（compose 的 scheduler 服务、`harnax-scheduler/src/main/resources/application.yml`，两处都有把算式写出来的注释）。**400 = 300 + 60 + 20 + 8 + 4**：chat 读超时 300s + `clearSession` 上限 60s + 两次调用各 10s 的 connect 预扣 20s + 定态写回与释放抢锁行 8s + Spring 关停钩子 4s（合计 392，向上取整到 400s；逐项推导在 compose 那段注释里，改任何一个数都要回到那里重算）。`clearSession`（快照上传 + 容器销毁）现在有自己独立的读超时 `min(scheduler.clear-session-timeout-seconds=60, timeout-seconds)`，不再沿用 chat 的 300s——沿用的话一次执行最坏占用是 600s，旧推导"300 + 40 + 20 = 360s"两头都不成立（clearSession 不是 40s，360s 也远小于真实的 600s），SIGKILL 会正好落在 DELETE 中间。**为什么不是刚好等于 timeout**：超时只管得到 router 调用，之后那几十秒才是把一次跑完的执行落成定态行的动作，砍掉它就把正常完成记成超时。
  - **保护范围 = 全部执行路径**（4.2 于发布 3 落地之后才成立）。此前手动 `/trigger` 走 `SchedulerServiceImpl.triggerManually` 起的线程，Quartz 不知道它在跑，`waitForJobsToCompleteOnShutdown` 不等它、grace 也不覆盖它：执行中被重启就是 `status=3` 的行 + `status=0` 的锁行。现在 `/trigger` 与 `/run-once` 都只往 store 投 one-shot，cron 与手动跑在同一批 worker 上，**"不要在任务执行中重启 scheduler"这条例外已经取消**——包括"对已暂停的任务点立即执行"这一情形（one-shot 不受 `taskStatus` 守卫管辖）。
  - 保护与容量是同一条事实的两面，运维规则因此改变：一次手动执行占用 `QUARTZ_THREAD_COUNT` 个 worker 之一直到跑完，`SimpleThreadPool` **没有队列**，worker 被占满时到期的 cron 只能等；等到越过 `misfireThreshold: 60000` 就成了 misfire，而 `concurrent=0` 用的正是 `withMisfireHandlingInstructionDoNothing` —— **那一发定时任务被跳过，不是延后跑**。据此定出下限：**不要用 1~2 个 worker 跑 scheduler**。正文见 `docs/deploy-harnax-scheduler.md`。
  - 这一项原先归在 S2，实际与 S1 同期做掉了：job 一旦在 Quartz 线程内同步执行，"停机不等就等于把一次正常执行切成僵尸行"立刻成立，不必等 JDBC store。属性方式即可（见 3.1 约束 2），`SchedulerFactoryBeanCustomizer` 是多余的。
  - 本项剩下的只有 6.3 的逐台滚动脚本——**发布 1 已交付**（`docker-new/roll-scheduler.sh`，见 6.3 的状态）。
- 时钟：compose 已统一挂载 `/etc/localtime`；集群要求各节点时钟偏差 < 1s，宿主机 NTP 记入运维 checklist（Quartz 集群对时钟敏感，NTP 步进会造成误判接管）。

### 6.3 部署脚本必须逐台滚动

**改造前**：`docker-new/deploy-service.sh` 的 scheduler 分支是 `up -d --force-recreate --no-deps scheduler`，`--force-recreate` 作用于整个 service → **两实例同时下线**，最长 400s 全集群无调度。触发在 `QRTZ_TRIGGERS` 堆积，节点回来后按 misfire 处理，而 `concurrent=0` 用的是 `withMisfireHandlingInstructionDoNothing` → **错过的触发被永久跳过**，与 D6"绝不丢执行"的初衷正好相反。

**发布 1 已落地**：`docker-new/roll-scheduler.sh`，由 `deploy-service.sh scheduler` 的第 4 步调用。它保证的是这些（逐条对着脚本读）：

- 停任何东西之前先把集群**补齐到 `SCHEDULER_REPLICAS`**，所以首次拉起、1→2 增长与 N 副本滚动都是满员进行的；
- 有副本在跑时**拒绝把目标副本数定在 2 以下**——只剩一台的集群没有"另一台"可接管；
- 每次 `docker stop` 之前要求**另一个副本应答它的容器健康检查**；停机用 `docker stop -t ${SCHEDULER_STOP_GRACE}`（默认 400，与本节上面的 `stop_grace_period` 是同一个数，两处要一起改）；
- stop 之后 `docker rm`，并**验证这台真的没了**才 `up -d --no-recreate`：留下的 Stopped 容器会被 `--no-recreate` 按**旧镜像**重新拉起，脚本就会报一个"跑着新构建"的旧部署；
- 整个进程持一把按 **compose 项目名 + daemon 名**定键的独占锁，两次并发滚动会各自看见对方的副本健康、各自停自己那台，所以这把锁是必要的；`Ctrl-C`/`SIGTERM` **结束滚动**而不是只把锁从手里掉出去。
- 结尾回读 `QRTZ_SCHEDULER_STATE`，并明确写了**别把行数读成副本数**：节点不删自己那行，刚滚完是"活成员 + 本轮回还没被对端清掉的尸行"。

**这条护栏看不到的东西要写清**：健康检查是 liveness，不是集群成员身份——一台 `scheduler.enabled=false`（因此根本没进集群）或跑在 `QUARTZ_JOB_STORE=memory`（因此进不去）的副本会高高兴兴应答 liveness 并满足这条检查。compose 对同一 service 的所有副本插值同一个 `SCHEDULER_ENABLED`，脚本这边不存在能区分的信号，所以**"禁用实例不要继续注册在同一个 service 名下"是拓扑规则，只能由文档承担**——正文在 `docs/deploy-harnax-scheduler.md`。

### 6.4 暴露面

- 删除 `docker-new/nginx.conf:178` 的 `location /api/scheduler/`（admin 走 `HARNAX_SCHEDULER_URL: http://scheduler:8084` 内网服务名，不需要 nginx）。
  - 状态：第二轮 R1 已删除该 location（原位留了一段禁止回加的注释，行号已漂移），本条只剩下面那项未做。
- scheduler 的 swagger 与 actuator 生产面收窄：`SWAGGER_ENABLED=false`、`management.endpoints.web.exposure` 去掉 `prometheus` 的匿名暴露（现随 8084 匿名可达）。属 F2，本轮只做部署层。

## 7. 里程碑

原 M1→M5 的顺序按"改动类型"分组而非按"依赖"分组，有两处会导致中间态比现状更危险；发布 1 的实施计划又添了一处范围修正，发布 2 的切口再添一处。四处都在本表里：

- **修正 A（顺序）**：同步执行（原 4.4）提前到 JDBC 集群之前，见 4.1 的顺序要求。
- **修正 B（顺序）**：reconcile（原 4.1）必须与 JDBC store 同期上线，否则存在"共享 store + 全删重建"的窗口，任一节点重启会报掉全集群任务。
- **修正 C（范围）**：D8 的 `agent_task` 迁移**不能与域搬迁分开**——admin 还在写这张表的时候，它只能在 `harnax_admin` 里有一份真相，否则两份副本立刻分叉。所以 S2 只做到"QRTZ 层独立"：`QRTZ_*` 临时建在 `harnax_admin`，用 scheduler 自有的历史表 `flyway_schema_history_scheduler` 记账；业务表与数据迁移随 S3。D3（scheduler 独占 schema）在 S3 结束时才完整成立，本发布不破坏它。**这一条到发布 2 只兑现了一半**：业务表随 S3 搬了，数据迁移那一半随 D8 一起被用户「无历史数据」的决定取消（见下面的修正 D），所以"迁移不能与域搬迁分开"这个约束现在是一条已经作废的理由，留着只为说明 S2 为什么长成那样。
- **修正 D（切口形状，发布 2 落地时确立）**：**域搬迁与数据源切换是同一次切口**，不是两次发布。C1 的四段 sessionId 与 C4 的门禁（`/api/scheduler/**` 全部接口，读写都在内，见 §5 C4）**双向不兼容**，所以 admin 与 scheduler 必须**同时下线**——这是整个改造里唯一不能滚动做的部分，其余每一步都能逐台滚动。两条不能分批的理由都与数据无关：① C4 之后 scheduler 拒收未签名的 HTTP，旧版 admin 的每一次转发都是 401，且两边的 `HARNAX_AUTH_SECRET` 必须同值（compose 用一个变量喂两个服务，手工部署是唯一能配歪的地方）；② C1 的四段形态旧 admin 读不懂，反向的三段 id 新 admin 直接拒，**任何新旧混跑的组合都不成立**。原计划里这一刀的第三条理由（D8 的搬数据）已经不存在：**用户确认无历史数据，迁移整项取消**，新库从空开始、任务由用户在界面重建，旧库表切口后直接 DROP。步骤与回滚正文：`docs/deploy-harnax-scheduler.md` 的「发布 2 切口」。

| 步 | 内容 | 人日 | 可独立发布 | 状态（`fix/scheduler-exec-semantics` / `feat/scheduler-cluster-cutover`） |
|---|---|---|---|---|
| **S0 行为修复** | C3（40901 三端同步）；CLI `logId`→`id` **并补 `task stop` 子命令**（当前 task.go 里不存在该命令，所谓"CLI 停止从未生效"的真实原因是功能缺失而非字段名）；删死代码（admin `AgentTaskLogService.save()`、`/internal/agent-tasks/{taskId}/spec` 端点及测试、`SchedulerClientImpl.kt:35` 与事实相反的注释） | 0.5 | ✅ | **已完成**。死代码那条比原清单多删一处：admin 端点的唯一"调用方" `AdminApiClient.getTaskAgentSpec` 自身也是 `@Deprecated` + 全仓零引用，整条死链一并摘掉 |
| **S1 执行语义** | 4.1 同步执行 + 双 job 类；4.3 中断命中语义（D7，跨三侧）；4.4 定态分支 + 3.5 超时统一；4.5 housekeeping | 2.5 | ✅ | **已完成**，另把 D6 的优雅停机配置（3.1 约束 2 + 6.2 的 `stop_grace_period`）提前做了——同步执行一落地，"停机不等"就立刻制造僵尸行 |
| **S2 库 + 集群 + 对账 + 部署形态** | 6.1 建库与两个 Flyway 脚本；3.1 quartz 集群配置 + 约束 2/3；3.3 reconcile；D8 数据迁移（`agent_task` 一次性 `INSERT ... SELECT`）；6.2/6.3 compose 与逐台滚动；6.4 删 nginx 与宿主映射 | 4 | ✅（内部必须原子） | ✅ **已完成，带范围注（见修正 C）**。落地的是：QRTZ 层（`V1__quartz_tables.sql` + scheduler 自己的 Flyway 历史表 + `job-store-type=jdbc` 集群配置）、reconcile 取代全删重建与 60s 集群清扫、部署形态（compose 两副本、`roll-scheduler.sh` 逐台滚动、admin 的广播塌缩成一次转发）。**没做、随 S3**：`V2__agent_task_domain.sql`、数据源切到 `harnax_scheduler`、D8 的 `agent_task` 迁移（前两项已随发布 2 落地，第三项被用户「无历史数据」的决定整项取消）。**IT-1/IT-2/IT-5 已写、从未执行**（交付机器无 Docker 守护进程），所以本行的"完成"不含集成测试通过 |
| **S3 域搬迁 + 数据源切换** | 三实体 + 三 mapper + 三 XML 从 `harnax-entity` 移入 scheduler（含 `@MapperScan`、`type-aliases-package`、XML 全限定 type）；scheduler 自带 `Page`/异常副本 + pagehelper；`/api/scheduler/agent-tasks/**` **11 个 CRUD/日志端点** + **C5 owner 端点** + 2.3 拦截器；admin `AgentTaskController` 瘦身为鉴权+转发、删 service/DTO/广播（admin 侧仍是 12 个端点：11 条转发 + `/agents` 留在它自己的域）；C1 sessionId 编 agentId；**admin 侧的 mapper 引用全部清零（改造前按发布 2 的代码基线重数：19 处 / 4 个文件 = `AgentTaskServiceImpl` 13、`AgentTaskLogServiceImpl` 2、`InternalApiController` 2（`:51` 的字段声明 + `:363` 那一次 `selectAnyById`）、`McpSessionOwnerResolver` 2（`:26` + `:63`）——spec 原来写的 24 处连同它的逐文件拆分都是错的基线，那四个数加起来就是 24**），另有 7 个 admin 文件 import 这三个实体/DTO 类型，编译边界比那条 grep 更宽** | 3.5 | ~~❌ 必须单 PR（admin 编译断裂）~~ → **该约束被实现方案消解**：scheduler 侧先建自己的一套（包名不同故与 `harnax-entity` 并存），每个提交都能编译、能跑测试 | ✅ **已完成（发布 2，与数据源切换同一个切口，见修正 D）**。落地的是：域（三实体 / 三 mapper / 三 XML / `Page` / 异常与 advice / 11 个 CRUD 与日志端点 / C5 owner 端点 / C4 拦截器）全部在 `harnax-scheduler`，数据源与 Flyway 归 `harnax_scheduler`，`harnax-entity` 与 admin 主源码对这四张表**零 SQL**（`grep -rn "agentTaskMapper\|agentTaskLogMapper\|AgentTaskExecution" harnax-admin/src/main` 与 `grep -rn "AgentTask\|agent_task" harnax-entity/src` 均已实测零命中）。**范围注**：本行的 D8 数据迁移由用户「无历史数据」的决定**整项取消**，切口后任务由用户在界面重建。**IT 不在完成之列**：随本发布新增的 `AgentTaskOwnerScopeIT`（IT-3）与 `AgentTaskMapperSemanticsIT` 和发布 1 的三个 `*IT` 一样从未执行——交付机没有 Docker 守护进程 |
| **S4 收口** | 4.2 one-shot 合并 + 删 `taskExecutor`；`scheduler.reconcile.drift` 指标；健康/指标改集群语义；第 8 节测试补全；文档同步 | 2 | ✅ | ✅ **发布 3 已完成，除测试那一栏**。落地的是：4.2 的 one-shot 合并（`triggerManually` 连同 `SchedulerConfig.taskExecutor()` 一起删除，两个手动端点同源，见 4.2）与随之改掉的文档——`docs/deploy-harnax-scheduler.md`、`prod_doc/agent-task-scheduler.zh-CN.md` 和 `docker-new/docker-compose.yml`、`application.yml` 的停机注释不再声称"grace 只覆盖 cron"。**指标两半早已被 S2 提前做掉**（`scheduler.reconcile.drift{action}` 与健康/指标的集群语义随发布 1 落地，`scheduler.jobs.scheduled` 因此**已经是集群视图**，F9 剩下的只是看板阈值重读）。**S4 还欠的只有一件**：依赖 one-shot 的 IT-4（`storeDurably` 缺席下的补火断言）与第 8 节其余补全——它需要 Docker，发布 3 也没跑。同批另外收掉两处会话归属：admin 能回答 `chn-` 的归属租户（F3-A 的 `chn-` 半边），`/api/router/monitor/call-logs` 按调用方租户收口 |

合计约 **12.5 人日**。要点是 **S0+S1 = 3 人日即可独立上线并解决全部问题③**——原计划把这些排在 M0 与最末的 M4，等于正确性修复要等 14 人日的搬迁走完才对用户生效。

回滚点：S0/S1/S4 各自独立可 revert；**S3 不是"revert 整个 PR"就能回的那一步**——那个单 PR 约束已被实现方案消解（见上表 S3 行），而切口之后新库里写进去的任务定义是代码 revert 带不回来的。它的退路是 `docs/deploy-harnax-scheduler.md`「发布 2 切口」的回滚三件套（`SCHEDULER_DB_URL` 指回旧库 + `QUARTZ_JOB_STORE=memory` + `SCHEDULER_FLYWAY_ENABLED=false`），并且要连镜像一起退、两个服务同时退，同时接受"新库里新建/改过的任务不跟着回来"。**S2 的回滚不是"指回 `harnax_admin` 库"**——修正 C 之后数据源本来就在 `harnax_admin` 库，那一步是空操作；真正的退路只有"离开集群"：`QUARTZ_JOB_STORE=memory` + `SCHEDULER_FLYWAY_ENABLED=false`（compose 侧与手工部署同一个键；`FLYWAY_ENABLED` 是它未设时的回退位）。`QRTZ_*` 表与其中的数据**保留不删**，它们是 reconcile 可以按 `agent_task` 重生成的可再生数据（发布 2 之后这条有了时限：「发布 2 切口」第 8 步 DROP 掉 `harnax_admin` 的 `QRTZ_*` 之后，回滚就要先让 Flyway 或运维把表建回来，"指回旧库"不再是零成本）。代价写在部署文档里：memory 实例**不是集群成员**，两副本里退掉哪台，哪台就只剩转发面的作用。这个退路是**逐台、临时的**：compose 的环境变量对所有副本同源，一旦整个 service 都跑成 memory，两副本之间**仍有** `agent_task_execution` 的 `uk_task_trigger(task_id, trigger_time)` 在挡同一个触发时点（那是业务层 INSERT 抢锁，赢家由抢锁决定而不是由调度器决定），但没有 Quartz 的接管、没有 misfire 补偿，停机期间错过的触发永久跳过；更要紧的是每台手里的 schedule 是私有的，一次只落到其中一台的 CRUD 会让两台跑着**不同的 cron 表达式**——不同表达式就是不同 `trigger_time`，这才是会成对写 `agent_task_log` 的那条路径（集群化之前的多实例就是这个样子，见 `prod_doc/agent-task-scheduler.zh-CN.md` §2.1）——回滚期间要把 `SCHEDULER_ENABLED=false` 的副本从同名 service 里摘掉（同一条拓扑约束），或直接缩到一台，回到 jdbc 后再恢复两副本。

`harnax_admin` 里的三张业务表与 11 张 `QRTZ_*` 在发布 2 的切口之后**不留观察期**：原 M5.1 那套"晚于 scheduler 稳定上线、观察过至少一个完整 cron 周期、运维签认后才合入"的硬约束是**为搬了数据的表设计的**，而这一版什么都不搬（D8 已取消），所以这些表在切口那一刻起就没有任何活着的读者，**切口步骤里直接 DROP**（`docs/deploy-harnax-scheduler.md` 的「发布 2 切口」第 8 步）。仓库不随发布合入 DROP 脚本，这条 SQL 由运维就地执行——留着的唯一作用是让回滚有地方可指。

## 8. 测试

集成测试放 scheduler 模块，testcontainers 起真 MySQL，走 failsafe profile（`-Pintegration-test`）。本域难点（集群抢锁、CAS 定态、diff 收敛）全部 mock 不出来。

**发布 1 落地的形态与原设想不同，三处都是实测出来的**：surefire 侧不能只靠 `<excludes>` 挡 `*IT`——命令行一旦出现 `-Dtest`，插件就会用表达式覆盖 `<excludes>`，所以护栏是 `@Tag("integration")` + `excludedGroups`（基类标一次，`@Tag` 是 `@Inherited`）；failsafe 挂在 `-Pintegration-test` 之后；且本模块的 `repackage` 没有 classifier，必须给 failsafe 加 `additionalClasspathElements=${project.build.outputDirectory}`，否则测试发现死在 `BOOT-INF` 遮蔽上。手工用 `StdSchedulerFactory` 建第二个集群成员时还必须显式 `org.quartz.dataSource.<name>.provider=hikaricp`——Quartz 2.5.2 对手工数据源默认走 c3p0，而 c3p0 在本仓是 `provided` 且无人声明；生产路径不受此影响（Spring 的 `LocalDataSourceJobStore` 自带 provider），所以**不要**把这行抄进 `application.yml`。

**执行状态**：IT-1（`ClusterSingleFireIT`）、IT-2（`ReconcileConvergenceIT`）、IT-5（`HousekeepingGuardIT`）已经是能编译的类，**一次都没跑过**——交付机器上没有 Docker 守护进程。IT-4 的前置条件（S4 的 one-shot 合并）已由发布 3 完成，**但它本身仍未编写**。IT-3 随发布 2 落地成 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/it/AgentTaskOwnerScopeIT.kt:42`（属主与可见性规则跟着域一起搬到了 scheduler，被测对象从此在本模块里），三条 mapper 语义测试合并为同目录的 `AgentTaskMapperSemanticsIT.kt:54`——**这两个类与上面三个完全同状态：能编译、从未执行**。"跑通它们"是验收机器的工作：`mvn -o -pl harnax-scheduler verify -Pintegration-test`。**在这之前任何地方都不该出现"IT 全绿"**，验收清单里依赖它们的两条（§11 第 1 条、第 4 条的 `EXPLAIN` 命中，即 F10）也一并保持未决。

| 用例 | 断言 | 为什么值得写 |
|---|---|---|
| IT-1 集群单触发 | 两个手工建的 Quartz 成员连同库，1s cron 跑 8s：`it_cluster_fire` 行数 ≈ 触发次数（不是 2×）、两个节点的计数相加**等于**行数、`QRTZ_SCHEDULER_STATE` 在该测试自己的 `SCHED_NAME` 下正好两行、且没有任何一个不属于这两台的实例 id 计到过 fire | "真集群"与"各节点各 fire"的唯一硬证据。**必须建在 S1 之后**，否则同步执行没做，这个测试会被假通过。它**故意不断言工作怎么在两台之间分配**：谁先拿到行锁谁跑，一台全输不是 bug，所以计数是按实例 id 分组后再相加的。为什么不用 `agent_task_log`：这两个成员不经过应用的任务链路，写库的是 job 自己插的那张证据表 |
| IT-2 对账收敛 | SQL 手造三种漂移（改 store 里的 cron / 表里删一行留 job / 一条完全不动）→ `reconcile()` → 报告的四个桶正好是 `added=0/updated=1/removed=1/unchanged=1` 的划分、`failedIds` 为空、被删任务失去 job、漂移的 cron 被拉回表里的值；**未动的那条保留它自己的 `NEXT_FIRE_TIME`，且它 trigger 行的 `START_TIME` 从未变过**（同一轮里被重写的那条 `START_TIME` 确实前移，作为正向对照） | 后半句是"没有回归成全删重建"的直接证据：一个把所有 job 都重注册的实现**也能过**前四条断言（它重写出来的值同样是表里的值），只有这两条时间戳断言能抓住它。"完全不动"的那条故意用小写 cron（webui 自己的预设形态），所以它同时是"按 Quartz 归一化结果比较"这条规则的证人 |
| IT-3 越权 | 非属主读/改/删拿不到且数据未变；`is_public=1` 可读 | 已完成项（属主校验）的回归护栏 |
| IT-4 one-shot | 连发两次 trigger：store 出现 `_ONCE` trigger、第二发返回 40901；投递后 kill 进程，重启补火一次且仅一次 | 覆盖 4.2 与 D6 的补火语义 |
| IT-5 guard 清理 | 造过期 `agent_task_execution` 行 → 跑 housekeeping → 过期行删除、未过期保留、`status=0` 泄漏行被清 | `cleanupOldExecutions()` 此前零调用方 |
| **IT-6 中断未命中** | 触发条件按 4.3 的判据写：**该 session 既没有在途流也没有在途调用**时 `interrupt()` 返回未命中（缓存里还留着 wrapper **不算**命中，G5），scheduler 立即把行 4→5，`expireStale` 之后不再改动它 | 覆盖 D7。这是本轮唯一跨三个服务的断言，也是用户可见的直接收益 |
| **IT-7 超时竞争** | 执行结果返回前行已被 `expireStale` 写成 2 → 真实结果与状态被覆盖写回，`error_info` 含超时标记 | 覆盖 4.4"结果不丢" |

既有测试处置（**发布 2 的实际落法，与原设想有三处不同**）：`AgentTaskServiceImplTest`(603) 的属主/校验/文案与 40902 断言迁成 scheduler 的 `AgentTaskCrudServiceImplTest`——**是 Mockito 单测，不是原设想的真库 IT**（真库那份由新增的 `AgentTaskOwnerScopeIT` 承担）；`AgentTaskLogServiceImplTest`(271) 没有删，它的参数透传四条迁成 scheduler 的 `AgentTaskLogQueryServiceImplTest`，"读契约不带 tenant 形参"改为 mapper 接口的反射断言；`AgentTaskControllerTest`(657) 拆成 admin 的 `AgentTaskForwardingContractTest`（MockWebServer 假装 scheduler）+ scheduler 自己的 `controller/AgentTaskControllerTest`；`it/AgentTaskCrudIT`(171) 与 `it/AgentTaskSchedulerIT`(208) **留在 admin 没有迁**，被测对象改为转发契约；`harnax-entity` 的三个 mapper 测试与其 `schema-test.sql` 的三段 DDL + 种子删除，语义合并为 scheduler 的 `AgentTaskMapperSemanticsIT`；`SchedulerClientImplTest` 加转发与身份头用例；scheduler 原有 3 个 Mockito 单测保留并按新签名调整。逐条对照表见 `prod_doc/agent-task-scheduler.zh-CN.md` §12。

## 9. Fast-follow（本轮明确不做，只登记）

1. **F1 scheduler 服务自鉴权**：真正装配 `harnax.auth.enabled=true` 的完整方案、端点级 `@InternalOnly`。本轮只有 2.3 落地的那道自装验签门禁（`InternalCallerInterceptor`，只认 `typ=internal` JWT，读写面全在内）。
2. **F2 生产匿名面收窄**：swagger、`/actuator/prometheus`。本轮只删 nginx location 与宿主端口映射。
3. **F3 `agent-spec` 属主校验缺失**：🟡 **前缀策略已落地（`SessionAccessGuard` + `PrivilegedSessionPrefixes`，覆盖 `task-`），归属比较也在发布 3 补上了 `chn-` 这一半**（admin 现在能回答频道会话的归属租户，跨租户 `chn-` 读取即拒；`task-` 的归属自发布 2 起**有端点可问**（C5），但 router 侧仍只看前缀——见下面 A 的 `task-` 半边）。原本的状况：`sessionId` 由调用方任意传入，router 侧唯一的归属校验 `SessionAccessGuard.requireAccessible` 只认 admin 的 `session` 表，而 `task-`/`chn-` 按设计**不在这张表里**（它们来自 `agent_task` / `channel`）→ lookup 恒为 `Unknown`、而 `Unknown` 是放行。于是任何一枚有效凭据——用户自己的登录 JWT 就够——伪造 `task-{受害者taskId}-...` 即可拿到该任务的 systemPrompt/model/tools/skills/MCP 清单，且 `resolveFromTask` 硬编码 `permissionMode = "BYPASS"`（免工具确认）。守卫只认一张表、admin 的解析认四张表，这个不对称就是洞本身。
   已落地的 B：`task-` 归为"由服务端自行决定的会话"，只有 `AuthContext.userId == null` 的内部调用方（SYSTEM key、内部服务令牌）能用；带终端用户身份的调用方（登录 JWT 或代表用户的外部 API key，即 `userId != null`）在 **lookup 之前**按前缀拒绝（判定同时位于 `tenantId ?: return` 之前，无租户的终端用户 key 也拦得住）。`web-`/`mp-` 行为完全不变，仍走租户比较。stream 端点（`/chat/stream`、`/confirm`）把拒绝转成 `ErrorChatEvent`（`FORBIDDEN`）+ `EndEventChatEvent`，不再让异常落到 `GlobalExceptionHandler` 上把 JSON 错误体写成 event-stream。
   **`chn-` 已从特权前缀集合移出**——首版（`89138c3`）把它与 `task-` 一并拦下，过宽。两个前缀的**可利用性不对等**：`task-{taskId}` 的 `{taskId}` 是自增整数，一枚有效凭据就能从 1 数到 N，读遍别人的任务配置，这是必须堵的枚举面；`chn-{uuid}`（生成处 `ChannelServiceImpl.kt:145`）是 UUID，不可枚举，能命名它的人本来就早已掌握那个具体 id。而 `chn-` 侧**确有合法的终端用户只读流量**：webui 频道管理页拿 `batchGetWorkspaceStatus(channels[].sessionId)` 查沙箱状态（`pages/channel/index.tsx:112-127`），并把同一个 sessionId 交给 `WorkspaceDrawer`（`:311-343` 的 Sandbox 列与 Workspace 按钮、`:538-542` 挂载 → `files`/`read`/`download`，`pages/session/components/WorkspaceDrawer.tsx:50,94,291`）；这些 sessionId 就是 `chn-{uuid}`，凭据是登录时下发的**用户绑定 key**（`AuthServiceImpl.kt:104-127` → `ApiKeyServiceImpl.kt:196` 写入 `userId`），即 `userId != null`，正好落在首版的拒绝里。净效果是 Sandbox 列恒为 `-`、Workspace 按钮恒 `disabled`，且失败被 `index.tsx:125-127` 的空 catch 吞掉，**静默**。而这次拒绝换来了什么？**当时没有任何可验证的授权**：admin 的 `/sessions/{id}/info` 只查 `session` 表，对 `chn-` 恒为 `Unknown`，这里根本不存在一个能被保护的归属查询（这一条发布 3 已改，见下面 A 的 `chn-` 半边）。一条拦不住攻击者、又关掉真实功能的规则不值其代价，所以 `chn-` **恢复本分支之前的行为**（走既有租户比较分支，与本分支之前完全一致）；`chn-` 真正的归属保护归入下面的 A——那是"加检查"而不是"撤访问"，才是有信息量的修法。
   **A 的 `chn-` 半边：✅ 发布 3 已闭环**。`GET /internal/sessions/{id}/info` 遇到 `chn-` 不再"查 `session` 表未果即 Unknown"，而是走 `channelSessionInfo` → `ChannelMapper.selectOwnerBySessionId`：`SELECT session_id, tenant_id, agent_id FROM channel WHERE session_id = ? LIMIT 1`，**没有 `active = 1` 过滤**（`V28__add_channel_session_id_index.sql` 正是为这一条查询补的索引）。不看 `active` 是本项的重点而不是疏漏：`deleteById` 是软删除，行没了但 `tenant_id` 还留在行上，而删频道既不清 session 也不清 sandbox——按 active 过滤等于把**"删掉一个频道"变成"抹掉一次跨租户读取的归属"**。所以软删除的频道照样归属它原来的租户：这里回答的是"这是谁的"，不是"这还准不准读"（配置读取仍走带 active 过滤的 `selectBySessionId`，那边的"删了就没了"才是对的）。查无此行才回 Unknown，而 `chn-` 的 id 与频道行是同时生成的（`ChannelServiceImpl.generateSessionId`），所以"有 id 无行"只可能是 admin 从未发出过的 id。`tenant_id <= 0` 的行**照实上报**而不折叠成 null——null 在 router 那里就是"无法判定"即放行，而 0 会让每一个带租户的调用方都成为跨租户调用方；admin 同时打一条 warn，让这种频道可诊断。`agentName`/`modelId`/`modelName` 留 null（这条路径唯一消费者是 router 的调用日志补全，缺字段只少几列补充信息，不值得给每一次 cache miss 加第二次查询）。**净结果**：租户 A 的登录态命名租户 B 的 `chn-` 会话，router 现在比得出归属并抛 `SecurityException`（此前恒放行），`SessionAccessGuardTest` 钉的那条"admin 能回答即拒"从此是活规则。
   **A 的 `task-` 半边：发布 2 只兑现了一半，另一半仍是决定而不是遗漏**。`task-` 的归属属于 scheduler 域，admin 不该替它回答——发布 2 之后 **数据来源有了**（C5 的 `GET /api/scheduler/agent-tasks/{id}/owner`，答 `creator` / `tenantId` / `agentId`），但**读它的只有 admin 的 MCP 属主解析这一条冷路径**：router 对 `task-` 的判定仍然只是 `PrivilegedSessionPrefixes` 的前缀规则（只有 `userId == null` 的内部调用方可用，带终端用户身份的调用方在 lookup 之前就被拒），热路径上没有那次归属查询。收口这一条的动作因此是"让 router 也去问"，登记在 F15 的末尾与 `docs/superpowers/plans/2026-09-14-scheduler-finishing-and-ownership.md` 的同批注记里。**纵深防御的 C（`resolveFromTask` 校验 sessionId 的 agentId 段与该行 `agent_id` 一致）不是"待落地"，而是已经不可执行**：域搬走之后 admin 读不到那张表，热路径改为**信任**字符串里的 agentId，这一条的完整登记见 §9 F15。
   **这条收口留下的已知窗口（一行）**：router 的 `SessionInfoClient` 把 admin 的归属查询**连结果一起缓存 5 分钟**（写后过期、上限 5000 条），所以一次归属变化——包括本次发布把 `chn-` 的 Unknown 变成 Found——最多 5 分钟内仍按旧值判定（`Unreachable` 立即作废，`Unknown`/`Found` 不作废）。这是既有缓存的既有语义，不是新引入的缺陷。
4. **F4 `agent_task_log.agent_task_id` 列**：现在靠解析 sessionId 字符串定位任务，应改为显式外键列。
5. **F5 任务级权限模式**：`agent_task` 加 `permission_mode` 列，scheduler 建会话时带上，替代 admin 侧硬编码的 `BYPASS`（D5 的产物）。
6. **F6 status 取值收敛**：`0/1`、`0/1/2`、`3/4/5` 三套散落字面量收敛为共享枚举。
7. **F7 日志脱敏**：`SELECT *` 全文下发 `prompt`/`response`/`errorInfo`，无租户/属主过滤。
8. **F8 域内无细粒度授权**：整域只要求"已登录"，`MybatisTenantInterceptor.intercept()` 实为 no-op，属主条件是目前唯一隔离手段。
9. **F9 指标语义（🟡 已随 S2 落地；剩下的是看板重读）**：代码这一侧已经全部改完——`scheduler.jobs.scheduled` 与健康 detail 的 `scheduledJobCount` 都**当场读共享 store**，因此它们是**集群视图**（两副本取相同值，不再有"每台各报自己那一份"的含义），读不出 store 时 gauge 给 NaN 而不是一个像样的 0；`scheduler.load.attempts` 已改名 `scheduler.reconcile.rounds{outcome}`，语义是**一轮一个样本**（谁来跑都算：启动收敛、admin 的一次 `/reload` 转发、60s 清扫），不再是每次启动一到 n 个；`scheduler.reconcile.drift{action}` 每轮只由跑它的那一台发布，所以按 `instance` 求和得到的是集群总量，而读单台不等于读集群。**留给运维的动作**：既有看板与告警阈值必须按新语义重读，特别是任何把旧名读成"启动失败次数"的面板——那条读数现在会一直涨，涨速是每轮一次而不是每次重启一次。
10. **F10 `agent_task_execution` 缺索引**：✅ **已落地（第三批次 G4，`V27__add_agent_task_execution_sweep_indexes.sql`）**，补的正是 `KEY idx_status_create_time (status, create_time)` 与 `KEY idx_create_time (create_time)`，`harnax-entity/src/test/resources/schema-test.sql` 同步。原本的状况：V1 建的这张表只有 PK、`uk_task_trigger(task_id, trigger_time)`、`idx_task_id`、`idx_trigger_time`，**`status` 与 `create_time` 都没有索引**，而 S1 的 housekeeping 把 `deleteStaleRunning`（按 `status` + 时间）与 `deleteOldExecutions`（按 `create_time`）挂成了每 5 分钟一轮，这张表又按触发次数线性增长 → 每轮两次全表扫，且扫描范围锁与 `tryAcquireLock` 的 INSERT 在 `uk_task_trigger` 上互顶。真库上的 `EXPLAIN` 命中验证**仍然没做过**。它的归属现在很明确：全仓库唯一有真 MySQL 的地方就是 scheduler 的 failsafe 套件，而 `HousekeepingGuardIT` 已经在真库上跑这两条清扫语句——`EXPLAIN` 就顺着它读一次。前提是那套 IT 第一次真的跑起来（`mvn -o -pl harnax-scheduler verify -Pintegration-test`，需要 Docker 守护进程，发布 1 只交付了代码）。在那之前，"索引被命中"是推测，不是观察结果。
11. **F11 停止意图只有一个瞬态列承载**：用户"点了停止"这件事目前**只写在 `agent_task_log.status = 4` 上**，没有独立的标记位，于是它只活到下一次回收扫描为止。G6 补齐的是**能区分出来的那一半**：执行线程先读到 4、随后 4 被 sweep 改成 2，`finalizeStopped` 的 4 守卫失配 → 重读发现是 2 → 改走 `reclaimExpired` 写 5（`SchedulerServiceImpl.kt:524-550`）。**没补齐的那一半**是 sweep 在**那次重读之前**就把 4 改成 2：线程按 2 分支写回自己真实的 0/1，用户那一次停止就此从记录上消失，而且**无法与"根本没被停止过"区分**——`expireStale` 已经把 `error_info` 覆盖成超时文案，4 存在过的证据被销毁了。这不是"超时"那样的错误标签，但这一行仍然不对。根治需要给行加一个 stop/owner 标记（`stop_requested` 列，或 `agent_task_execution` 上记 owner），让停止意图不依赖 status 这一瞬态——属 S2/S3 的库改动，本轮不动行为，只在 `SchedulerServiceImpl.kt:510-561` 与 `AgentTaskLogMapper.xml` 的 `expireStale`/`reclaimExpired` 注释里标明各守住哪个窗口、哪个窗口未守。
12. **F12 `GET /api/admin/channels/page` 无租户过滤（🔴 发布 3 判定超范围，未修；本清单里用户感知最强的一条）**。事实：`ChannelController.pageChannel` → `ChannelServiceImpl.page` → `channelMapper.selectChannelList(keyword, type, status)`，那条 SQL 除了 `active = 1` 与 keyword/type/status 三个可选条件**不过滤任何东西**，`tenant_id` 根本不在 WHERE 里；而本该兜这一层的 `MybatisTenantInterceptor.intercept()` **方法体整体是注释掉的**，只剩 `return invocation.proceed()`（F8 说的就是它）。于是列表可以直接包含别的租户的频道行，而 `ChannelResponse` 是 1:1 全字段下发——连 `configJson`（各平台凭证）与 `sessionId`、`callbackKey` 一起给出去。
    **发布 3 之后它多了一个用户可见的后果**：那份混合列表的 `sessionId` 会被整批交给 `GET /api/router/agent/workspace/status`，而 router 对列表里**每一个** id 都过 `SessionAccessGuard.requireAccessible`——`chn-` 现在答得出归属，于是别租户那一个抛 `SecurityException`，**整批调用一起失败**。webui 侧是 `pages/channel/index.tsx` 的空 catch，所以现象不是报错而是**静默**：一个同租户的操作员，Sandbox 列恒为 `-`、Workspace 按钮恒 `disabled`，看起来只像功能坏了。
    **它同时顶穿了 F3 的前提**：发布 1 选择"不按前缀拒绝 `chn-`"的理由是 `chn-{uuid}` 不可枚举、能命名它的人本就掌握那个 id。列表接口正是这个"本就掌握"的来源——不必枚举 UUID，一页 JSON 就够了。所以这条不只是数据泄露，它使 F3-A 的 `chn-` 收口在实践上少了一层。
    **最小正确修法**（不在发布 3）：`selectChannelList` 加显式租户谓词，值由服务端从调用方身份推导（**不是**调用方可传的 query 参数——调用方选的过滤器不能同时是它所在的边界，与 `/monitor/call-logs` 同一道理），并给平台管理员留一条**显式**豁免（像 `@SkipTenantFilter` 那样标注，而不是靠"没人盖章"）；同时把列表 DTO 里的 `configJson`/`callbackKey` 摘掉（列表页用不到，详情按需下发）。webui 那个空 catch 要改成可诊断的失败，否则同类故障每次都只表现为"按钮坏了"。

13. **F13 启停/触发面没有写侧属主门禁（🔴 发布 2 有意原样搬过来，未修）**。事实三条，都在搬迁后的代码里：
    - `harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:106-107` 的 `updateStatus` 只有 `WHERE id = #{id} AND active = 1`，**没有 `creator` 条件**——而同文件的 `updateById`（`:64`）与 `deleteById`（`:67-69`）都带 `creator = #{currentUsername}`。写门禁在这张 mapper 里就不是齐的。
    - `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:177`（`/{id}/start`）、`:181`（`/{id}/pause`）、`:185`（`/{id}/trigger`）都是 `relay(schedulerController.…)`，**一次属主或可见性读都没有**；对照同一个类里 `:148-156` 的 `toggle/{id}` 先调 `requireVisibleTask`、`:199-201` 的 `logs/{logId}/stop` 先调 `requireOwnedLog`。admin 侧同样原样转发（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:149`、`:153`、`:157`），它已经读不到那张表，也无从判起。
    - 用户可感知的后果：**任何一枚登录态凭据都能按 id 启停或立即执行别人的任务**。`pause` 是"让别人不再按时跑"（可用性面），`trigger` 是"占掉一个 Quartz worker 并写一行 `agent_task_log`"（容量与记录面），`start` 把一条从没被 owner 验证过的 cron 推进调度。此刻的屏障只剩 C4（内部调用方身份）与网络隔离（`8084` 不发布宿主端口）。
    - **为什么发布 2 不修**：这一版的硬约束是"属主/可见性规则逐字保留，不得在搬迁中顺手收紧或放宽"——在纯搬家的提交里改门禁，会让一个 diff 同时承载两件事，回归时没人分得清是搬坏了还是改严了。修法也不止一个谓词：`start` 那条还要一并决定"非属主的 `start` 回 404 还是 403"（今天 `toggle` 走的是"Agent task not found"文案、客户端按它展示），所以它是一次独立改动。
14. **F14 `uk_name` 与软删除互斥：已删任务的名字永久不可复用，且报错形式是 500（🔴 发布 2 原样搬，非新引入）**。四条凑成这条死路：
    - `harnax-scheduler/src/main/resources/db/migration/V2__agent_task_domain.sql:53` 的 `UNIQUE KEY uk_name (name)` 是**全局唯一、不含 `active`**（admin 的 `V1__init_schema.sql` 原样如此，搬迁逐字保留）；
    - `harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:67-69` 的 `deleteById` 是 `UPDATE agent_task SET active = 0`，行留在表里、名字也留在表里；
    - 同文件 `:98-99` 的 `selectByName` 带 `AND active = 1`，所以 `harnax-scheduler/.../service/impl/AgentTaskCrudServiceImpl.kt:74-76`（创建）与 `:123-125`（改名）的重复名预检查答"这个名字可用"；
    - 于是真正的裁决落在 INSERT 撞键上，`harnax-scheduler/.../controller/AgentTaskController.kt:99-101` 的 `catch (e: Exception)` 把它包成 `ResultVo.error("Failed to create agent task: …")`，而单参 `error` 的码就是 **500**（`harnax-common/src/main/kotlin/com/agnetix/harnax/common/dto/ResultVo.kt:49`）。用户在界面上看到的不是"名称已存在"，而是一条带 SQL 字样的失败。
    - 净结果：**"`uk_name` 仍然占用却查不到占用者"**——这正是搬迁计划里警告过"不能只搬 `active=1` 的行"的那件事，只不过这一版一行都不搬，所以它不再是迁移风险而是这个域本来的形状。修法要先定产品口径：**已删任务的名字该不该复用**。该复用 → 删除时改名（`name = concat(name,'#',id)`）或改硬删（前提是没有日志引用它）；不该复用 → 摘掉 `selectByName` 的 `active = 1` 谓词，让预检查答"被占用"并以业务码回出（**一行**改动，也是唯一让错误诚实的改动）。两条都比现状好，现状是最坏的第三种：规则说了不算，却又确实挡着人。
15. **F15 C1 之后 admin 无法交叉校验 sessionId 里的 agentId（🟡 域搬迁的直接代价，登记为残留而不是完成项）**。spec §5 C1 原定的"F3 第三刀 C"——`resolveFromTask` 把 sessionId 的 `{agentId}` 段与该行 `agent_task.agent_id` 比对——**在 S3 之后不可执行**：那张表已经不在 admin 读得到的地方。今天落地的形态是 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:369-373`：`TaskSessionId.parse` 严格四段解析，然后**直接拿 `parsed.agentId` 去装配 spec**，热路径上没有任何东西核对"这个 agentId 是不是那次任务真用的那个"。可达集合因此从"有任务的 agent"变成"**任何 agent**"——仍需内部调用方身份（internal token / SYSTEM key，且 F3-B 的 `PrivilegedSessionPrefixes` 仍把带终端用户身份的调用方在 lookup 之前拒掉），但一枚有效内部凭据就能凭 `task-1-<任意 agentId>-x` 读到那个 agent 的 systemPrompt / model / tools / MCP 清单。**不粉饰：这是一次真实的放宽**，只是它换掉的那次查询本来也挡不住"agentId 与 taskId 配错"以外的事。已有的两件补偿：生成侧同源（两个段出自同一次 `selectAnyById`，见 `harnax-common/.../session/TaskSessionId.kt` 的 `of` 注释）与解析侧严格格式；冷路径的 C5 也把 `agentId` 一起返回了，所以"按 id 反查真属主与真 agent"有数据来源，只是不在热路径上做。**正解仍是 F3-A 的归属扩展**：由 scheduler 回答"这个 task 归属谁"，需要判定的那一侧再比对——`task-` 的归属半边与这一条本来就是同一件事。

## 10. 数据迁移（D8）

> **状态：整节取消。** 用户确认这次切换**没有历史数据**，于是 D8 剩下的部分（把 `agent_task` 的定义搬过去）也一起去掉：发布 2 的切口**不迁任何东西**。

- 因此这一节原来写的东西全部作废，且**不再有对应物**：没有 `INSERT ... SELECT` 脚本、没有"目标表已有行就跳过"的幂等守卫、没有末尾那条自校验 SELECT，也没有"历史日志变空 / 最近运行两列变空"这类要写进公告的损失——没有东西可失去。仓库里不留迁移 SQL，也不留 DROP 脚本（旧表要 DROP 的那条由运维就地执行）。
- 新库从空开始，**任务由用户在界面重建**。这既是这一节取消的直接后果，也是切口顺序里最后一步的内容。
- **唯一的 schema 事实保留**：`agent_task_log` 仍然必须在新库里建出来。`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml` 的 `selectTaskList` 自联这张表取 `lastRunStatus` / `lastRunTime`，**缺表是列表页 500，不是"某一列空着"**——这与迁不迁数据无关，所以 `V2__agent_task_domain.sql` 照旧建三张表。
- 旧库 `harnax_admin` 的三张业务表与 11 张 `QRTZ_*`：**切口后直接 DROP，不留观察期**（原来那条"运维签认 + 观察一个完整 cron 周期"是为搬了数据的表设的）。
- 切口顺序正文：`docs/deploy-harnax-scheduler.md` 的「发布 2 切口」。

## 11. 验收标准

1. `mvn -o -pl harnax-scheduler verify -Pintegration-test` 通过，IT-1/IT-2/IT-5 全绿。**发布 1 只交付到"这三个类写好了"**：交付机器没有 Docker 守护进程，它们一次都没执行过，所以这一条**现在是未决项，不是已完成项**——它要有 Docker 的机器（本机或验收机）跑一次才算数。**发布 2 让这条的未决集合变大而不是变小**：新增的 IT-3（`AgentTaskOwnerScopeIT`，属主/可见性规则在真库上的越权断言）与 `AgentTaskMapperSemanticsIT`（三条 mapper 语义测试随域迁来）同样是"能编译、从未执行"。IT-4 的依赖（S4 的 one-shot 合并）已由发布 3 满足、但用例仍未编写，IT-6/IT-7 属中断命中与超时竞争两批，都不在本条之内。
2. `docker-compose -f docker-new/docker-compose.yml up -d --scale scheduler=2` 后 `QRTZ_SCHEDULER_STATE` 有两个实例的 `INSTANCE_NAME`，且每行的 `LAST_CHECKIN_TIME` 每 15s 各自前进（发布 2 起这张表在 **`harnax_scheduler`** 库，`roll-scheduler.sh` 的回读已跟着改；**行数不等于副本数**，见上）；kill 其一，另一个接管它未完成的 trigger。接管窗口是**算出来的 22.5~52.5s**，不是范围猜的：死节点那行的 `LAST_CHECKIN_TIME` + `CHECKIN_INTERVAL` 15000ms + Quartz 硬编码的 7500ms = **22.5s** 之后才被 `calcFailedIfAfter` 判为失效，而对端只在自己的 ClusterManager 线程醒来时求值这个式子（睡的就是一个 checkin 周期）→ **+至多 15s**；那一轮对端自己被慢库拖过一整个周期时 `max()` 取 30s 而非 15s → **再 +15s**，最坏 52.5s。整个窗口都落在 `misfireThreshold: 60000` 之内，所以**单节点死亡不丢触发**，而一次全 service 的 `--force-recreate`（最长 400s）必然越过它——那条边由 `roll-scheduler.sh` 关住（见 6.3）。
3. 带执行中任务重启 scheduler：该行最终为真实结果（1 或 0），**不是** 2 timeout；`stop_grace_period` 生效证据可见于容器退出耗时。
4. 对一个正在执行的任务点"停止"：agent-service 已重启过的场景下，日志状态在秒级变为 5，不出现 timeout。
5. webui 列表（含 `lastRunStatus`/`lastRunTime`）、创建/编辑/启停/删除、立即执行（含二次确认与冲突提示走 40901）、日志弹窗轮询全通；CLI 与小程序各跑一遍。
6. `grep -rn "agentTaskMapper\|agentTaskLogMapper\|AgentTaskExecution" harnax-admin/src/main` **零命中**——**发布 2 已实测为零**（配套那条 `grep -rn "AgentTask\|agent_task" harnax-entity/src` 同样为零）。**基线订正**：本文原写的"改造前实测 24 处 / 4 个文件"是错的，实施时重新数是 **19 处 / 4 个文件**（`AgentTaskServiceImpl` 13、`AgentTaskLogServiceImpl` 2、`InternalApiController` 2、`McpSessionOwnerResolver` 2——**旧的那串 24 连同它的逐文件拆分 12/5/4/3 一起作废，那四个数相加正好是 24，也就是错的来源**），且**编译边界比这条 grep 更宽**：另有 **7 个** admin 文件 import `com.agnetix.harnax.entity.AgentTask*` 这三个类型（含两个 DTO、两个 service 接口），它们必须与那 19 处一起改，所以单看这条 grep 归零并不代表域已经搬干净；nginx 无 `/api/scheduler/` location；宿主 28084 不可达。
7. **C5 回归**：一个绑定了 OAuth MCP 的 agent 被定时任务调用时，agent-service 仍能解析出正确的 `sys_user.id` 与 `tenantId`（数据来自 scheduler 的 owner 端点而非本地表）。这条必须单独验，因为它的故障形式是"OAuth 工具静默不可用"，不会让任务失败。
