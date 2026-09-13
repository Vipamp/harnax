# Harnax 定时任务与 Scheduler 服务全流程（中文）

> 本文覆盖「智能体定时任务」（Agent Task）这个业务域的完整链路：数据模型、harnax-scheduler 独立服务的职责、Quartz 调度引擎、任务生命周期（创建→注册→触发→执行→停止→回收）、跨服务边界与鉴权、以及本轮集群化改造的方案决策与实施计划。
>
> **关于英文版**：`prod_doc` 的约定是中英成对，但本文自始**只有中文**（无 `agent-task-scheduler.en-US.md`）。改本文时不需要同步英文版，也**不要**为此新建半份英文文档；真要建，就得在同一批改动里把本文完整翻过去。
>
> **本轮改造的设计与实施计划已于 2026-09-11 定稿并移至** [docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md](../docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md)（决策清单 D1~D8、里程碑 S0~S4、跨服务契约 C1~C4、测试 IT-1~IT-7、验收标准）。本文此后是**现状链路与方案推理**的参考：改代码前先对齐那份 spec 的里程碑表，本文第 10~11 节保留作工作项清单与被取代决策的备查记录。
>
> 相关文档：会话路由能力契约见 [session-routing.zh-CN.md](./session-routing.zh-CN.md)（部署步骤见 [docs/deploy-harnax-session-router.md](../docs/deploy-harnax-session-router.md)），渠道监听器单实例化（MySQL `GET_LOCK` 范式）见 [docs/channel-to-agent-flow.md](../docs/channel-to-agent-flow.md)，后端分层规范见 [docs/backend-code-conventions.md](../docs/backend-code-conventions.md)，库表规范见 [docs/database-design-conventions.md](../docs/database-design-conventions.md)。

## 1. 结论先行

1. **定时任务是独立服务，不是 admin 的一个功能模块**。`harnax-scheduler`（Kotlin，端口 8084）独占 `agent_task` / `agent_task_log` / `agent_task_execution` 三张表与 Quartz 引擎，自建 `harnax_scheduler` 库、自管 Flyway。admin 不再持有这个域的任何表和 SQL。
2. **Quartz 用真正的 JDBC 集群模式**：共享 JobStore + `isClustered=true`，11 张 `QRTZ_*` 表是唯一调度真相。一次 cron 触发在全集群只投递一次、只由一个节点执行，故障节点 15~75s 内被其它节点接管。
3. **"多实例去重"从业务层下沉到引擎层**。改造前靠 `agent_task_execution` 表的唯一键做各节点抢锁（因为每个节点各自触发一次）；改造后 Quartz 自己保证单次投递，抢锁降级为兜底防线，仍然保留。
4. **admin 退化为「校验用户 JWT + 带身份转发」的薄入口**。三个客户端（webui / cli / 小程序）继续打 `/api/admin/agent-tasks/**`，前缀与契约不变；scheduler 的 HTTP 面不直接对浏览器开放。
5. **CRUD 与 job 注册同库同服务，通知链路消失**。改造前 admin 改完任务要 HTTP 广播 `/reload` 给所有 scheduler 节点；改造后一次方法调用里写完 `agent_task` 就注册 job，`SchedulerClientImpl` 的广播逻辑整条删除。
6. **对账（reconcile）取代"全删重建"**。共享存储下"把 `AgentTaskGroup` 里的 job 全删再重建"是集群级破坏操作（任一节点重启会瞬时报掉全集群任务）。改成 diff 式收敛，并由一个每 60s 的集群内 job 兜底——这个 job 本身也依赖集群保证"全集群同时只有一个节点在跑"。
7. **本轮不迁移数据**。三张表的历史数据丢弃，上线后人工重建任务。这是明确的取舍，不是待办。
8. **scheduler 的服务自鉴权不在本轮范围**（见第 13 节 fast-follow），本轮靠部署层收紧暴露面：不发布宿主端口、nginx 路径改内网 allowlist。

## 2. 现状与问题

### 2.1 改造前的样子

`harnax-scheduler` 是从 admin 拆出的独立服务（commit `3ec9bf9`），但拆分只做了一半：

| 层面 | 状态 | 证据 |
|---|---|---|
| 进程与部署 | 已独立 | 独立 jar、独立 `Dockerfile.scheduler`、compose 独立服务 |
| 跨节点执行状态 | 已就绪 | `agent_task_execution` 唯一键抢锁（`AgentTaskExecutionGuard.kt:31-46`）、`agent_task_log` 的 3→4→5 状态机使任意节点可受理停止（`SchedulerServiceImpl.kt:386-410`）、`expireStale` 回收僵尸执行（`AgentTaskLogMapper.xml:116-127`） |
| 调度引擎 | **未改造** | `application.yml:33` 默认 `job-store-type: memory`；全仓库没有任何 `QRTZ_*` 建表脚本；无 `isClustered`/`clusterCheckinInterval`/`jobStore` 配置 |
| 数据归属 | **错位** | `agent_task` 的 CRUD 在 admin，调度在 scheduler，两者共用 `harnax_admin` 库 |

引擎未改造带来的直接后果是：所谓"多实例"实际是**每个节点各自 fire 一次，再由 DB 唯一键丢弃重复**。这条路拿不到 Quartz 的三样东西——misfire 补偿（节点停机期间错过的触发会被永久跳过）、故障接管（节点死了它内存里的任务一起没了）、以及负载均衡（谁抢到锁谁跑，但抢锁失败的那次 fire 是纯浪费）。

数据归属错位带来的是两处跨表耦合：

```
admin 列表页   AgentTaskMapper.xml:62-86   SELECT t.*, latest.status AS last_run_status ...
                                           LEFT JOIN agent_task_log ...        ← 业务表 JOIN 引擎产物
scheduler 回收  AgentTaskLogMapper.xml:116-127  UPDATE agent_task_log l
                                               JOIN agent_task t ON t.id=l.task_id  ← 引擎写回读业务表
```

两条 SQL 各自跨了两个服务的职责边界。今天靠"共库"这个前提勉强成立，一旦分库立刻全废——这也是第 10 节三个方案里淘汰 C2 的直接原因。

### 2.2 顺带发现的既有缺陷

本轮摸底时确认了四个与集群化无关但同属这个域的问题，**已随第 11 节 M0 全部处理**，本表保留为发现记录（各项现状见那张状态表）：

| 问题 | 位置 | 影响 |
|---|---|---|
| 按 ID 的操作无属主校验 | `AgentTaskMapper.xml:27` `selectById`、`updateById`、`deleteById` 均只判 `active = 1` | 任何登录用户拿到任务 ID 就能读改删他人任务。列表查询 `selectTaskList` 反而有 `(is_public = 1 OR creator = #{currentUsername})`（:74-75） |
| CLI 读错字段名 | `harnax-cli/cmd/task.go:24` 读 `logId`，实体字段实际是 `AgentTaskLog.id` | CLI 拿到的日志 ID 恒为 0，从 CLI 停止运行中任务从来没生效过 |
| trigger 冲突判断从不命中 | webui `pages/agent-task/index.tsx:122` 匹配 message 含 `'already running'`，后端实际文案是 `"Task is already being executed by another instance"` | 重复触发的提示走的是通用错误分支；且 message 文案成了隐式契约 |
| admin 侧死代码 | `AgentTaskLogServiceImpl.save()`（只有单测调用）、`GET /api/admin/internal/agent-tasks/{id}/spec`（对应 agent-service 方法已 `@Deprecated`，全仓库零调用方） | **已删除**（commit 见 M0）：`save()` 及其单测、legacy spec 端点及其测试，以及 agent-service 侧 `AdminApiClient.getTaskAgentSpec`——它是那个端点的唯一"调用方"且自身零引用，整条死链一起摘掉，S3 搬迁时不再需要判断带不带过去。DTO `TaskAgentSpecResponse` 本身仍在 `harnax-entity`，留到 S3 随引用面一起处理 |

## 3. 目标架构

```
                    ┌──────────────────────────────────────────────┐
  浏览器 / CLI / 小程序 │                nginx (唯一入口)              │
                    └───────┬──────────────────────────┬───────────┘
                            │ /api/admin/agent-tasks/** │ /api/router/**
                            ▼                           ▼
                    ┌───────────────┐           ┌──────────────┐
                    │  harnax-admin │           │     router   │
                    │  校验用户 JWT  │           └──────┬───────┘
                    │  注入身份头转发 │                  │
                    └───────┬───────┘                  │
              internal JWT  │ HTTP（内网，不对浏览器开放） │
                            ▼                          │
        ╔═══════════════════════════════════════════════════════════╗
        ║  harnax-scheduler   （N 个实例，无状态，可任意 scale）        ║
        ║  ┌────────────────┐ ┌──────────────┐ ┌──────────────────┐ ║
        ║  │ CRUD Service   │ │ Quartz 引擎   │ │ AgentTaskJob     │ ║
        ║  │ + reconcile    │ │ JobStoreTX   │ │ (回查 agent_task)│ ║
        ║  └───────┬────────┘ │ isClustered  │ └────────┬─────────┘ ║
        ╚══════════╪══════════╧══════╤═══════╧══════════╪═══════════╝
                   ▼                 ▼                  ▼
        ┌─────────────────────────────────────┐   POST /api/router/agent/chat
        │  harnax_scheduler 库（Flyway 独占）    │        （携带 task-{id}-{uuid} 会话）
        │  agent_task / agent_task_log /       │
        │  agent_task_execution / QRTZ_* ×11   │
        └─────────────────────────────────────┘
```

### 3.1 职责划分

| 服务 | 负责 | 不再负责 |
|---|---|---|
| **harnax-admin** | 校验用户 JWT、解析 `tenantId`/`username`、以 internal token 转发到 scheduler、`agent-spec` 反查（问 scheduler 要 taskId→agentId） | 本域三张表的任何 SQL、Quartz、CRUD 业务规则、reload 广播 |
| **harnax-scheduler** | 任务定义存储、CRUD 校验与属主规则、Quartz 集群调度、执行与状态机、执行日志、僵尸回收、对账收敛、guard 清理 | 读别人的库（改造后 scheduler 只有一个数据源） |

### 3.2 数据归属

`harnax_scheduler` 库由 scheduler 的 Flyway 独占管理（`harnax-scheduler/src/main/resources/db/migration/`），admin 的 Flyway 不再涉及：

| 表 | 性质 | 谁写 | 谁读 |
|---|---|---|---|
| `agent_task` | 业务真相（任务定义） | scheduler CRUD | scheduler（注册 job、fire 时回查） |
| `agent_task_log` | 执行历史 + 运行时状态机 | scheduler | scheduler（并发拦截、回收）、admin 经转发读 |
| `agent_task_execution` | 集群抢锁记录（引擎实现细节） | scheduler | scheduler |
| `QRTZ_*` ×11 | 引擎状态 | 只有 Quartz | 只有 Quartz |

三张业务表与 `QRTZ_*` 同库，第 2.1 节那两条跨边界 SQL 就地消解，无需改造。

## 4. 数据模型

### 4.1 业务表

字段沿用 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:480-536` 的现状，迁库时按库表规范补齐每字段 COMMENT、`tenant_id` 索引与逻辑删除唯一键。

`agent_task` 关键列：`cron_expression`（6 段 Quartz 风格）、`task_status`（0 暂停 / 1 运行）、`concurrent`（0 禁止重叠 / 1 允许）、`timeout_seconds`（默认 300，驱动 `expireStale` 判定）、`is_public`、`creator`、`active`。

`agent_task_log` 的状态机是这套设计里最需要小心的部分：

```
  3 running ──正常结束：finishExecution──> 1 success
      │        └───异常─────────────────> 0 failed
      │
      ├── 任意节点请求停止：markStopping  3 ──> 4 stopping（前端立刻反馈）
      │                                    │
      │                    执行线程收尾 finalizeStopped            4 ──> 5 stopped
      │                    停止节点收到"无在途执行"（Missed）        4 ──> 5 stopped
      │                      —— 不等回收扫描，见 6.4
      │
      └── 超时未被收尾（节点死了）：expireStale  3|4 ──> 2 timeout
                                              │
                        真实结果事后回来：reclaimExpired  2 ──> {0,1}（覆盖回收的猜测，error_info 留标记）
                        停止确曾被读到过：reclaimExpired  2 ──> 5  （只有读到 4 的那个线程写得动）
```

即：`3 → {0,1,4,2}`、`4 → {5,2}`、`2 → {0,1,5}`，与 `AgentTaskLogMapper` 的类注释同源。`2 → 5` 是唯一的例外通道，写它的资格来自"我亲眼读到过 4"，不是来自能拼出这条 UPDATE。有一条拼不回来：sweep 在执行线程**重读之前**就把 4 改成 2，那一行和"从没被停止过"的行一模一样（`expireStale` 已覆盖 `error_info`），线程于是写回真实的 0/1，用户那次停止从记录上消失——根治要给停止意图一个独立列，见 spec 第 9 节 F11。

`finishExecution` / `finalizeStopped` / `markStopping` 都是**带状态条件的 UPDATE**，靠"影响行数为 0"判断被别的节点抢先定态（`SchedulerServiceImpl.kt:347-357`）。这套 CAS 语义要求日志表必须是执行节点能用 SQL 直连的库——这正是"日志表不能留在 admin 库、否则得把状态机包成 HTTP 条件更新接口"的原因。

`agent_task_execution` 的 `UNIQUE KEY uk_task_trigger (task_id, trigger_time)` 是集群抢锁的实现：插入成功即获得执行权，唯一键冲突即放弃。`trigger_time` 取 `JobExecutionContext.scheduledFireTime`（而非墙上时钟，`AgentTaskJob.kt:37-40`），保证各节点对"同一次触发"算出同一个键。

### 4.2 QRTZ 表

采用 Quartz 2.5.2 官方 `org/quartz/impl/jdbcjobstore/tables_mysql_innodb.sql`，剥掉脚本里的 `DROP TABLE` 段与注释块、只留 11 张 `CREATE TABLE`。本方案实际用到其中的 6 张，其余 5 张是引擎固定 schema 的一部分（`useProperties=true` 后 `*_BLOB_DATA` 列会保持为空）：

| 表 | 作用 |
|---|---|
| `QRTZ_JOB_DETAILS` | job 类名、`IS_NONCONCURRENT`（对应 `@DisallowConcurrentExecution`）、`REQUESTS_RECOVERY`、JobDataMap |
| `QRTZ_TRIGGERS` | `NEXT_FIRE_TIME`/`PREV_FIRE_TIME`、`TRIGGER_STATE`（WAITING/ACQUIRED/BLOCKED/PAUSED/ERROR） |
| `QRTZ_CRON_TRIGGERS` | cron 表达式 + `TIME_ZONE_ID` |
| `QRTZ_FIRED_TRIGGERS` | 正在执行的触发属于哪个实例——故障接管与 `requestsRecovery` 的依据 |
| `QRTZ_SCHEDULER_STATE` | 集群成员心跳（每节点每 `clusterCheckinInterval` 一次）；判断谁是死节点 |
| `QRTZ_LOCKS` | `TRIGGER_ACCESS`/`STATE_ACCESS` 两行悲观锁——**保证一次触发全集群只被一个节点抢到** |

## 5. Quartz 集群配置

```yaml
spring:
  quartz:
    job-store-type: ${QUARTZ_JOB_STORE:jdbc}
    jdbc:
      initialize-schema: never
    wait-for-jobs-to-complete-on-shutdown: true
    properties:
      org:
        quartz:
          scheduler:
            instanceName: HarnaxScheduler
            instanceId: AUTO
          threadPool:
            class: org.quartz.simpl.SimpleThreadPool
            threadCount: ${QUARTZ_THREAD_COUNT:25}
            threadPriority: 5
          jobStore:
            isClustered: "true"
            clusterCheckinInterval: "15000"
            misfireThreshold: "60000"
            acquireTriggersWithinLock: "true"
            tablePrefix: "QRTZ_"
            useProperties: "true"
```

逐项理由与几个容易踩的实现事实：

- **不写 `org.quartz.jobStore.class`**。已核对 spring-context-support 7.0.2 的 `SchedulerFactoryBean`：一旦注入了 DataSource，它在 prepare 阶段**强制**把 `jobStore.class` 覆盖成 Spring 的 `LocalDataSourceJobStore`（`org.quartz.jobStore.class` 常量与该类名都在该 class 的常量池里）。手写 `JobStoreTX` 不但会被覆盖，还会因为 Quartz 自己不认识 `quartzDataSource` 这个逻辑名而启动失败。
- **不需要 `@QuartzDataSource`**。Boot 4.0.1 的 `QuartzAutoConfiguration`（已拆到独立的 `spring-boot-quartz` 模块）在 `job-store-type: jdbc` 时自动接入唯一的 DataSource。scheduler 改造后确实只有一个数据源。第 10 节的 C1 方案（业务表与引擎表分库）才需要那个注解。
- **`initialize-schema` 默认值是 `embedded`**，MySQL 下本就不会建表；这里显式写 `never` 是把"schema 归 Flyway 独占"固化成约定，防止有人改成 `always` 导致 Boot 与 Flyway 抢建表。
- **`instanceName` 全集群必须一致**。它是 `QRTZ_*` 表的 `SCHED_NAME` 列，集群成员靠它相认；反过来，同一套库里放两个不同 `instanceName` 的部署是可以的（互不干扰），但一环境一库更清晰。
- **`instanceId: AUTO` 在容器化下可用**（由 hostname + 时间戳 + 线程生成，容器 hostname 天然唯一），但 compose 里必须删掉 `container_name: harnax-scheduler`（`docker-compose.yml:270`）——固定容器名既阻碍 `--scale`，也可能造成 hostname 重复从而 instanceId 撞车。
- **时钟要求**。集群靠比对 `QRTZ_SCHEDULER_STATE.LAST_CHECKIN_TIME` 判断节点死活，节点间时钟偏移超过 checkin 间隔会误判死亡并触发误抢。所有节点必须 NTP 同步。另外 cron 表达式在 JVM 默认时区解释，各节点 TZ 要一致（compose 已统一挂载 `/etc/localtime`）。
- **`threadCount` 从 10 提到 25**：执行改由 Quartz 工作线程同步跑（见 7.3），一次执行的 HTTP 读超时默认 300s（`scheduler.timeout-seconds`），10 个线程会被长任务占满。相应地 Hikari `maximum-pool-size` 从 10 提到 30——JDBC store 的每次 trigger 获取与每个执行线程都要占连接，经验值是 ≥ `threadCount + 5`。
- **`useProperties: true`**：JobDataMap 以文本 kv 存进 `QRTZ_JOB_DETAILS`，配合 7.2 的"只放 taskId"，引擎表里不再有任何 Java 序列化 BLOB，实体字段变更不会让存量任务反序列化失败。注意此时 **taskId 必须放成 String**（Long 会被拒）。
- **`waitForJobsToCompleteOnShutdown: true`** 会让停机最多等一个任务超时，需要与编排的 stop grace period 一起调，否则会被 kill 窗口截断。**已落地**：它就是 Boot 4.0.1 `spring-boot-quartz` 的标准属性 `spring.quartz.wait-for-jobs-to-complete-on-shutdown`（该版本 configuration metadata 里 `defaultValue=false`，所以必须显式写），**不需要** `SchedulerFactoryBeanCustomizer`；`application.yml` 写 `${QUARTZ_WAIT_FOR_JOBS:true}`，compose 侧配 `stop_grace_period: 400s`（逐项相加 = chat 读超时 300 + clearSession 60 + 两次调用各 10s 的 connect 预扣 20 + 定态写回/释放锁 8 + Spring 关停钩子 4 = 392，向上取整；算式在两处注释里；`clearSession` 自第三批起有自己的读超时上限 `scheduler.clear-session-timeout-seconds=60`，不再共用 chat 的 300s，否则一次执行最坏占用是 600s）。**保护范围只到 Quartz 认得的路径**（cron 与 `/run-once`）：手动 `/trigger` 走裸 daemon 线程，停机不等它，S4 之前不要在任务执行中重启 scheduler。

## 6. 任务生命周期全链路

### 6.1 创建 / 更新 / 删除

```
用户在 webui 新建任务
  → POST /api/admin/agent-tasks        （admin：JwtAuthenticationFilter 校验、TenantContext 解析）
  → 转发 POST /api/scheduler/agent-tasks （带 X-Forwarded-User / X-Tenant-Id，internal JWT 签名）
  → scheduler CRUD Service：校验 cron、写 agent_task、同事务内注册 job
```

同库同服务后，"写表 + 注册 job"可以在一个 `@Transactional` 里完成（`LocalDataSourceJobStore` 的设计目的正是加入 Spring 事务）。这一步需实测验证；若事务传播不成立，退化为"先写表，由 60s 对账兜底"，正确性不受影响。

admin 侧 `schedulerClient.reloadTasks()` 的广播（`SchedulerClientImpl.kt:50`、`AgentTaskServiceImpl.kt:138,158`）整条删除——**不存在"通知另一个进程"这件事了**。

### 6.2 注册

```
AgentTaskJob 类（concurrent=0 时用 AgentTaskNonConcurrentJob）
JobKey     = AgentTask_{id}   / group AgentTaskGroup
TriggerKey = AgentTask_{id}_trigger
JobDataMap = { "taskId": "123" }        ← 只有 ID，不放实体
misfire    = concurrent==0 ? DoNothing : FireAndProceed
```

`concurrent` 的语义今天只被翻译成 misfire 指令（`SchedulerServiceImpl.kt:149-153`），**拦不住重叠执行**——misfire 只在触发被错过时生效，前一次还在跑时新触发照样 fire。真正的开关是 `@DisallowConcurrentExecution`，而它是**类级注解**，无法按 job 实例切换，所以拆两个 job 类由 `scheduleTask` 选（7.2）。

### 6.3 触发与执行

```
QRTZ_TRIGGERS.NEXT_FIRE_TIME 到期
  → 某个节点在 TRIGGER_ACCESS 锁内把该行 ACQUIRED（全集群只有一个节点成功）
  → 该节点 Quartz 线程执行 AgentTaskJob.execute()
      ├─ guard.tryAcquireLock(taskId, scheduledFireTime)  ← 兜底去重（正常恒成功）
      ├─ 按 taskId 回查本地 agent_task
      ├─ 插 agent_task_log(status=3, session_id=task-{id}-{uuid})
      ├─ POST router /api/router/agent/chat  →  session-router → agent-service
      └─ 结束：finishExecution CAS 定态 1/0；被请求停止则 4→5；清 session
```

`agent-spec` 反查链保持不动：agent-service 拿 `task-{id}-{uuid}` 回问 `GET /api/admin/internal/agent-spec/{sessionId}`，admin 的 `resolveFromTask()` 解出 taskId 后**只需要一个 `agentId`**。今天它仍靠本地 `agentTaskMapper.selectAnyById(taskId)` 回查拿 `agent_id`（分库后即失效）。目标形态是评审 D4 定下的**把 agentId 编进 sessionId**（`task-{taskId}-{agentId}-{uuid}`，契约 C1），届时这里退化成一次纯字符串解析取 `parts[2]`，不再有任何跨服务端点或跨库查询——原先计划的 `GET /api/scheduler/agent-tasks/{id}/agent-id` 端点**已作废**（见第 11 节 3.2）。下游 agent→model 装配完全不变。

### 6.4 停止

停止是**跨节点**的，而且这在本轮之前就已经做对了：

```
前端点「停止」→ admin 转发 → scheduler 任一节点 stopTask(logId)
  ├─ markStopping: 3→4（CAS，抢不到说明已定态）
  ├─ routerClient.sendCommand(sessionId, INTERRUPT)   ← 会话 ID 存在日志行里，所以任何节点都能发
  │     它走自己的 10s 读超时（scheduler.command-timeout-seconds），不共用 chat 的 300s：整条 /stop 必须
  │     落在 admin 转发那 30s 之内，否则用户先看到失败、状态却仍不确定；超时照样是 Unanswered 而非 Missed
  ├─ 送达（有实例答"我这儿有在途执行"）→ 真正执行的那个线程收尾时看到 4，写成 5
  ├─ Missed（没有任何实例在推进它）→ **停止节点当场** finalizeStopped 4→5，不等回收扫描
  └─ Unanswered（命令根本没送到）→ 行留在 4：谁也不知道执行是否还活着，交给执行节点或回收扫描
```

写 5 的因此是**两个**地方：执行线程（它拿到过真实结果）与受理停止的节点（它拿到过一个"未命中"答复，那本身就是结论）。第二条边是 D7/G5 换回来的——未命中不再被折叠成"已送达"，否则一行停在 4 的执行最后会被 `expireStale` 写成 `2 timeout`。

正因为状态在 DB 行上而不是内存里，admin 不需要知道"这个任务在哪个节点上跑"，转发也不必定向到某个实例——这是第 10 节淘汰"固定发 `urls[0]`"的依据。注意它反过来也成立：**受理停止的节点不是执行节点时，它写 5 的依据只有那个"未命中"答复**，所以 `SchedulerClientImpl.broadcast` 的多实例折叠会直接把这条边变成 bug（该处的债注释）。

### 6.5 回收

`expireStale` 把超过 `timeout_seconds × 1.5` 仍未定态的行（3 或 4）判为 `2 timeout`，调用点是启动加载、housekeeping 每 5 分钟一轮、以及 fire 前的并发判断——最后这一处自 G7 起被限流成**每节点每 30s 至多一次**（它是扫 `idx_status` 活跃端的 UPDATE，和同一时刻插入新行的抢锁在 MySQL 上互撞，输的那方丢的是真执行）。它是节点被 kill 之后不留永久"运行中"僵尸的最后防线。

"最后"是关键字：`2` 不是不可逆的。执行线程事后带着真实结果回来时走 `reclaimExpired`，把这一行覆盖成 `0/1`（并在 `error_info` 留"完成于自动超时之后"的标记），或在自己读到过 4 的情况下覆盖成 `5`（见 4.1 状态图的 `2 → {0,1,5}` 三条边）。回收扫描因此只能是猜测的兜底，不能当定论的出口。

## 7. 三处引擎层逻辑改动

### 7.1 对账取代"全删重建"

`loadTasksToScheduler()`（`SchedulerServiceImpl.kt:172-218`）现在的第一步是把 `AgentTaskGroup` 里所有 job 删光（:178-189），再按 DB 重建。RAMJobStore 下这是幂等的正确做法；共享 JobStore 下它是**集群级破坏操作**：任一节点重启或收到一次 reload，都会瞬时报掉全集群的定时任务，两个节点同时 reload 还会互相抢删并撞 `ObjectAlreadyExistsException`。

改成 diff 收敛：

```
期望态 = agent_task 中 task_status=1 AND active=1 的 { taskId → (cron, concurrent) }
实际态 = QRTZ store 中 AgentTaskGroup 的 { jobId  → (cron, job 类) }

  期望有、实际无   → 注册
  实际有、期望无   → 删除
  两边都有但 cron/job 类不一致 → reschedule
  两边一致                      → 不动（保留 PREV_FIRE_TIME，不重置执行历史）
```

函数幂等且收敛，两个节点同时跑也安全（抢注失败即视为已被别人完成）。三处复用：启动、admin 转发 CRUD 后、每 60s 集群内 job。

对账 job 自己就存在共享 store 里，因此**全集群每 60s 只有一个节点执行**——这是本轮集群化最直观的一个收益演示。它同时暴露了一个以前看不见的事实：加 `scheduler.reconcile.drift{action=add|remove|update}` 计数器，CRUD 与 store 一旦漂移就会在指标上显形，而不是等用户投诉任务没跑。

### 7.2 JobDataMap 只存 taskId

`SchedulerServiceImpl.kt:135-136` 现在把整个 `AgentTask` 对象塞进 JobDataMap。RAMJobStore 下无所谓，JDBC store 下它会被 Java 序列化进 BLOB：实体字段一变（这个仓库改实体很频繁），存量任务在重启后直接反序列化失败；而且改了 prompt/cron 不重新注册就不生效。改为只存 `taskId` 字符串，fire 时回查 `agent_task`——顺便让"任务定义的唯一真相"落回业务表。

### 7.3 手动执行走 Quartz one-shot 投递

现在 `triggerManually()`（:268-299）从库里读任务、检查并发、抢锁，然后**起一个裸 daemon 线程直接跑**（:290-296）。同一模块里另外还有个 `runTaskOnce()`（:239-266）走 Quartz one-shot 投递，两套重复的去重逻辑，而 admin 只用前者、且固定发给 `urls[0]`（`SchedulerClientImpl.kt:42`）。

裸线程有三个问题：那个节点挂了手动执行就不可用；请求进程在 fire 前崩掉这次执行静默丢失；Quartz 以为 job 秒回，`@DisallowConcurrentExecution` 与 `requestsRecovery` 全部失效。

合并成一个：往共享 store 投 `startNow()` 的 one-shot trigger（group `AgentTaskGroup_ONCE`，非 durable，fire 完自动清理）。admin 打到任意节点都行，投递即落库，节点崩了 Quartz 补火。`hasActiveRunningLog`（:423-426）保留为"同一任务不许并发手动跑"的业务级拦截，guard 保留为集群兜底。

## 8. 跨服务边界与鉴权

### 8.1 admin → scheduler 的转发契约

| 项 | 做法 |
|---|---|
| 出站签名 | 复用现成的 `InternalTokenProvider` + `AuthRestTemplateInterceptor`（`SchedulerClientImpl.kt:28-38`），签 `typ=internal` HMAC JWT。admin 已在发，只是 scheduler 今天不校验 |
| 地址 | `harnax.scheduler.url` 支持逗号分隔多实例；本轮不再需要广播，任选可达实例即可，建议指向 nginx 上游或 K8s Service |
| 身份透传 | `X-Forwarded-User`（当前登录用户名，用于属主过滤）+ `X-Tenant-Id`（取自 `TenantContext`，只在 create 时用，与现状一致） |
| 幂等 | CRUD 转发失败即返回错误，不留"DB 已改但通知丢了"的中间态；对账是最后兜底 |

### 8.2 scheduler 的入站保护（本轮范围与偏离说明）

用户明确要求本轮**不改鉴权代码**：`harnax.auth.enabled` 保持 false，不引入 `@InternalOnly`/`UnifiedAuthFilter`。

> **一处有意的偏离**（实施时按批准的方案执行）：本轮把 scheduler 的写面从「trigger/start/pause」扩大到「全量 CRUD」，因此加一个约 20 行的拦截器，只校验 admin 签的 `typ=internal` JWT（用已存在的 `harnax.auth.internal.shared-secret`，不接入 harnax-auth 的自动装配）。理由是裸放在 docker 内网的全量写接口不再可接受。若否决，替代方案是完全靠网络隔离（删除 nginx location + 不发布端口）。

必须记录在案的事实：`AuthAutoConfiguration.kt:41-54` 的 `unifiedAuthFilter` bean 带 `@ConditionalOnProperty(harnax.auth.enabled, havingValue="true", matchIfMissing=true)`，scheduler 显式写了 `false` → **这个 filter 根本没被创建**，不是"创建了但放行"。而 `SchedulerClientImpl.kt:35` 的注释"The scheduler runs UnifiedAuthFilter"与事实相反。同时 `docker-compose.yml:293-294` 把 28084 直接发布到宿主机，`nginx.conf:178-192` 的 `/api/scheduler/` 无任何访问控制，`/actuator/prometheus` 与 `/swagger-ui.html` 匿名可达。日志接口又是 `SELECT *` 全文下发 `prompt`/`response`（无脱敏）。这些是本轮的部署层收紧项与第 13 节的 fast-follow 清单。

> 状态更新（第二轮 R1）：上面两条网络事实已关闭——`docker-compose.yml` 去掉 `28084:8084` 宿主映射、改 `expose: ["8084"]`，`nginx.conf` 的 `location /api/scheduler/` 整段删除，scheduler 只在容器网络 `http://scheduler:8084` 可达。仍未关闭的是 `/actuator/prometheus`、`/swagger-ui.html` 的匿名可达与 scheduler 侧自鉴权（internal-token 拦截器 / `harnax.auth.enabled`），属 S3 与 F2。

另外要清楚：`UnifiedAuthFilter` 即便打开也**验不了用户的登录 JWT**——它用 `harnax.auth.internal.shared-secret` 验签，而 admin 签用户 token 用的是另一个 key `jwt.secret`（`InternalTokenProvider.kt:59-66` 的注释明确区分了两者）。仓库内唯一的服务自鉴终端用户范式是 session-router 的 `X-Api-Key` + `RemoteApiKeyStore`（回源 admin 校验 + Caffeine 缓存）+ `SessionAccessGuard` 的手写租户判断。走那条路要给 scheduler 复刻一遍，并让三个客户端改发送的凭证——正是第 10 节 B 方案要避开的成本。

## 9. 可观测性

| 信号 | 含义 | 位置 |
|---|---|---|
| `/actuator/health` 的 `scheduler` 指示器 | 本进程是否真的在调度：`quartzStarted`、`instanceId`（集群下的真实实例名）、job 数、最近一次加载成功时间与错误 | `SchedulerHealthIndicator.kt:34-47` |
| `/actuator/health/liveness` | 只回答"进程活着吗"，**故意不含**上面的指示器——数据库慢不该让容器重启掉自己的重试循环 | 设计说明在 `SchedulerHealthIndicator.kt:11-19` 注释 |
| `scheduler.load.attempts{outcome}` | 启动加载重试的成败计数 | `SchedulerMetrics.kt:28-34` |
| `scheduler.jobs.scheduled` | **本实例视图，不是集群视图**：store 仍是 `memory`，gauge 读本进程 Quartz 的 `getJobKeys(AgentTaskGroup)`，每实例各报自己注册的那一份。S2 换 JDBC 集群 store 后同一表达式静默变成集群视图，看板与告警阈值届时要重解读（spec 第 9 节 F9） | `SchedulerMetrics.kt:32-35`、`QuartzJobInventory.kt` |
| `scheduler.reconcile.drift{action}` | 对账发现的漂移量，CRUD 与 store 脱节的唯一早期信号 | 未实施（S4） |
| `QRTZ_SCHEDULER_STATE` | 运维直查：有几行、各自 checkin 是否在推进，是判断"第二台到底进没进群"最快的办法 | — |

## 10. 方案决策记录

淘汰项一并留下，避免日后重新论证。

### 10.1 引擎层：抢锁去重 vs 真集群

保持 RAMJobStore + `agent_task_execution` 唯一键抢锁（改动极小）被否决：它拿不到 misfire 补偿、故障接管和负载均衡，且每节点各自 fire 是持续的无效功。选择真 JDBC 集群，抢锁降级为兜底。

### 10.2 QRTZ 表放哪

| 方案 | 内容 | 结论 |
|---|---|---|
| A | 放 `harnax_admin`，由 admin 的 Flyway 建 `V27` | 零配置改动，但 scheduler 上线被 admin 升级顺序绑住；引擎心跳写与业务 OLTP 抢同库。曾按此推进 |
| B | 仍在 `harnax_admin`，scheduler 开自己的 Flyway、独立 history 表 | 解掉升级依赖，需要 DDL 权限与双 history 表并存 |
| C | 独立 `harnax_scheduler` 库 | ✅ 采用。引擎状态彻底独立、可单独备份调优，schema 归属唯一 |

C 方案下 scheduler 需要两个数据源（业务库 + 引擎库，靠 `@QuartzDataSource` 区分）——这是当时评估出的唯一代价，也是引出第 10.3 节的入口。

### 10.3 域边界：业务表跟不跟走

| 方案 | 内容 | 结论 |
|---|---|---|
| C1 | 只把 `QRTZ_*` 挪进新库，业务三表留 `harnax_admin`，scheduler 双数据源 | 改动最小，但 scheduler 仍依赖 admin 的库，"独立建库"只独立了引擎状态 |
| C2 | `agent_task` 留 admin（走 internal API 拉），日志/抢锁表搬 scheduler | ❌ 淘汰。需要新增任务定义镜像表（否则每次凌晨触发硬依赖 admin 可用），且 `last_run_status` 跨库改造。经 M0~M5 拆解后估 16 人日且架构最难看 |
| 全量搬 + 客户端直连 scheduler | scheduler 对浏览器暴露 CRUD | ❌ 淘汰（多花 3 人日）。用户 JWT 验不了（8.2），要复刻 router 的 X-Api-Key 范式并改三个客户端；日志全文 `prompt`/`response` 摊到浏览器路径 |
| **B：全量搬 + admin 薄鉴权代理** | 表、实体、CRUD、调度全归 scheduler；admin 仍校验 JWT 并带身份转发 | ✅ **采用**。三客户端零改动，scheduler 不对浏览器开放，跨库 JOIN 全部消解，估 14.5 人日 |

### 10.4 数据

> **本节的结论已被取代（2026-09-11 评审 D8）**：改为**迁任务定义 `agent_task`、不迁历史日志**。理由是选定独立 `harnax_scheduler` 库后，两库同在一个 MySQL 实例内，跨库 `INSERT ... SELECT` 是一次几十行的脚本，而"人工重建任务"在任务量非零时是真实的运维负担与出错源。完整策略与发布顺序见 [spec 第 10 节](../docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md)。下面的原始推理保留备查。

不迁数据、上线后人工重建。备选的一次性 `INSERT ... SELECT`（同实例跨库）与双写渐进都被否决：当前环境任务量小、历史日志价值低，而双写要写两套随后即弃的临时代码并显著抬高测试量。代价是明确的——历史执行日志丢弃，需在发布公告里写明。

## 11. 实施计划与进度状态表

> **本节的里程碑编号已被 2026-09-11 的设计评审重排。** 新的决策清单、实施计划（S0~S4）与验收标准见 [docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md](../docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md)。两处顺序修正必读：
> - **同步执行必须先于 Quartz JDBC store 上线**。**已按此顺序落地**（S1，commit `1aa9981`）：改造前 `AgentTaskJob` 起裸 daemon 线程后立刻返回，Quartz 认为 job 秒完、`QRTZ_FIRED_TRIGGERS` 不留行，于是故障接管、`waitForJobsToCompleteOnShutdown`、`@DisallowConcurrentExecution` 三者同时失效——按原 M1→M4 顺序会先得到一个"名义集群"。目前定时路径已同步执行，**只剩手动 trigger 路径仍起 daemon 线程**（one-shot 合并属 S4）。
> - **对账必须与 JDBC store 同期**。共享 store 下 `loadTasksToScheduler()` 的"全删重建"等于任一节点重启就报掉全集群任务。
>
> 下面的 M0~M5 表格保留作为**工作项清单**，不再是进度真相源。状态列已于 2026-09-13 随 `fix/scheduler-exec-semantics`（spec 里程碑 S0+S1）逐项重新对照代码核对，M0 已整节完成。

### M0 行为修复（独立合入，必须最先）—— **已完成**

不搬代码，先把三个洞补在当前仓库，否则 M2 搬运时会丢。

| # | 项 | 状态 |
|---|---|---|
| 0.1 | `selectById`/`updateById`/`deleteById` 补属主可见性条件，与 `selectTaskList` 口径一致；调用方传当前用户 | ✅ 已完成（commit `8e70819`；服务间无用户上下文的调用另走 `selectAnyById`） |
| 0.2 | CLI 的日志字段 `logId` → `id`。**并需补 `task stop` 子命令**——`task.go` 里根本没有该命令，"从 CLI 停止运行中任务从来没生效过"的真实原因是功能缺失，不只是字段名读错 | ✅ 已完成（commit `a09da87`，字段改 `id` + 新增 `task stop`） |
| 0.3 | trigger 冲突改业务 code `40901`，前后端一起改。补充事实：现状文案匹配并非"从不命中"——业务并发那条会拼进 `e.message` 因而能命中，漏的是集群抢锁失败的 `"Task is already being executed by another instance"` | ✅ 已完成，且实际落地**三个码**：`40901 CODE_EXECUTION_IN_PROGRESS`（scheduler 出码，含本实例并发与集群抢锁失败两种）、`40902 CODE_SCHEDULER_SYNC_FAILED`（admin："已存库但没有 scheduler 重载它"）、`40903 CODE_SCHEDULER_DISABLED`（实例关了调度）。**前端必读的坑**：`ResultVo.success=false` 会被全局 `errorThrower` 转成抛出的 `BizError`，页面拿不到 response 对象，所以判码处要显式传 `skipErrorHandler` 再读 `info.errorCode` |

### M1 scheduler 自建库 + Quartz JDBC 集群

| # | 项 | 状态 |
|---|---|---|
| 1.1 | `db/migration/V1__quartz_tables.sql`（11 张表，剥 DROP） | ⏳ |
| 1.2 | `V2__agent_task_domain.sql`（三表 DDL 落新库） | ⏳ |
| 1.3 | `application.yml`：数据源指新库、Flyway 开、quartz 集群段、Hikari 池 | ⏳ |
| 1.4 | pom：testcontainers + failsafe/surefire IT profile（照 `harnax-admin/pom.xml:344-393`） | ⏳ |
| 1.5 | 部署：`init-databases.sql` 建库授权、compose 环境变量、删 `container_name`、删 28084 映射、`.env.example` 补全 | ⏳ 部分：**暴露面两半已落**（R1：宿主映射 `28084:8084` 改 `expose`、nginx 的 `/api/scheduler/` location 已删并留禁止回加的注释）；**D6 优雅停机已落**（compose `stop_grace_period: 400s` + `application.yml` 显式 `spring.quartz.wait-for-jobs-to-complete-on-shutdown: ${QUARTZ_WAIT_FOR_JOBS:true}`，两处都有算式注释；只覆盖 cron 路径，手动 `/trigger` 见 5 节那条）。仍未做：建库授权、删 `container_name`、`.env.example` |

**里程碑验收**：scheduler 单实例以 JDBC store 正常启动；两实例连同库时日志出现 `ClusterManager` checkin、`QRTZ_SCHEDULER_STATE` 两行；kill 一个节点另一个能接管。

### M2 实体与 mapper 迁进 scheduler

| # | 项 | 状态 |
|---|---|---|
| 2.1 | 三实体 + 三 mapper + 三 XML 从 harnax-entity **移动**到 scheduler（含 `@MapperScan`、`type-aliases-package`、XML 全限定 type 同步） | ⏳ |
| 2.2 | `AgentTaskLogMapperTest` 随迁；`schema-test.sql` 删除对应段与种子 | ⏳ |
| 2.3 | scheduler 自带 `Page`/`BizException`/`ApiErrors`/`GlobalExceptionHandler` 小份副本 + pagehelper 依赖 | ⏳ |

**注意**：M2 完成即 admin 编译断裂（已确认引用面只有 admin 与 scheduler，agent-service / channel / tools-sdk / harness-core 均不 import 这些类）。**M2 与 M3 必须同一个 PR**，不得单独发布。

### M3 CRUD 落位 + admin 薄代理（原子切换）

| # | 项 | 状态 |
|---|---|---|
| 3.1 | scheduler：CRUD service、日志 service（不迁死代码 `save()`）、4 个 DTO、`/api/scheduler/agent-tasks/**` controller（12 端点，保持 `records`/`total`/`id` 契约） | ⏳（其中"不迁 `save()`"已无对象——admin 侧的 `save()` 已随 S0 删除） |
| 3.2 | ~~scheduler：`GET /agent-tasks/{id}/agent-id` internal 端点~~ | ❌ **已作废**（评审 D4：agentId 编进 sessionId，见 spec 契约 C1。该端点、其测试与一跳 admin→scheduler 转发均不再需要） |
| 3.3 | scheduler：约 20 行 internal-token 校验拦截器（8.2 的偏离项） | ⏳ |
| 3.4 | admin：`AgentTaskController` 瘦身为鉴权 + 转发，路径不变；注入 `X-Forwarded-User`/`X-Tenant-Id` | ⏳ |
| 3.5 | admin：删 `AgentTaskService(+Impl)`、`AgentTaskLogService(+Impl)`、4 DTO、`SchedulerClient` 广播逻辑 | ⏳ |
| 3.6 | admin：~~`resolveFromTask` 改调 agent-id~~ → 改为**纯字符串解析**取 `parts[2]` 当 agentId，`split` 用 `limit=4`，并**删除对 `agentTaskMapper` 的依赖**；~~删除死端点 `/internal/agent-tasks/{id}/spec` 及其测试~~ | ⏳ 仅剩 `resolveFromTask` 的解析改造（形态按 spec C1 调整）。**死端点及其测试、以及 agent-service 侧零引用的 `AdminApiClient.getTaskAgentSpec` 已在 S0 删除**，`InternalApiController` 里现已不存在该路由 |

**里程碑验收**：webui 列表（含 `lastRunStatus`/`lastRunTime`）、创建/编辑/启停/删除、立即执行、日志弹窗轮询全通；CLI 与小程序各跑一遍。回滚点 = revert 整个 PR（旧表数据仍在）。

### M4 对账 + one-shot + housekeeping

| # | 项 | 状态 |
|---|---|---|
| 4.1 | `loadTasksToScheduler()` → `reconcile()` diff 收敛，删除全删重建 | ⏳（与 JDBC store 同期，S2） |
| 4.2 | JobDataMap 只放 `taskId` 字符串；`AgentTaskJob` 回查库 | ⏳ |
| 4.3 | `AgentTaskNonConcurrentJob` + 按 `concurrent` 选 job 类 | ✅ 已完成（commit `1aa9981`，与 4.4 的同步执行同批） |
| 4.4 | 手动执行合并为 Quartz one-shot，废弃裸线程；`AgentTaskJob` 改同步执行 | ⏳ **两半拆开**：同步执行 ✅ 已完成（commit `1aa9981`，`AbstractAgentTaskJob.run()` 在 Quartz 线程内跑完才返回；正是这一步让 D6 的 `waitForJobsToCompleteOnShutdown`、`@DisallowConcurrentExecution` 与故障接管第一次真正生效，也才让 `stop_grace_period` 有意义）。one-shot 合并 ⏳（S4，`triggerManually` 仍起 daemon 线程） |
| 4.5 | 60s reconcile 集群 job；housekeeping job 挂 `cleanupOldExecutions()`（当前零调用方） | ⏳ **两半拆开**：housekeeping ✅ 已完成（commit `b7a33f7` + `ca04de0`，5 分钟一轮，四件事：`expireStale` / 日志 90 天保留 / guard 行 7 天过期 / `status=0` 泄漏锁回收，类上 `@DisallowConcurrentExecution` 防自己叠跑）。60s reconcile job ⏳（S2） |
| 4.6 | 健康/指标改集群语义；新增 `scheduler.reconcile.drift` | ⏳。已做的只是把 store 读取抽到 `QuartzJobInventory`（消掉 metrics↔service 构造环，commit `0f4bbc2`），指标语义仍是本实例视图（见第 9 节与 spec F9） |

### M5 DROP 旧表 + 暴露面 + 文档

| # | 项 | 状态 |
|---|---|---|
| 5.1 | admin `V27__drop_agent_task_tables.sql`。**硬约束：晚于 scheduler 上线、观察过至少一个完整 cron 周期、运维签认后才合入** | ⏳ |
| 5.2 | nginx `/api/scheduler/` 改 allowlist 或删除；若保留补 `client_max_body_size` | ⏳ |
| 5.3 | 重写 `docs/agent-task-design.md` 架构节；修 `docs/deploy-harnax-admin.md:135` 的 RAMJobStore 说法；新建 scheduler 部署文档 | ⏳ |

### 工作量

M1 2.5 + M2 2 + M3 3.5 + M4 3 + M5 2 = 共通 13 人日，加形态 B 的转发层 1 人日、M0 的 0.5 人日 ≈ **14.5 人日**。最大单项是测试重写（约 1900 行有效测试 + 5 个新集成测试）。

## 12. 测试策略

新增集成测试全部放 scheduler 模块，走 failsafe profile（`-Pintegration-test`），testcontainers 起 MySQL 跑真库——本域的难点（集群抢锁、CAS 定态、diff 收敛）全是 mock 测不出来的。

既有测试处置：`AgentTaskServiceImplTest`(603) 迁 scheduler 重写为真库 IT；`SchedulerClientImplTest`(452) 随广播逻辑删除、保留转发用例；`AgentTaskLogServiceImplTest`(271) 删除（被测对象是死代码）；`AgentTaskControllerTest`(657) 拆成 admin 转发契约测试 + scheduler CRUD 测试；`AgentTaskSchedulerIT`(208) 与 `AgentTaskCrudIT`(171) 迁入 scheduler。scheduler 现有 3 个 Mockito 单测（startup-load / stop 状态机 / health）保留并按新签名调整。

| 用例 | 断言什么 | 为什么值得写 |
|---|---|---|
| IT-1 集群单触发 | 两实例连同库，1s cron 跑 8s：`agent_task_log` 行数 ≈ 触发次数（不是 2×），`QRTZ_FIRED_TRIGGERS` 里出现过两个实例名 | 这是"真集群"与"各节点各 fire"的唯一硬证据 |
| IT-2 对账收敛 | 用 SQL 手造漂移（多删/少建）→ `reconcile()` → store 收敛，且保留 job 的 `PREV_FIRE_TIME` 未被清零 | 后半句是"没有回归成全删重建"的直接证据，否则这个测试会被一个错误实现骗过 |
| IT-3 越权 | 非属主身份读/改/删 → 拿不到且数据未变；`is_public=1` 可访问 | M0 修复的回归护栏 |
| IT-4 one-shot | 连发两次 trigger：store 出现 `_ONCE` trigger、第二发被拒返回 40901；模拟投递后 kill 进程，重启补火一次且仅一次 | 覆盖 7.3 的两个新语义 |
| IT-5 guard 清理 | 造过期 `agent_task_execution` 行 → 跑 housekeeping → 过期行删除、未过期保留 | `cleanupOldExecutions()` 此前零调用方 |

## 13. 已知问题与 fast-follow

明确不在本轮：

> 已从本清单移除的一条：`AgentTaskJob.interrupt()` 的名义空实现**已随 S1 摘掉**——两个 job 类都不再实现 `InterruptableJob`（真正的停止是发给 router 的 INTERRUPT 命令，见 6.4），广告一个 Quartz 级中断只会误导读者。

1. **scheduler 服务自鉴权**：`harnax.auth.enabled=true` 让 `UnifiedAuthFilter` 真正装配、端点加 `@InternalOnly`。（`SchedulerClientImpl.kt:35` 那句与事实相反的注释已在 S0 改为如实描述：scheduler 侧不装配过滤器，admin 发的 internal bearer 目前无人校验，是向前兼容。）
2. **生产收窄匿名面**：关 `SWAGGER_ENABLED`，`management.endpoints.web.exposure` 收窄（现在 `/actuator/prometheus` 随 8084 匿名可达）。
3. **日志脱敏**：`agent_task_log` 查询 `SELECT *` 全文下发 `prompt`/`response`/`errorInfo`，且无租户/属主过滤。admin 侧已有 `AgentTaskLogResponse`（1:1 全字段、当前未被 controller 使用），本可作为裁剪出口，本轮未启用。属主口径已修（读日志要通过任务可见性join），脱敏仍未做。
4. **`/api/admin/agent-tasks/{id}` 之外无细粒度授权**：整个域只要求"已登录"（`SecurityConfig.kt:52-53` 兜底），无角色/权限码；`tenant_id` 只在 create 用一次（`AgentTaskServiceImpl.kt:79`），`MybatisTenantInterceptor` 的 `intercept()` 实际是 no-op。属主条件（M0.1）是目前唯一的隔离手段，比租户隔离更弱。
5. **`agent_task_execution` 缺索引**：✅ **已落地（第三批次 G4，V27）**——补了 `(status, create_time)` 与 `(create_time)`，并同步了 `harnax-entity` 的 `schema-test.sql`。原本的状况：现表只有 PK、`uk_task_trigger(task_id, trigger_time)`、`idx_task_id`、`idx_trigger_time`，`status` 与 `create_time` 都没有索引，而 S1 的 housekeeping 把 `deleteOldExecutions`（按 `create_time`）与 `deleteStaleRunning`（按 `status = 0 AND create_time < ...`）挂成每 5 分钟一轮，这张表又按触发次数线性增长 → 每轮两次全表扫，扫描范围锁与 `tryAcquireLock` 的 INSERT 互顶。真库 `EXPLAIN` 命中验证随 B1 在有 Docker 的环境补跑。对应 spec 第 9 节 F10。
6. **`scheduler.jobs.scheduled` 的语义跳变**：现在（RAM store）它是**本实例视图**，S2 换 JDBC 集群 store 后同一表达式静默变成集群视图，看板与告警阈值必须届权重解读。对应 spec 第 9 节 F9。

## 14. 运维 checklist

1. MySQL 建 `harnax_scheduler` 库 + 授权（或直接跑新的 `init-databases.sql`）。
2. `.env` 补 scheduler 变量；**确认所有 scheduler 节点 NTP 同步**。
3. `mysqldump` 三张旧表留档（不迁数据，日志会随 5.1 的 DROP 永久丢失）。
4. 合入 M2 + M3 同一个 PR 并构建。
5. 停旧 scheduler → 起新 scheduler 单实例（Flyway 自动建表；`SCHEDULER_INSTANCE_ID` 留空走自动生成）。
6. 起第二实例，查 `QRTZ_SCHEDULER_STATE` 应有两行。
7. 更新 nginx（allowlist 或删 `/api/scheduler/`）+ 确认 28084 不再发布到宿主机。
8. **人工重建定时任务**，观察至少一个完整 cron 周期，核对 `agent_task_log` 与列表页的 `lastRunStatus`。
9. 签认后合入 `V27__drop_agent_task_tables.sql`。
10. 发布公告：任务未迁移、历史日志已弃、需重建。
