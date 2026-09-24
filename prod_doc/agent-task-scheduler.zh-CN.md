# Harnax 定时任务与 Scheduler 服务全流程（中文）

> 本文覆盖「智能体定时任务」（Agent Task）这个业务域的完整链路：数据模型、harnax-scheduler 独立服务的职责、Quartz 调度引擎、任务生命周期（创建→注册→触发→执行→停止→回收）、跨服务边界与鉴权、以及本轮集群化改造的方案决策与实施计划。
>
> **关于英文版**：`prod_doc` 的约定是中英成对，但本文自始**只有中文**（无 `agent-task-scheduler.en-US.md`）。改本文时不需要同步英文版，也**不要**为此新建半份英文文档；真要建，就得在同一批改动里把本文完整翻过去。
>
> **本轮改造的设计与实施计划已于 2026-09-11 定稿并移至** [docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md](../docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md)（决策清单 D1~D8、里程碑 S0~S4、跨服务契约 C1~C4、测试 IT-1~IT-7、验收标准）。本文此后是**现状链路与方案推理**的参考：改代码前先对齐那份 spec 的里程碑表，本文第 10~11 节保留作工作项清单与被取代决策的备查记录。
>
> 相关文档：会话路由能力契约见 [session-routing.zh-CN.md](./session-routing.zh-CN.md)（部署步骤见 [docs/deploy-harnax-session-router.md](../docs/deploy-harnax-session-router.md)），渠道监听器单实例化（MySQL `GET_LOCK` 范式）见 [docs/channel-to-agent-flow.md](../docs/channel-to-agent-flow.md)，后端分层规范见 [docs/backend-code-conventions.md](../docs/backend-code-conventions.md)，库表规范见 [docs/database-design-conventions.md](../docs/database-design-conventions.md)。

## 1. 结论先行

1. **定时任务是独立服务，不是 admin 的一个功能模块**。`harnax-scheduler`（Kotlin，端口 8084）独占 Quartz 引擎与 `agent_task` / `agent_task_log` / `agent_task_execution` 三张表的调度语义，Flyway 自管（自有历史表 `flyway_schema_history_scheduler`）。**发布 2 起库也独立了**：数据源是本服务自有的 `harnax_scheduler`，11 张 `QRTZ_*` 与三张业务表都在那里，由这个服务的两张脚本（`V1__quartz_tables.sql` + `V2__agent_task_domain.sql`）建出来，这个库里没有第二个迁移工具。历史注：发布 1 时数据源还指 `harnax_admin`，因为"admin 还在写这张表时它只能在库里有一份真相"——那条约束随域搬迁一起消失（spec 的修正 C 与修正 D）。
2. **Quartz 用真正的 JDBC 集群模式**：共享 JobStore + `isClustered=true`，11 张 `QRTZ_*` 表是唯一调度真相。一次 cron 触发在全集群只投递一次、只由一个节点执行。故障接管 **22.5~52.5s**：判据是死节点行的最后 check-in + `CHECKIN_INTERVAL`(15000ms) + Quartz 硬编码的 7500ms = 22.5s，而存活节点只在自己的 ClusterManager 线程醒来时求值这个式子（线程睡的就是一个 checkin 周期），所以再加至多 15s 轮询粒度；对端那一轮被慢库拖过时，`max()` 取到 30s 而不是 15s，判定线再外推 15s → 最坏 52.5s。
3. **"多实例去重"从业务层下沉到引擎层**。改造前靠 `agent_task_execution` 表的唯一键做各节点抢锁（因为每个节点各自触发一次）；改造后 Quartz 自己保证单次投递，抢锁降级为兜底防线，仍然保留。
4. **admin 退化为「校验用户 JWT + 带身份转发」的薄入口**。三个客户端（webui / cli / 小程序）继续打 `/api/admin/agent-tasks/**`，前缀与契约不变；scheduler 的 HTTP 面不直接对浏览器开放。发布 2 起每一发转发带三样东西：一枚 `typ=internal` 的 JWT、`X-Forwarded-User`、`X-Tenant-Id`（契约 C4），而 scheduler 只在接受了那枚 JWT 之后才读那两个头。
5. **CRUD 与它的调度通知现在在同一个进程里**。改造前 admin 改完任务要 HTTP 广播 `/reload` 给所有 scheduler 节点；共享 store 之后广播删成**一次转发**（`urls[0]`）；发布 2 之后写 `agent_task` 与跑那一轮 reconcile 都在 scheduler 内，`/reload` 只剩"外部（admin）也可以手动叫一轮收敛"这一个作用。`/reload` 的语义仍是**跑一轮对账**——写 `agent_task` 与写 store 不在一个事务里，所以通知依旧可能在提交后丢失，丢掉的那次由 60s 的集群清扫兜住（见 6.1）。
6. **对账（reconcile）取代"全删重建"**。共享存储下"把 `AgentTaskGroup` 里的 job 全删再重建"是集群级破坏操作（任一节点重启会瞬时报掉全集群任务）。改成 diff 式收敛，并由一个每 60s 的集群内 job 兜底——这个 job 本身也依赖集群保证"全集群同时只有一个节点在跑"。
7. **发布 2 不动任何数据——一次都不搬**。用户确认本部署没有历史数据，所以评审 D8（迁定义、不迁历史日志）连同它的迁移脚本、幂等守卫、自校验查询一起取消：`harnax_scheduler` 从空开始，**切口后由用户在界面重建任务**。旧库 `harnax_admin` 的三张业务表与 11 张 `QRTZ_*` 在切口后没有任何活着的读者，**直接 DROP，不留观察期**（原计划的"另存 DROP 脚本 + 运维签认"随迁移一起取消；三张业务表那条 DROP 后来以 admin `V39__drop_agent_task_tables.sql` 合入仓库——不合进去，每次全新部署都会被 `V1__init_schema.sql` 重放出这三张 0 行的空表，见 M5 的 5.1 与 spec §9 F16；11 张 `QRTZ_*` 仍由运维就地删）。唯一必须建出来的是 `agent_task_log` 这张表本身，哪怕它空着——`AgentTaskMapper.xml` 的 `selectTaskList` 自联它取 `lastRunStatus`/`lastRunTime`，缺表是列表页 500（见 2.1 那两条跨边界 SQL 的归宿）。
8. **入站保护已经从"只有网络隔离"变成"网络 + 验签"**（发布 2 的 C4）。`harnax.auth.enabled` 仍是 `false`（那套完整自鉴权属第 13 节的 F1），但本服务自己装了一道只认 `typ=internal` JWT 的门禁，覆盖 `/api/scheduler/**` 的**全部**接口——**读也在内**。不发布宿主端口、nginx 不代理 `/api/scheduler/` 于是从"唯一屏障"变成纵深。

## 2. 现状与问题

### 2.1 改造前的样子

`harnax-scheduler` 是从 admin 拆出的独立服务（commit `3ec9bf9`），但拆分只做了一半：

| 层面 | 状态 | 证据 |
|---|---|---|
| 进程与部署 | 已独立 | 独立 jar、独立 `Dockerfile.scheduler`、compose 独立服务 |
| 跨节点执行状态 | 已就绪 | `agent_task_execution` 唯一键抢锁（`AgentTaskExecutionGuard.kt:31-46`）、`agent_task_log` 的 3→4→5 状态机使任意节点可受理停止（`SchedulerServiceImpl.kt:386-410`）、`expireStale` 回收僵尸执行（`AgentTaskLogMapper.xml:116-127`） |
| 调度引擎 | **改造前的快照** | 当时 `application.yml` 默认 `job-store-type: memory`；当时全仓库没有任何 `QRTZ_*` 建表脚本；无 `isClustered`/`clusterCheckinInterval`/`jobStore` 配置。现状见 §5 |
| 数据归属 | ~~错位~~ → **发布 2 已解** | 改造前 `agent_task` 的 CRUD 在 admin、调度在 scheduler，两者共用 `harnax_admin` 库。域搬迁把 CRUD、实体、mapper 与数据源一起搬进 `harnax-scheduler`（自有库 `harnax_scheduler`），这条错位随之消失 |

引擎未改造带来的直接后果是：所谓"多实例"实际是**每个节点各自 fire 一次，再由 DB 唯一键丢弃重复**。这条路拿不到 Quartz 的三样东西——misfire 补偿（节点停机期间错过的触发会被永久跳过）、故障接管（节点死了它内存里的任务一起没了）、以及负载均衡（谁抢到锁谁跑，但抢锁失败的那次 fire 是纯浪费）。

数据归属错位带来的是两处跨表耦合：

```
admin 列表页   AgentTaskMapper.xml:62-86   SELECT t.*, latest.status AS last_run_status ...
                                           LEFT JOIN agent_task_log ...        ← 业务表 JOIN 引擎产物
scheduler 回收  AgentTaskLogMapper.xml:116-127  UPDATE agent_task_log l
                                               JOIN agent_task t ON t.id=l.task_id  ← 引擎写回读业务表
```

两条 SQL 各自跨了两个服务的职责边界，改造前靠"共库"这个前提勉强成立。发布 2 把库分开之后它们**没有全废**——因为两张表连同这两条语句一起搬进了同一个服务（`harnax-scheduler/src/main/resources/mapper/`），跨边界于是就地消解。这正是第 10.3 节淘汰 C2（业务表留 admin、日志/抢锁表搬 scheduler）的直接原因：那条路要把这两条 JOIN 改造成跨服务调用。

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
        │  harnax_scheduler 库（Flyway 独占）    │        （携带 task-{id}-{agentId}-{uuid} 会话，契约 C1）
        │  agent_task / agent_task_log /       │
        │  agent_task_execution / QRTZ_* ×11   │
        └─────────────────────────────────────┘
```

### 3.1 职责划分

| 服务 | 负责 | 不再负责 |
|---|---|---|
| **harnax-admin** | 校验用户 JWT、解析 `tenantId`/`username`、以 internal token + `X-Forwarded-User`/`X-Tenant-Id` 转发到 scheduler（C4）、`agent-spec` 装配（**解析 `task-{taskId}-{agentId}-{uuid}` 直接拿到 agentId**，纯字符串、不查库也不问人，见 6.3）、冷路径上调 C5 owner 端点取任务属主 | 本域三张表的任何 SQL、Quartz、CRUD 业务规则、reload 广播 |
| **harnax-scheduler** | 任务定义存储、CRUD 校验与属主规则、Quartz 集群调度、执行与状态机、执行日志、僵尸回收、对账收敛、guard 清理、入向的 internal-JWT 门禁 | 读别人的库（改造后 scheduler 只有一个数据源，就是 `harnax_scheduler`） |

**一件没做到的事要写在脸上**：admin 装配 spec 时用的 agentId 就是从 sessionId 字符串里读出来的那一个，**没有任何东西核对它与任务行真用的那个一致**——域搬走之后 admin 读不到 `agent_task`，这次比对（spec C1 原定的"F3 第三刀 C"）不可执行。补偿是两件：生成侧同源（两个段出自同一次读）+ 解析侧严格四段。残留登记为 spec §9 **F15**，正解是 F3-A 的归属扩展。

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

字段沿用 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:480-536` 的现状。**发布 2 真的搬过来了，但按"逐列照抄、不改类型/索引/列序"搬**：现在的真相源是 `harnax-scheduler/src/main/resources/db/migration/V2__agent_task_domain.sql`，它与 admin 的三段建表逐列同形，只做了四处改动——`agent_task_log.session_id` 因 C1 从 `VARCHAR(64)` 变 `VARCHAR(128)`、发布 1 补的两条 `agent_task_execution` 清扫索引（`(status, create_time)`、`(create_time)`）直接内联进建表而不再另起 V3、缺 COMMENT 的列补齐、文件头。**原计划里"按库表规范补齐逻辑删除唯一键"这一项没有做**：`uk_name (name)` 仍是那条不含 `active` 的全局唯一键，而 `deleteById` 只置 `active = 0`——于是"已删任务的名字永久不可复用、且撞键报的是 500"这条缺陷跟着搬家原样存活，登记为 spec §9 **F14**。`idx_tenant_id` 本来就在，不是新增。

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

下面是发布 1 **已落地**的形态，与 `harnax-scheduler/src/main/resources/application.yml` 逐项对照（该文件是真相源，这里只给形状）：

```yaml
spring:
  quartz:
    job-store-type: ${QUARTZ_JOB_STORE:jdbc}
    # 关掉调度的实例根本不要进集群：Boot 默认会启动 scheduler，那样的节点会 checkin、会 acquire
    # trigger，然后拒执行——fire 被吃掉而不是交出去
    auto-startup: ${SCHEDULER_QUARTZ_AUTO_STARTUP:${scheduler.enabled:true}}
    jdbc:
      initialize-schema: never
    wait-for-jobs-to-complete-on-shutdown: ${QUARTZ_WAIT_FOR_JOBS:true}
    properties:
      org:
        quartz:
          scheduler:
            instanceName: HarnaxScheduler
            instanceId: AUTO
          threadPool:
            class: org.quartz.simpl.SimpleThreadPool
            threadCount: ${QUARTZ_THREAD_COUNT:10}
            threadPriority: 5
          jobStore:
            isClustered: "true"
            clusterCheckinInterval: 15000
            misfireThreshold: 60000
            acquireTriggersWithinLock: "true"
            tablePrefix: QRTZ_
            useProperties: "true"
```

逐项理由与几个容易踩的实现事实：

- **不写 `org.quartz.jobStore.class`**。已核对 spring-context-support 7.0.2 的 `SchedulerFactoryBean`：一旦注入了 DataSource，它在 prepare 阶段**强制**把 `jobStore.class` 覆盖成 Spring 的 `LocalDataSourceJobStore`（`org.quartz.jobStore.class` 常量与该类名都在该 class 的常量池里）。手写 `JobStoreTX` 不但会被覆盖，还会因为 Quartz 自己不认识 `quartzDataSource` 这个逻辑名而启动失败。
- **不需要 `@QuartzDataSource`**。Boot 4.0.1 的 `QuartzAutoConfiguration`（已拆到独立的 `spring-boot-quartz` 模块）在 `job-store-type: jdbc` 时自动接入唯一的 DataSource。scheduler 改造后确实只有一个数据源。第 10 节的 C1 方案（业务表与引擎表分库）才需要那个注解。
- **`initialize-schema` 默认值是 `embedded`**，MySQL 下本就不会建表；这里显式写 `never` 是把"schema 归 Flyway 独占"固化成约定，防止有人改成 `always` 导致 Boot 与 Flyway 抢建表。
- **`instanceName` 全集群必须一致**。它是 `QRTZ_*` 表的 `SCHED_NAME` 列，集群成员靠它相认；反过来，同一套库里放两个不同 `instanceName` 的部署是可以的（互不干扰），但一环境一库更清晰。
- **`instanceId: AUTO` 在容器化下可用**（由 hostname + 时间戳 + 线程生成，容器 hostname 天然唯一）。前提是 compose 里不能有 `container_name: harnax-scheduler`——固定容器名唯一，Docker 会直接拒绝 `--scale`，也可能造成 hostname 重复从而 instanceId 撞车。**发布 1 已删掉该键**（`docker-new/docker-compose.yml` 的 scheduler 段注释里写着这条理由，以及为什么访问它必须走 `docker-compose … exec scheduler` 而不是 `docker exec harnax-scheduler`）。
- **时钟要求**。集群靠比对 `QRTZ_SCHEDULER_STATE.LAST_CHECKIN_TIME` 判断节点死活，节点间时钟偏移超过 checkin 间隔会误判死亡并触发误抢。所有节点必须 NTP 同步。另外 cron 表达式在 JVM 默认时区解释，各节点 TZ 要一致（compose 已统一挂载 `/etc/localtime`）。
- **`threadCount` 保持 10，没有提到 25**：spec 3.4 明确否决了放大线程数——下游 agent-service 仍是单实例，线程数买到的只是"同时打更多下游"。2 实例 × 10 = 全集群最多 20 个并发执行，这是每节点闸口而不是集群闸口。相应地 Hikari `maximum-pool-size` 默认 30，下界是这么来的：10 个 Quartz worker 各占一条连接（job 在 worker 线程内同步跑，占用时长就是一次执行）+ 业务查询 + 集群 checkin，全走同一个池。`QUARTZ_THREAD_COUNT` 与 `DB_POOL_SIZE` 要一起动：只加线程不加池不会多出容量，只是把等待从调度线程挪到 30s 的 connection-timeout 上。
- **`useProperties: true`**：JobDataMap 以文本 kv 存进 `QRTZ_JOB_DETAILS`，配合 7.2 的"只放 taskId"，引擎表里不再有任何 Java 序列化 BLOB，实体字段变更不会让存量任务反序列化失败。注意此时 **taskId 必须放成 String**（Long 会被拒）。
- **`waitForJobsToCompleteOnShutdown: true`** 会让停机最多等一个任务超时，需要与编排的 stop grace period 一起调，否则会被 kill 窗口截断。**已落地**：它就是 Boot 4.0.1 `spring-boot-quartz` 的标准属性 `spring.quartz.wait-for-jobs-to-complete-on-shutdown`（该版本 configuration metadata 里 `defaultValue=false`，所以必须显式写），**不需要** `SchedulerFactoryBeanCustomizer`；`application.yml` 写 `${QUARTZ_WAIT_FOR_JOBS:true}`，compose 侧配 `stop_grace_period: 400s`（逐项相加 = chat 读超时 300 + clearSession 60 + 两次调用各 10s 的 connect 预扣 20 + 定态写回/释放锁 8 + Spring 关停钩子 4 = 392，向上取整；算式在两处注释里；`clearSession` 自第三批起有自己的读超时上限 `scheduler.clear-session-timeout-seconds=60`，不再共用 chat 的 300s，否则一次执行最坏占用是 600s）。**保护范围 = cron 与手动执行两条路径**：发布 3 之后 `/trigger` 与 `/run-once` 都只往共享 store 投一枚 one-shot job，两者跑在同一批 Quartz worker 上，所以这个等待与 400s 对两条都成立，"手动执行进行中不要重启 scheduler"这条例外已经取消。**代价是容量，同一件事的另一面**：一次手动执行占用 `QUARTZ_THREAD_COUNT`（默认 10，见上一条）个 worker 之一直到跑完，`SimpleThreadPool` **没有队列**，worker 被占满时到期的 cron 只能干等；等到越过 `misfireThreshold: 60000` 就成一次 misfire，而 `concurrent=0` 用的正是 `withMisfireHandlingInstructionDoNothing`——**那一发定时任务被跳过，不是延后跑**。由此得到一条下限规则：**不要用 1~2 个 worker 跑 scheduler**（运维正文见 `docs/deploy-harnax-scheduler.md` 的「优雅停机」）。

## 6. 任务生命周期全链路

### 6.1 创建 / 更新 / 删除

```
用户在 webui 新建任务
  → POST /api/admin/agent-tasks       （admin：`JwtAuthenticationFilter` 校验用户 JWT → 取出 username/tenantId
                                        → 转发，本身不碰任何表）
  → POST /api/scheduler/agent-tasks   （scheduler：C4 门禁验 internal JWT → 属主规则与 cron 校验 →
                                        写自己的 harnax_scheduler.agent_task → 事务提交后跑一轮 reconcile）
  → reconcile：diff 落进所有节点共读的 QRTZ_* store
```

admin 侧的 12 个端点、请求/响应形状与业务码对三客户端一字未改（见 3.1）；改的是"谁在做决定"——校验、属主规则、写库与通知调度现在都在 scheduler 内，`AgentTaskCrudServiceImpl` 就是原来 admin `AgentTaskServiceImpl` 的那套规则搬过来的（逐字保留，没有顺手收紧也没有放宽）。

`/reload` 的语义已经改了：它不再"把本实例的任务表重新加载一遍"，而是**跑一轮对账**（`SchedulerServiceImpl.reconcileTasks()` → `TaskScheduleReconciler.reconcile()`）；`ReconcileReport.converged` 为真才回成功；一轮没收干净就是非 200，并且答案里直接把人指向 `/actuator/health`（没收干净的明细在 `lastReconcileError` 里）。因为它改的是共享 store，所以**一台收敛 = 全集群收敛**，admin 的 `SchedulerClientImpl` 因此把原来的逐节点广播塌缩成**一次转发**——广播在没有共享 store 时是必需的，在此之后只是 N 台节点对同一份 diff 各起一次写。

`afterCommit` 这条规则跟着域一起搬了家，理由一字未变：写 `agent_task` 与写 store 不在一个事务里，通知若在事务内发出去，对账读到的是提交前的行，于是把旧定义注册回去（删除场景更糟——它会把 UI 已经认为消失的任务重新注册）。挂在提交后，回滚的事务也就不会通知任何人。

这一发仍可能丢（进程间没有分布式事务），失败的后果是"定义已存库、没有任何 scheduler 调度它"，出码 **40902 `CODE_SCHEDULER_SYNC_FAILED`——发布 2 起这个码由 scheduler 自己出（`AgentTaskCrudServiceImpl.kt:290`），admin 只把它原样透传**，而不是回滚——前者只需要重试调度、后者要重做一次保存。**边界要说清**：40902 只保证"这一发没成"，不保证任务永远错着——60 秒的集群清扫是这一条的最后兜底。另外，落到 `SCHEDULER_ENABLED=false` 的实例上时该实例答 40903，而 reload 路径会把它改判成 40902 回给调用方（只有 40903 的文案留在 message 里）；start/pause/trigger/stop 是原样透传 40903。

### 6.2 注册

```
AgentTaskJob 类（concurrent=0 时用 AgentTaskNonConcurrentJob）
JobKey     = AgentTask_{id}   / group AgentTaskGroup
TriggerKey = AgentTask_{id}_trigger
JobDataMap = { "taskId": "123" }        ← 只有 ID，不放实体
misfire    = concurrent==0 ? DoNothing : FireAndProceed
```

`concurrent` 的语义今天只被翻译成 misfire 指令（`TaskQuartzRegistrar.register`），**拦不住重叠执行**——misfire 只在触发被错过时生效，前一次还在跑时新触发照样 fire。真正的开关是 `@DisallowConcurrentExecution`，而它是**类级注解**，无法按 job 实例切换，所以拆两个 job 类、由 `TaskQuartzRegistrar.jobClassFor(task)` 选（7.2）。

**同一张图的手动那一发**（发布 3 之后才有）：`/tasks/{id}/trigger` 与 `/tasks/{id}/run-once` 都不再起线程，而是投 `JobKey = AgentTask_{id}_ONCE_{uuid8}` / group `AgentTaskGroup_ONCE`、trigger 同名加 `_trigger`、`startNow()`、**不带 `storeDurably()`**（fire 完即被 Quartz 清掉，不留 reconcile 读不懂的行），JobDataMap 与 job 类的选择与上面完全同源。它落在 `AgentTaskGroup_ONCE` 而不是 `AgentTaskGroup`，是因为 reconcile 删的是"表里不再要求的 job"——一次刚点下去、还在等 fire 的点击不是谁的过期 schedule。副作用要清楚：这一组因此**不在 reconcile 的视野里**，也不在 `scheduledJobCount` 的计数里（两者都只读 `AgentTaskGroup`）。

### 6.3 触发与执行

```
QRTZ_TRIGGERS.NEXT_FIRE_TIME 到期（cron 与手动 one-shot 是同一类对象）
  → 某个节点在 TRIGGER_ACCESS 锁内把该行 ACQUIRED（全集群只有一个节点成功）
  → 该节点 Quartz 线程执行 job，AbstractAgentTaskJob.run() 依次：
      ├─ 读 JobDataMap 的 taskId（拿不到就不是我们的 job，直接拒）
      ├─ 本机 schedulingEnabled？否则让给别的节点（共享 store 会把 job 分给没注册它的节点）
      ├─ 按 taskId 回查 agent_task：行没了就把 store 里的孤儿 job 删掉
      ├─ 守卫：active != 1 一律拒；taskStatus != 1 只对 cron 拒（one-shot 是用户几秒前刚点的意图，暂停中照跑）
      ├─ 闸口一：concurrent=0 且本任务已有活执行 → 跳过这一发
      ├─ 闸口二：guard.tryAcquireLock(taskId, scheduledFireTime) 抢不到 → 别的实例在跑
      ├─ 插 agent_task_log(status=3, session_id=task-{id}-{agentId}-{uuid})   ← 契约 C1
      ├─ POST router /api/router/agent/chat  →  session-router → agent-service
      └─ 结束：finishExecution CAS 定态 1/0；被请求停止则 4→5；清 session
```

**注意上面那两道闸口的顺序**：它们都在插日志行**之前**，所以一次被挡掉的 fire 在 `agent_task_log` 里不留任何痕迹。对手动执行这就够成一次用户可见的意外：`/trigger` 拿到 200（one-shot 已成功进 store），fire 时撞上闸口被丢，webui"执行成功 → 打开日志列表"于是可以是空列表。唯一的线索是 scheduler 日志里的 `skipping this fire` / `already being executed by another instance`。投递时机还有第三道，与这两道不同：`blocksManualRun`（`concurrent == 0` 且已有活执行）在 `runTaskOnce` 里就拒，返回 `40901`，那一次用户是明确看到冲突提示的。

`agent-spec` 反查链已经按契约 C1 改完：agent-service 拿 `task-{taskId}-{agentId}-{uuid}` 回问 `GET /api/admin/internal/agent-spec/{sessionId}`，admin 的 `resolveFromTask()`（`InternalApiController.kt:369-373`）用**与生成侧同一份** `com.agnetix.harnax.common.session.TaskSessionId.parse` 解出两个 id，然后**直接用其中的 agentId** 装配 spec——它不再对 `agent_task` 发任何查询（域搬走之后也发不出来），原先计划的 `GET /api/scheduler/agent-tasks/{id}/agent-id` 端点依旧作废（第 11 节 3.2）。段数与两段的数字都被严格核对，三段形态直接拒并给出期望格式，所以"新旧混跑"在这里是显式失败而不是静默错读。**代价也要写清**：这次解析没有任何东西核对"这个 agentId 就是那次任务真用的那个"，spec 原定的第三刀 C 因此不可执行，残留登记为 spec §9 F15（见 3.1 末段）。下游 agent→model 装配完全不变。

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

正因为状态在 DB 行上而不是内存里，受理停止的那台不需要是执行的那台：`markStopping` 与 `finalizeStopped` 都是对同一行的带条件 UPDATE，谁拿着都算数。所以 admin 的转发不必知道"这个任务在哪个节点上跑"——**但它是发给一个具体地址的**（`HARNAX_SCHEDULER_URL` 逗号分隔列表的第一个，落到哪个副本由 Docker 对 `scheduler` 这个 service 名的 DNS 轮询决定）。它反过来也成立：**受理停止的节点不是执行节点时，它写 5 的依据只有那个"未命中"答复**，所以同一次停止被广播到两台时，第二台会答"我这儿没有在途执行"、当场把行 4→5，把真实结果从执行线程手里抢走。发布 1 把 `SchedulerClientImpl` 塌缩成**一次转发**之后这条边不再存在（原先的多实例折叠因此不是待修的债，而是被删掉的功能），且 `/tasks/logs/{id}/stop` 刻意不受 `scheduler.enabled` 门禁——它不写任何 Quartz 对象，任何副本都可以受理。

### 6.5 回收

`expireStale` 把超过 `timeout_seconds × 1.5` 仍未定态的行（3 或 4）判为 `2 timeout`，调用点是启动加载、housekeeping 每 5 分钟一轮、以及 fire 前的并发判断——最后这一处自 G7 起被限流成**每节点每 30s 至多一次**（它是扫 `idx_status` 活跃端的 UPDATE，和同一时刻插入新行的抢锁在 MySQL 上互撞，输的那方丢的是真执行）。它是节点被 kill 之后不留永久"运行中"僵尸的最后防线。**发布 3 之后这条防线不再分两种执行路径**：手动执行也是一次 Quartz fire，被 SIGKILL 截断时留下的同样是 `agent_task_log` 的 `status=3` 行 + `agent_task_execution` 的 `status=0` 锁行，回收按同一套判据把它们定成 `2`。差别只在停机那一段——以前那条线程不归 Quartz 管，宽限等不到它，现在 `/trigger` 与 `/run-once` 都在 worker 上跑，400s 之内根本走不到回收这一步（见第 5 节与 7.3）。

store 换成共享之后，housekeeping 那一轮的含义变了：清扫 job 是 store 里的一行，因此**全集群每 5 分钟只有一个节点 fire 它**，不再是每台各扫一遍（内存 store 下"每个节点都扫"这件事以前是隐形的冗余，现在它换成了一条集群级保证）。对运维的实际意思是：**只要集群里还有一台开着，回收就还在跑**；一台 `SCHEDULER_ENABLED=false` 的节点没有、也不需要私有的回收路径——它自己 `stopTask` 留在 4 的那一行，会由 fire 到共享清扫 job 的那台收走。单节点且把开关关掉是唯一没人回收的情形（见部署文档的同名 service 约束）。

"最后"是关键字：`2` 不是不可逆的。执行线程事后带着真实结果回来时走 `reclaimExpired`，把这一行覆盖成 `0/1`（并在 `error_info` 留"完成于自动超时之后"的标记），或在自己读到过 4 的情况下覆盖成 `5`（见 4.1 状态图的 `2 → {0,1,5}` 三条边）。回收扫描因此只能是猜测的兜底，不能当定论的出口。

## 7. 三处引擎层逻辑改动

### 7.1 对账取代"全删重建"

**改造前**：`loadTasksToScheduler()` 的第一步是把 `AgentTaskGroup` 里所有 job 删光，再按 DB 重建。RAMJobStore 下这是幂等的正确做法；共享 JobStore 下它是**集群级破坏操作**：任一节点重启或收到一次 reload，都会瞬时报掉全集群的定时任务，两个节点同时 reload 还会互相抢删并撞 `ObjectAlreadyExistsException`。

**已落地**为 `TaskScheduleReconciler`（diff）+ `TaskQuartzRegistrar`（单个任务进/出 store 的唯一写入口）+ `SchedulerReconcileJob`（60s 清扫），`SchedulerServiceImpl` 的 `scheduleTask`/`unscheduleTask` 也委托给 registrar：

```
期望态 = agent_task 中 task_status=1 AND active=1 的 { taskId → (cron, concurrent) }
实际态 = QRTZ store 中 AgentTaskGroup 的 { jobId  → (cron, job 类) }

  期望有、实际无   → 注册
  实际有、期望无   → 删除
  两边都有但 cron/job 类不一致 → reschedule
  两边一致                      → 不动（保留 PREV_FIRE_TIME，不重置执行历史）
```

cron 的比较是**忽略大小写**的：Quartz 会把它大写归一化，存量行读回来就是 `0 0 9 ? * MON`，逐字比较会把每一条带字母的 cron 都判成"变了"，于是每一轮都 `scheduleJob(replace=true)` 重写整个组——正是这个类要消灭的行为，而且它还会喂脏漂移指标。

三处复用同一个函数：启动、admin 转发 CRUD 后（一次 `/reload` = 一轮）、每 60s 集群内 job。两个节点同时跑之所以安全，靠的是三件事而不是运气：注册走 `scheduleJob(replace=true)`（幂等交换，不会撞 `ObjectAlreadyExistsException`）、清扫类带 `@DisallowConcurrentExecution`（一轮慢过 60s 不会叠第二轮）、以及**先读 store 再读表**这个刻意顺序——一次 CRUD 若正好落在两次读之间，它会出现在表里而不在快照里，本轮于是重注册它（一次良性写），反过来则会误删集群刚要的那条 schedule 并带走落在窗口里的 cron 边界。

对账 job 自己就存在共享 store 里，因此**全集群每 60s 只有一个节点执行**——这是本轮集群化最直观的一个收益演示。它同时暴露了一个以前看不见的事实：新增 `scheduler.reconcile.drift{action=add|remove|update}` 计数器，CRUD 与 store 一旦漂移就会先在指标上显形，而不是等用户投诉任务没跑。**读法**：每一轮只有一台发布样本（清扫是集群单例；admin 的一次转发也只落到一台），所以按 `instance` 求和得到的是**集群总量**而不是它的倍数——真正会骗人的读法是把**单台**的序列当集群读数：一次 `/reload` 落到哪个副本由调用方那边决定，没落到的那台不代表集群没修过东西。告警设在"任一实例非零"上。

### 7.2 JobDataMap 只存 taskId —— 已落地

改造前 `scheduleTask` 把整个 `AgentTask` 对象塞进 JobDataMap。RAMJobStore 下无所谓，JDBC store 下它会被 Java 序列化进 BLOB：实体字段一变（这个仓库改实体很频繁），存量任务在重启后直接反序列化失败；而且改了 prompt/cron 不重新注册就不生效。现在注册的唯一入口 `TaskQuartzRegistrar` 只放 `taskId` 字符串（`useProperties: true` 下非字符串是硬错误），`AbstractAgentTaskJob` 在 fire 时回查 `agent_task` 拿当前 prompt/cron/status——顺带让"任务定义的唯一真相"落回业务表。

一个由此改变的运维结论：**被删掉的任务不会"还能火"**。回查拿不到行时，这一发直接把 store 里那个孤儿 job 删掉（`deleteJob` 从正在跑的 job 内部调用是安全的：`JobStoreSupport.triggeredJobComplete` 不会把它再写回去），下一轮 reconcile 做的是同一件事。所以"删除已生效但 store 里还留着旧 job"这个窗口，最迟在一次到点触发后就自己关掉了。

### 7.3 手动执行走 Quartz one-shot 投递 —— 已落地（发布 3）

**改造前**是两套并行逻辑：`triggerManually()` 从库里读任务、检查并发、抢锁，然后起一个线程直接跑；`runTaskOnce()` 走 Quartz one-shot 投递（group `AgentTaskGroup_ONCE`）。去重逻辑写了两遍，而 admin 用的是前者——也就是绕过 Quartz 的那一套。那条线程当时带来的三个问题现在都不成立：节点挂了手动执行跟着不可用；进程在 fire 前崩掉这次执行静默丢失；Quartz 以为 job 秒回，于是 `@DisallowConcurrentExecution`、故障接管与 D6 的停机宽限对这条路径全部无效。

**现在的样子**：`triggerManually` 从接口与实现里删除，`/tasks/{id}/trigger`（admin 与 CLI 在用）与 `/tasks/{id}/run-once` 都只调同一个 `SchedulerServiceImpl.runTaskOnce`，它往共享 store 投一枚 `startNow()` 的 one-shot（非 durable、JobDataMap 只放 taskId），集群里任意节点 fire，进程崩在 fire 之前由 Quartz 补火而不是消失。`SchedulerConfig` 里那个从没被这条路用过的 `taskExecutor()` 线程池 bean 一并删除。去重只剩三处、各司其职：投递时的 `blocksManualRun`（= `concurrent == 0 && hasActiveRunningExecution`，返回 `40901`）、fire 时同一个按 task 键的读（`@DisallowConcurrentExecution` 是按 JobDetail 互斥的，而一个任务现在握着 cron + 每次点击多个 JobDetail，跨不过去）、以及 `guard.tryAcquireLock` 作为集群兜底。**暂停中的任务点立即执行会跑**：`taskStatus` 守卫只管 store 里那份滞后的 cron 注册，一次点击的注册本身就是当前意图（软删除仍然两边都拒）——详见 6.3。

两条新出现的后果，都属于"并入 Quartz"的价格：一次手动执行占用一个 Quartz worker（容量规则见第 5 节），以及一次 200 不再保证留下一行执行记录（两道 fire 时闸口在插行之前，见 6.3）。

## 8. 跨服务边界与鉴权

### 8.1 admin → scheduler 的转发契约

| 项 | 做法 |
|---|---|
| 出站签名 | 复用现成的 `InternalTokenProvider` + `AuthRestTemplateInterceptor`（`SchedulerClientImpl.kt`），签 `typ=internal` HMAC JWT。**发布 2 起对面真的验了**：`InternalCallerInterceptor` 只放行验签通过且 `callerType == INTERNAL_SERVICE` 的请求，其余一律 401 |
| 地址 | `harnax.scheduler.url` **只取逗号分隔列表的第一个**（其余仍解析、不报错，但没有任何代码路径会用它们）——`SchedulerClientImpl` 的广播在共享 store 之后删成了"一次调用、一台实例"。落在那台都等价，因为改的是所有节点共读的 store；**但落到的那台必须是开着调度的**：一台 `SCHEDULER_ENABLED=false` 的副本若仍注册在同一个 service 名下，这一发会按 DNS 的运气落到它身上并回 40903（reload 路径会把它改判成 40902），用户看到的就是"任务保存了但没生效"。所以禁用实例要么摘掉，要么换 service 名（约束的正文在 `docs/deploy-harnax-scheduler.md`） |
| 身份透传 | ✅ **已落地（发布 2 的 C4）**。admin 的每一发转发在 bearer 之外再盖两个头：`X-Forwarded-User`（admin 自己从用户 JWT 解出的 username）与 `X-Tenant-Id`（`TenantContext`）。scheduler 侧的顺序是**先验签、后读头**：`InternalCallerInterceptor` 放行之后才把两者放进 `CallerContext`（ThreadLocal，`postHandle`/`afterCompletion` 清），本域的属主与可见性规则读的就是它。`X-Forwarded-Tenant` **一律不读**——那是浏览器自己能发的头。**运维后果**：门禁一上，旧版 admin 的转发全部 401，所以 admin 与 scheduler 必须同窗口升级，且两边 `HARNAX_AUTH_SECRET` 同值（compose 用一个变量喂两个服务，手工部署是唯一能配歪的地方） |
| 幂等 | **中间态确实存在，只是有人兜底**：`agent_task` 的写与 store 的收敛不是一个事务，提交后那一轮 reconcile 若失败或没跑到，调用方拿到 40902「已存库但没被调度」，而 store 与 `agent_task` 就处于这个差里，直到 60s 的集群清扫把它收敛掉。发布 2 之后这个差整个住在 scheduler 进程内（CRUD 不再跨服务），但它没有消失——进程之间本来就没有分布式事务。reconcile 是 diff 且幂等，所以重试一次 `/reload`、或等清扫，结果一样 |

### 8.2 scheduler 的入站保护（本轮范围与偏离说明）

用户明确要求本轮**不改鉴权代码**：`harnax.auth.enabled` 保持 false，不引入 `@InternalOnly`/`UnifiedAuthFilter`。

> **一处有意的偏离**（实施时按批准的方案执行，现已落地）：把 scheduler 的写面从「trigger/start/pause」扩大到「全量 CRUD」之后，裸放在 docker 内网的全量写接口不再可接受，于是加一个只校验 admin 签的 `typ=internal` JWT 的拦截器（用已存在的 `harnax.auth.internal.shared-secret`，**不**接入 harnax-auth 的自动装配）。

**改造前的暴露面（快照，非现状）**：`AuthAutoConfiguration.kt` 的 `unifiedAuthFilter` bean 带 `@ConditionalOnProperty(harnax.auth.enabled, havingValue="true", matchIfMissing=true)`，scheduler 显式写了 `false` → **这个 filter 根本没被创建**，不是"创建了但放行"——这条**今天仍然成立**，它就是本服务另装一道门禁的原因。当时同时成立、此后逐条关掉的还有四条：`SchedulerClientImpl.kt:35` 那句"The scheduler runs UnifiedAuthFilter"与事实相反（**S0 已改成如实描述**）、`docker-compose.yml` 把 28084 发布到宿主机、`nginx.conf` 有一个无访问控制的 `location /api/scheduler/`（**这两条由第二轮 R1 关闭**：改 `expose: ["8084"]`、location 整段删除并在原位留了禁止回加的注释）、以及 `/api/scheduler/**` 谁都能调（**发布 2 的 C4 关闭**）。

> **发布 2 之后仍然开放的**：`/actuator/prometheus` 与 `/swagger-ui.html` 的匿名可达（属 F2，本轮只在部署层把端口与 nginx 收掉）；`harnax.auth.enabled=true` 的完整自鉴权（F1）。另外记一条**对 spec §2.3 的有意偏离**：那一节写的是"覆盖全部**写面**"，而这句话出自 scheduler 只有写面的时候。发布 2 新增了 C5 那个读端点（凭一个任务 id 就能读出创建人与租户），所以拦截器覆盖的是 `/api/scheduler/**` 的**全部**接口，读也在内。理由与写面逐字相同，就不该留豁免；运维侧的正文在 `docs/deploy-harnax-scheduler.md` 的「认证边界」。

另外要清楚：`UnifiedAuthFilter` 即便打开也**验不了用户的登录 JWT**——它用 `harnax.auth.internal.shared-secret` 验签，而 admin 签用户 token 用的是另一个 key `jwt.secret`（`InternalTokenProvider.kt:59-66` 的注释明确区分了两者）。仓库内唯一的服务自鉴终端用户范式是 session-router 的 `X-Api-Key` + `RemoteApiKeyStore`（回源 admin 校验 + Caffeine 缓存）+ `SessionAccessGuard` 的手写租户判断。走那条路要给 scheduler 复刻一遍，并让三个客户端改发送的凭证——正是第 10 节 B 方案要避开的成本。

## 9. 可观测性

| 信号 | 含义 | 位置 |
|---|---|---|
| `/actuator/health` 的 `scheduler` 指示器 | 本进程是否真的在调度。detail：`quartzStarted`、`instanceId`（集群下的真实实例名）、`storeType`、`scheduledJobCount`（**当场读 store**，不是启动时的数）、`lastReconcileAt`、`lastReconcileError`。判据只有三条：一轮对账干净收过 → UP；从未收过 / 最近一轮留了漂移或抛了 → DOWN；`scheduler.enabled=false` → UP 并只带 `enabled=false`。`storeType` 与 `scheduledJobCount` 是 detail，**不参与判据** | `SchedulerHealthIndicator.kt` |
| `/actuator/health/liveness` | 只回答"进程活着吗"，**故意不含**上面的指示器——数据库慢不该让容器重启掉自己的重试循环。compose 的健康检查与滚动脚本看的就是它 | 设计说明在 `SchedulerHealthIndicator` 的类注释 |
| `scheduler.reconcile.rounds{outcome=success\|failure}` | **一轮对账的判决**，跑它的那台发布一个样本。三个来客都算：启动的收敛循环、admin 转发的 `/reload`、60s 集群清扫。它不是重启计数器——原名 `scheduler.load.attempts` 在只有启动加载一个调用方时是诚实的，清扫上线后它变成"每分钟还要再来一个样本（由跑那一轮的那台发布）"，于是名字本身成了 bug（看板若按"启动失败次数"读它会一路漂） | `SchedulerMetrics.recordReconcileRound` |
| `scheduler.reconcile.drift{action=add\|remove\|update}` | 本轮修掉的漂移量，按方向分桶；没动的桶不产生样本（健康集群每分钟那一轮什么都不发）。这是"CRUD 与 store 脱节"最早的显形点。读法见 7.1：**一轮只有一台发布**，跨 `instance` 求和是集群总量，单台序列不能当集群读数 | `SchedulerMetrics.recordReconcileDrift` |
| `scheduler.jobs.scheduled` | store 里 `AgentTaskGroup` 的 job 数，直接读 `QuartzJobInventory`。store 换成共享之后这个表达式**已经是集群视图**（两台的取值相同，不再各报各的），所以按旧的"本实例视图"含义设的阈值要重读。store 读不出来时它是 NaN，而不是一个像样的 0 | `SchedulerMetrics` + `QuartzJobInventory` |
| `QRTZ_SCHEDULER_STATE` | 运维直查：有几行、各自的 `LAST_CHECKIN_TIME` 有没有在推进，是判断"第二台到底进没进群"最快的办法。**别拿行数当副本数**：一个节点从不删自己那行（优雅停机也不删），行是**对端**在 `calcFailedIfAfter` 判它过期后、于 `clusterRecover` 里顺带删的——按 15s check-in 算是那行最后一次心跳之后 15000+7500ms=22.5s，再加上对端最多一个 checkin 周期的求值粒度。刚滚完 2 副本时看到 3~4 行是正常答案 | `SELECT INSTANCE_NAME, LAST_CHECKIN_TIME, CHECKIN_INTERVAL FROM harnax_scheduler.QRTZ_SCHEDULER_STATE;`（发布 2 起这张表在 scheduler 自有的库里，`roll-scheduler.sh` 结尾的回读用的就是这条） |

**router 侧调用日志（`api_call_log`）里定时执行长什么样**：`GET /api/router/monitor/call-logs` 自发布 3 起按调用方租户收口——**带租户的调用方只看自己那些行**，谓词由服务端从凭证推出来（该端点从来不接受 `tenantId` 参数，也不该开始接受）。而本服务调 router 用的是自己的 SYSTEM key（`SCHEDULER_API_KEY` 留空时向 admin 现申请一把），这类凭证没有租户，`ApiCallLogFilter` 于是没有租户可盖章——**一次定时执行写下的行是 `tenant_id IS NULL` 的行**。两条规则合起来的实际后果值得写下来，因为它看起来像 bug：**租户用户在自己的 monitor 页面里永远看不到自己任务的调用记录**，那些行只在无租户的内部/运维调用方视图里。这不是漏，是"无法归属的行不能变成所有人可见"的另一面；排查一次定时执行为什么失败，走的仍然是 `agent_task_log`（那里有 prompt、response、error_info 与耗时），不是 router 的调用日志。

同 controller 的 `GET /api/router/monitor/instances` **刻意没有做同样的收口**：它答的是集群拓扑（host、port、心跳年龄、该实例持有几个会话），不属于任何租户，是运维视图；它停在一个实例"是什么"，不给谁的会话、更不给任何一次会话的内容。这是决定，不是漏做——把它按 call-logs 的规则收窄，等于关掉运维面板本身。

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

> **本节的结论已被两次改动，最终形态是"什么都不迁"。** 2026-09-11 评审 D8 把原文的"不迁数据、人工重建"改成**迁任务定义 `agent_task`、不迁历史日志**（理由：两库同在一个 MySQL 实例内，跨库 `INSERT ... SELECT` 只是几十行脚本，而"人工重建"在任务量非零时是真实负担）。**发布 2 实施前用户确认本部署没有历史数据**，于是 D8 剩下的那一半也一起去掉：没有迁移脚本、没有幂等守卫、没有自校验查询，也没有"历史变空 / 最近运行两列变空"这类要公告的损失——没有东西可失去。落地的形态反而回到了本节原文的结论：**切口后由用户在界面重建任务**，只是这次它不再是代价，而是零数据下的正常路径；旧库 `harnax_admin` 的表因此在切口后直接 DROP，不留观察期。下面的原始推理保留作备查。

不迁数据、上线后人工重建。备选的一次性 `INSERT ... SELECT`（同实例跨库）与双写渐进都被否决：当前环境任务量小、历史日志价值低，而双写要写两套随后即弃的临时代码并显著抬高测试量。代价是明确的——历史执行日志丢弃，需在发布公告里写明。

## 11. 实施计划与进度状态表

> **本节的里程碑编号已被 2026-09-11 的设计评审重排。** 新的决策清单、实施计划（S0~S4）与验收标准见 [docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md](../docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md)。四处修正必读（两处顺序 + 一处范围 + 一处切口形状）：
> - **同步执行必须先于 Quartz JDBC store 上线**。**已按此顺序落地**（S1，commit `1aa9981`）：改造前 `AgentTaskJob` 起裸 daemon 线程后立刻返回，Quartz 认为 job 秒完、`QRTZ_FIRED_TRIGGERS` 不留行，于是故障接管、`waitForJobsToCompleteOnShutdown`、`@DisallowConcurrentExecution` 三者同时失效——按原 M1→M4 顺序会先得到一个"名义集群"。目前两条路径都在 Quartz worker 内同步执行：**手动 trigger 那一条自发布 3 起也是 Quartz 的 fire**（one-shot 合并已完成，见 7.3）。
> - **对账必须与 JDBC store 同期**。**已按此顺序落地**（发布 1，与 `job-store-type=jdbc` 同批）：共享 store 下当年 `loadTasksToScheduler()` 的"全删重建"等于任一节点重启就报掉全集群任务，该方法已被 `reconcileTasks()` 的 diff 收敛取代（见 7.1）。
> - **`agent_task` 的迁移不能与域搬迁分开**（spec 修正 C）：admin 还在写这张表时它只能有一份真相，所以发布 1 只做到 QRTZ 层——`QRTZ_*` 临时建在 `harnax_admin`，用自有的 `flyway_schema_history_scheduler` 记账，业务表与库的搬迁随 S3。（这句里的"数据迁移"没有跟着兑现：发布 2 搬了业务表与库，迁移本身被用户「无历史数据」的决定取消，见下一条。）
> - **域搬迁与数据源切换是同一次切口**（spec 修正 D，发布 2 落地时确立）：这两件事不能分批发，而且 **admin 与 scheduler 必须同时下线**——C4 之后 scheduler 拒收未签名的 HTTP（旧 admin 的转发一律 401），C1 的四段 sessionId 双向不兼容（旧 admin 读不懂新 id，新 admin 直接拒旧 id）。这是整个改造里唯一不能滚动做的部分。两条理由都与数据无关：**用户确认无历史数据，D8 的迁移整项取消**，所以这一刀里没有搬数据这一步，新库从空开始、任务由用户在界面重建。正文：`docs/deploy-harnax-scheduler.md` 的「发布 2 切口」。
>
> 下面的 M0~M5 表格保留作为**工作项清单**，不再是进度真相源（进度真相源是 spec 的里程碑表）。状态列于 2026-09-13 随 `fix/scheduler-exec-semantics`（spec 里程碑 S0+S1）逐项对照代码核对过，M0 已整节完成；2026-09-15 随**发布 1（spec 里程碑 S2）**再核对一轮；**发布 2（spec 里程碑 S3，域搬迁 + 数据源切换）已落地**，M1~M3 与 M5 里原来挂"⏳ 属 S3"的行都按此更新，改动的行都写明落在哪个发布。**IT 不在"已完成"之列**：`ClusterSingleFireIT` / `ReconcileConvergenceIT` / `HousekeepingGuardIT` 之外，发布 2 又交了 `AgentTaskOwnerScopeIT`（IT-3）与 `AgentTaskMapperSemanticsIT`，五个类都已就位、能编译、被 `-Pintegration-test` 挡在默认 reactor 之外，但**还没有任何一台有 Docker 守护进程的机器跑过它们**——本节凡"验收"都指的是它们将来会证明什么，不是已经证明了什么。

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
| 1.1 | `db/migration/V1__quartz_tables.sql`（11 张表，剥 DROP） | ✅ 已完成（发布 1）：官方 `tables_mysql_innodb.sql` 的 11 张 `CREATE TABLE` + **20 条 `CREATE INDEX`**，剥掉 DROP 与文件头，补 COMMENT 与 utf8mb4。记在自有的 `flyway_schema_history_scheduler`。历史注：发布 1 时这张历史表在 `harnax_admin` 里（修正 C 的中间态），**发布 2 切库之后它与 V1/V2 一起归 `harnax_scheduler`**，那个库里只有它一个迁移工具 |
| 1.2 | `V2__agent_task_domain.sql`（三表 DDL 落新库） | ✅ 已完成（发布 2）。三张业务表现在的真相源就是它（逐列照抄 admin 的 `V1__init_schema.sql:480-536`，四处改动见 4.1）。`agent_task_log` 即使空着也必须建——`selectTaskList` 自联它取 `lastRunStatus`/`lastRunTime` |
| 1.3 | `application.yml`：数据源指新库、Flyway 开、quartz 集群段、Hikari 池 | ✅ **四项全落（发布 2 补齐最后一项）**：Flyway 开（默认 `true`）、集群段（`isClustered` / `clusterCheckinInterval=15000` / `acquireTriggersWithinLock` / `useProperties` / `initialize-schema: never`）、Hikari `maximum-pool-size=30`，以及**数据源默认 `harnax_scheduler`**（compose 侧走独立的 `SCHEDULER_DB_URL`）。发布 1 那个"仍指 harnax_admin"的中间态到此结束 |
| 1.4 | pom：testcontainers + failsafe/surefire IT profile | ✅ 已完成（发布 1）。三个 `*IT` 在 surefire 侧靠 `@Tag("integration")` + `excludedGroups` 挡（`<excludes>` 会被 `-Dtest` 顶掉，不能当护栏），failsafe 挂在 `-Pintegration-test` 后，且必须带 `additionalClasspathElements`（本模块 repackage 无 classifier，否则 BOOT-INF 遮蔽会让 IT 找不到类） |
| 1.5 | 部署：`init-databases.sql` 建库授权、compose 环境变量、删 `container_name`、删 28084 映射、`.env.example` 补全 | ✅ 发布 1 范围内已全落，**发布 2 用上了**：`harnax_scheduler` 建库 + 授权从"预建但空转"变成本服务的活库（**注意那脚本只在 MySQL 首次初始化空数据目录时执行，存量部署要手工补两行**）；compose 的集群变量与 `SCHEDULER_FLYWAY_ENABLED`、`container_name` 已删（否则 `--scale` 直接被拒）、宿主映射早已改 `expose`。发布 2 新增两项：compose 侧独立的 `SCHEDULER_DB_URL`（**不复用** admin/agent/channel 共享的 `DB_URL`，否则一改变动带走三个服务）、`.env.example` 的连接串与 scheduler 段说明。另有一项不在原清单里：逐台滚动 `docker-new/roll-scheduler.sh`（其结尾回读已改指 `harnax_scheduler.QRTZ_SCHEDULER_STATE`） |

**里程碑验收（尚未执行，别当成已过）**：单实例以 JDBC store 正常起来；两实例连同库时 `QRTZ_SCHEDULER_STATE` 里有两个 `INSTANCE_NAME` 且各自的 `LAST_CHECKIN_TIME` 每 15s 前进（**不是"数出两行"**：停机不删自己那行，表里可能还有没被对端清掉的尸行）、kill 其一另一个在 22.5~52.5s 内接管（算式见 §1 第 2 条）。三个 IT 各管其中一部分：`ClusterSingleFireIT` 断言"两个成员都在册 + 一次触发全集群只落一次"（这是真集群与各节点各 fire 的唯一硬证据），`ReconcileConvergenceIT` 断言 diff 收敛，且没动过的那条 job **保留它自己的 `NEXT_FIRE_TIME`、其 trigger 行的 `START_TIME` 从未变过**（同一轮里被重写的那条 `START_TIME` 确实前移，是正向对照），`HousekeepingGuardIT` 断言两张清扫各自只删自己该删的行。**"kill 其一、另一台接管"没有任何自动化在证明**，它只能在验收机器上手工 `docker kill` 一次并回读上面那条 SQL。三条都还没跑过：`mvn -o -pl harnax-scheduler verify -Pintegration-test`；跑通之前不要在任何地方写"集成测试通过"。

### M2 实体与 mapper 迁进 scheduler

| # | 项 | 状态 |
|---|---|---|
| 2.1 | 三实体 + 三 mapper + 三 XML 从 harnax-entity **移动**到 scheduler（含 `@MapperScan`、`type-aliases-package`、XML 全限定 type 同步） | ✅ 已完成（发布 2）。`com.agnetix.harnax.scheduler.{entity,mapper}` + `harnax-scheduler/src/main/resources/mapper/` 三个 XML（namespace/resultMap 全改），`SchedulerApplication` 的 `@MapperScan` 与 `application.yml` 的 `type-aliases-package` 跟着改指；`harnax-entity/src` 现在对 `AgentTask`/`agent_task` **零命中** |
| 2.2 | `AgentTaskLogMapperTest` 随迁；`schema-test.sql` 删除对应段与种子 | ✅ 已完成（发布 2）。三个 mapper 测试合并为 `harnax-scheduler/.../it/AgentTaskMapperSemanticsIT.kt`（真库、Flyway 建表、用例内自插自删，**不再靠种子**），`AgentTaskLogStopGateSqlTest` 的 SQL 门禁测试搬到本模块并改英文消息；`harnax-entity/src/test/resources/schema-test.sql` 删掉三段 DDL 与 6 条种子。该 IT 与发布 1 的三个 `*IT` 一样从未执行（无 Docker） |
| 2.3 | scheduler 自带 `Page`/`BizException`/`ApiErrors`/`GlobalExceptionHandler` 小份副本 + pagehelper 依赖 | ✅ 已完成（发布 2）。`scheduler/dto/Page.kt`（7 个键，`PageContractTest` 钉住形状）、`support/SchedulerBizException.kt`、`config/SchedulerWebConfig` 里的 advice 副本，`pagehelper-spring-boot-starter:2.1.0`（带对 `mybatis-spring-boot-starter` 的 exclusion，照 admin 的 pom） |

**注意（原约束已被实现方案消解）**：原本写的是"M2 完成即 admin 编译断裂，M2 与 M3 必须同一个 PR"。实际做法是先让 scheduler 建**自己的一套**（包名不同，故与 `harnax-entity` 的旧套并存不冲突），再搬 admin 的读面，最后才删 `harnax-entity` 的原件——于是每个提交都能编译、都能跑测试，没有那次"一次断裂"的大提交。实测的引用面（订正 spec 里"24 处"的旧基线）：`harnax-admin/src/main` 里 **19 处** mapper 引用分布在 **4 个文件**（按发布 2 动代码之前的基线重数：`AgentTaskServiceImpl` 13、`AgentTaskLogServiceImpl` 2、`InternalApiController` 2、`McpSessionOwnerResolver` 2。旧基线里"12/5/4/3"那份逐文件拆分**加起来正好是 24**，错就错在这里，一并作废），另有 **7 个** admin 文件 import 这三个实体/DTO 类型——编译边界比那条 grep 更宽，两处都要一起改才算搬干净。现在两条都是零命中。

### M3 CRUD 落位 + admin 薄代理（原子切换）

| # | 项 | 状态 |
|---|---|---|
| 3.1 | scheduler：CRUD service、日志 service（不迁死代码 `save()`）、4 个 DTO、`/api/scheduler/agent-tasks/**` controller（12 端点，保持 `records`/`total`/`id` 契约） | ✅ 已完成（发布 2）。**11 个 CRUD/日志端点**（`AgentTaskController.kt`：page/detail/create/update/delete/toggle/start/pause/trigger/stop/logs），响应外壳与 admin 今天的一致（`Page` 7 键、`records[*]` 字段名与业务码一字未改）；"不迁 `save()`"确实无对象——admin 侧那个方法已随 S0 删除。DTO 4 个（含 create/update 请求体带上 admin 已解析的 `agentName`，缺失回 400，见计划 Task 6 的跨域取数决定） |
| 3.2 | ~~scheduler：`GET /agent-tasks/{id}/agent-id` internal 端点~~ | ❌ **已作废**（评审 D4：agentId 编进 sessionId，见 spec 契约 C1。该端点、其测试与一跳 admin→scheduler 转发均不再需要） |
| 3.3 | scheduler：约 20 行 internal-token 校验拦截器（8.2 的偏离项） | ✅ 已完成（发布 2 的 C4）。`support/InternalCallerInterceptor` + `CallerContext`，注册在 `config/SchedulerWebConfig.kt:51-53`，验签用的 provider 是本模块自声明的 bean（`SchedulerConfig.kt:36-40`）。**覆盖范围比 spec §2.3 那句"全部写面"更宽——读面也在内**，理由是 C5 那个 owner 读端点凭一个任务 id 就能读出创建人与租户（偏离的说明在 8.2 末段） |
| 3.4 | admin：`AgentTaskController` 瘦身为鉴权 + 转发，路径不变；注入 `X-Forwarded-User`/`X-Tenant-Id` | ✅ 已完成（发布 2）。12 个端点里 11 条走 `SchedulerClient.forward`（`/agents` 留在 admin 的域），响应体按 `JsonNode` 原样回传——不在 admin 再镜像一份 DTO，那会是同一份契约的第二套定义，漂移要等到客户端读到 null 才看得见 |
| 3.5 | admin：删 `AgentTaskService(+Impl)`、`AgentTaskLogService(+Impl)`、4 DTO、`SchedulerClient` 广播逻辑 | ✅ 已完成（发布 2 删前四项；广播一项属发布 1，共享 store 之后就删成了 `urls[0]` 一次调用）。admin 现在对这四张表零 SQL，测试处置表（禁止靠删用例变绿）见计划 Task 7 第 3 步 |
| 3.6 | admin：~~`resolveFromTask` 改调 agent-id~~ → 改为**纯字符串解析**取 `parts[2]` 当 agentId，`split` 用 `limit=4`，并**删除对 `agentTaskMapper` 的依赖**；~~删除死端点 `/internal/agent-tasks/{id}/spec` 及其测试~~ | ✅ 已完成（发布 2 的 C1）。落地的不是手写 `split` 而是两侧同一份 `com.agnetix.harnax.common.session.TaskSessionId`（放 `harnax-common` 因为 admin 不能依赖 scheduler），`resolveFromTask`（`InternalApiController.kt:369-373`）解析失败即抛期望格式。**代价**：那次 agentId 与任务行的比对随域搬迁不可执行，登记为 spec F15。死端点与零引用的 `AdminApiClient.getTaskAgentSpec` 早已在 S0 删除 |

**里程碑验收**：webui 列表（含 `lastRunStatus`/`lastRunTime`）、创建/编辑/启停/删除、立即执行、日志弹窗轮询全通；CLI 与小程序各跑一遍。**这条仍是验收机器上的手工活**，本仓库没有跑过（交付机无 Docker）。发布 2 新增的自动化只有 `AgentTaskOwnerScopeIT`（IT-3，越权与可见性在真库上的断言）——**已写、从未执行**。回滚点：**不是** "revert 整个 PR（旧表数据仍在）"，因为切口之后新库里已经有了新数据；退路写在 `docs/deploy-harnax-scheduler.md` 的「发布 2 切口」——数据源指回 `harnax_admin` + `QUARTZ_JOB_STORE=memory` + `SCHEDULER_FLYWAY_ENABLED=false`，并明确接受"切口后新建/改过的任务只留在新库里、不会跟着回来"。

### M4 对账 + one-shot + housekeeping

| # | 项 | 状态 |
|---|---|---|
| 4.1 | `loadTasksToScheduler()` → `reconcile()` diff 收敛，删除全删重建 | ✅ 已完成（发布 1，与 JDBC store 同期）：`TaskScheduleReconciler` + `SchedulerReconcileJob`（60s，`@DisallowConcurrentExecution`）。清扫 trigger 也在共享 store 里，所以**间隔是集群级一个值**——配置不一致时最后启动的那台说了算（`SCHEDULER_RECONCILE_INTERVAL` 因此不该按实例改） |
| 4.2 | JobDataMap 只放 `taskId` 字符串；`AgentTaskJob` 回查库 | ✅ 已完成（发布 1），见 7.2。附带改变了"删除后还会不会火"的结论 |
| 4.3 | `AgentTaskNonConcurrentJob` + 按 `concurrent` 选 job 类 | ✅ 已完成（commit `1aa9981`，与 4.4 的同步执行同批） |
| 4.4 | 手动执行合并为 Quartz one-shot，废弃那条线程；`AgentTaskJob` 改同步执行 | ✅ **两半都完成**。同步执行那半属 S1（commit `1aa9981`，`AbstractAgentTaskJob.run()` 在 Quartz 线程内跑完才返回；正是这一步让 D6 的 `waitForJobsToCompleteOnShutdown`、`@DisallowConcurrentExecution` 与故障接管第一次真正生效，也才让 `stop_grace_period` 有意义）。one-shot 合并那半属**发布 3**：`triggerManually` 连同 `SchedulerConfig.taskExecutor()` 一起删除，`/trigger` 与 `/run-once` 都只投 `AgentTaskGroup_ONCE` 的 one-shot（见 7.3） |
| 4.5 | 60s reconcile 集群 job；housekeeping job 挂 `cleanupOldExecutions()`（当前零调用方） | ✅ **两半都完成**：housekeeping 属 S1（commit `b7a33f7` + `ca04de0`，5 分钟一轮，四件事：`expireStale` / 日志 90 天保留 / guard 行 7 天过期 / `status=0` 泄漏锁回收），60s reconcile 属发布 1。两把 sweep 都注册在 `SchedulerSystemGroup`（不是 reconcile 会收敛的 `AgentTaskGroup`，否则自己删自己），且因为 store 共享，**它们都是集群单例**（见 6.5） |
| 4.6 | 健康/指标改集群语义；新增 `scheduler.reconcile.drift` | ✅ 已完成（发布 1）：`storeType` 进 detail、`scheduledJobCount` 改成当场读 store、新增 `scheduler.reconcile.drift{action}`、`scheduler.load.attempts` 改名 `scheduler.reconcile.rounds{outcome}`（一轮一个样本，见第 9 节）。**运维侧的半边还没做**：既有看板与阈值要按新语义重读（spec F9） |

### M5 DROP 旧表 + 暴露面 + 文档

| # | 项 | 状态 |
|---|---|---|
| 5.1 | admin `V27__drop_agent_task_tables.sql`。~~硬约束：晚于 scheduler 上线、观察过至少一个完整 cron 周期、运维签认后才合入~~ | ✅ **形态已变（发布 2）**：那三条硬约束是为**搬了数据**的表设的，而这一版什么都不迁（D8 取消，见 10.4），所以旧 `harnax_admin` 的三张业务表与 11 张 `QRTZ_*` 在切口后没有任何活着的读者——**直接 DROP，不留观察期**。**最终落法（2026-09-22）**：三张业务表的那一刀合进了仓库，用的就是当初起的名字，号位从 V27 挪到 V39（V27 已被执行锁的 sweep 索引占用）→ `V39__drop_agent_task_tables.sql`。不合进去的代价更实在：`V1__init_schema.sql` 建过这三张表，全新部署每次都会把它们重放出来，运维在这个库里找任务表会找到一张空的。11 张 `QRTZ_*` 仍由运维就地执行——它们只在跑过发布 1 的安装里存在过。反面代价：回滚三件套里"数据源指回 `harnax_admin`"从此要先重建表才指得回去。步骤与回滚正文：`docs/deploy-harnax-scheduler.md`「发布 2 切口」第 8 步 |
| 5.2 | nginx `/api/scheduler/` 改 allowlist 或删除；若保留补 `client_max_body_size` | ✅ 已完成（第二轮 R1）：该 location 整段删除并在原位留了禁止回加的注释，compose 侧也只有 `expose: ["8084"]`、不发布宿主端口 |
| 5.3 | 重写 `docs/agent-task-design.md` 架构节；修 `docs/deploy-harnax-admin.md` 的 RAMJobStore 说法；新建 scheduler 部署文档 | ✅ **三项齐了（发布 2 补完最后一项）**：`docs/deploy-harnax-scheduler.md` 已有、按集群拓扑重写过、并新增「发布 2 切口」；`docs/deploy-harnax-admin.md` 现在写明本服务的定时任务域只剩鉴权 + 带身份转发（12 端点契约不变、四张表零 SQL、转发头三件套与 `HARNAX_AUTH_SECRET` 必须两侧同值）；`docs/agent-task-design.md` 的架构节不再把 Quartz/`AgentTaskJob`/`agent_task` 的写侧画在 admin 里，并把「一、数据模型」标为已被 `V2__agent_task_domain.sql` 取代 |

### 工作量

M1 2.5 + M2 2 + M3 3.5 + M4 3 + M5 2 = 共通 13 人日，加形态 B 的转发层 1 人日、M0 的 0.5 人日 ≈ **14.5 人日**。最大单项是测试重写（约 1900 行有效测试 + 5 个新集成测试）。

## 12. 测试策略

新增集成测试全部放 scheduler 模块，走 failsafe profile（`-Pintegration-test`），testcontainers 起 MySQL 跑真库——本域的难点（集群抢锁、CAS 定态、diff 收敛）全是 mock 测不出来的。

**发布 2 之后的实际状态**：表里的 IT-1（`ClusterSingleFireIT`）、IT-2（`ReconcileConvergenceIT`）、IT-5（`HousekeepingGuardIT`）与**发布 2 新增的 IT-3（`AgentTaskOwnerScopeIT`，属主与可见性规则随域搬进本模块，被测对象终于在这里）和 `AgentTaskMapperSemanticsIT`（三个 mapper 测试随迁合并成一个）** 都是能编译的类，五个**一次都没有执行过**——交付机器上没有 Docker 守护进程。IT-4 的阻塞项（one-shot 合并）已由发布 3 完成、**但用例本身仍未编写**。所以这里没有任何"全绿"可陈述。要证它们，在有 Docker 的机器上跑：

```bash
mvn -o -pl harnax-scheduler verify -Pintegration-test
```

既有测试处置（发布 2 已按此执行，逐条落点）：`AgentTaskServiceImplTest`(603) 的属主/校验/文案断言迁成 scheduler 的 `AgentTaskCrudServiceImplTest`，`afterCommit`/40902 那四条也迁过去（stub 从"scheduler 客户端"换成"本地 reconciler 的 `reconcile()` + `converged` 判断"，语义等价）；`AgentTaskLogServiceImplTest`(271) 的参数透传四条迁 scheduler、"读契约不带 tenant 形参"改为 mapper 接口的反射断言；`AgentTaskControllerTest`(657) 拆成 admin 的 `AgentTaskForwardingContractTest`（MockWebServer 假装 scheduler，参数表驱动，7 个 query 参数原样出站）+ scheduler 的 CRUD 测试；`it/AgentTaskCrudIT`(171) 与 `it/AgentTaskSchedulerIT`(208) 留在 admin、被测对象改为转发契约（其中 `toggle unknown task fails without calling scheduler` 一条改了方向，因为门禁随数据一起走了）；`SchedulerClientImplTest`(452) 加转发与身份头用例；`harnax-entity` 的三个 mapper 测试与其 `schema-test.sql` 的三段 DDL + 6 条种子**删除**（语义随迁到 `AgentTaskMapperSemanticsIT`）。scheduler 原有 3 个 Mockito 单测（startup-load / stop 状态机 / health）保留并按新签名调整。

| 用例 | 断言什么 | 为什么值得写 |
|---|---|---|
| IT-1 集群单触发 | 两个手工建的 Quartz 成员连同库，1s cron 跑 8s：`it_cluster_fire` 行数 ≈ 触发次数（不是 2×）、两台的计数相加**等于**行数、该测试自己的 `SCHED_NAME` 下正好两行在册、且没有第三个实例 id 计到过 fire | 这是"真集群"与"各节点各 fire"的唯一硬证据。它**不断言工作怎么分**——谁先拿到行锁谁跑，一台全输不是 bug。证据不用 `agent_task_log`：那两台是手搓的 Quartz 成员，不经过应用链路 |
| IT-2 对账收敛 | SQL 手造三种漂移（只改 store 里的 cron / 表里删一行留 job / 一条完全不动）→ `reconcile()` → 四个桶正好是 `0/1/1/1` 的划分且 `failedIds` 空；被删任务失去 job、漂移的 cron 被拉回表里的值；**没动的那条保留它自己的 `NEXT_FIRE_TIME`、其 trigger 行的 `START_TIME` 从未变过**（同轮里被重写的那条 `START_TIME` 前移，作为正向对照） | 后半句是"没有回归成全删重建"的直接证据——一个把所有 job 都重注册的实现也能过前面所有断言，只有这两条时间戳能抓住它。"没动的那条"故意用小写 cron，所以它同时钉住"按 Quartz 归一化结果比较"这条规则 |
| IT-3 越权 | 非属主身份读/改/删 → 拿不到且数据未变；`is_public=1` 可访问 | M0 修复的回归护栏 |
| IT-4 one-shot | 连发两次 trigger：store 出现 `_ONCE` trigger、第二发被拒返回 40901；模拟投递后 kill 进程，重启补火一次且仅一次 | 覆盖 7.3 的两个新语义 |
| IT-5 guard 清理 | 造过期 `agent_task_execution` 行 → 跑 housekeeping → 过期行删除、未过期保留 | `cleanupOldExecutions()` 此前零调用方 |

## 13. 已知问题与 fast-follow

明确不在本轮：

> 已从本清单移除的一条：`AgentTaskJob.interrupt()` 的名义空实现**已随 S1 摘掉**——两个 job 类都不再实现 `InterruptableJob`（真正的停止是发给 router 的 INTERRUPT 命令，见 6.4），广告一个 Quartz 级中断只会误导读者。

1. **scheduler 服务自鉴权**：`harnax.auth.enabled=true` 让 `UnifiedAuthFilter` 真正装配、端点加 `@InternalOnly`。（`SchedulerClientImpl.kt:35` 那句与事实相反的注释已在 S0 改为如实描述：scheduler 侧不装配过滤器，admin 发的 internal bearer 目前无人校验，是向前兼容。）
2. **生产收窄匿名面**：关 `SWAGGER_ENABLED`，`management.endpoints.web.exposure` 收窄（现在 `/actuator/prometheus` 随 8084 匿名可达）。
3. **日志脱敏**：`agent_task_log` 查询 `SELECT *` 全文下发 `prompt`/`response`/`errorInfo`，且无租户/属主过滤。admin 侧已有 `AgentTaskLogResponse`（1:1 全字段、当前未被 controller 使用），本可作为裁剪出口，本轮未启用。属主口径已修（读日志要通过任务可见性join），脱敏仍未做。
4. **`/api/admin/agent-tasks/{id}` 之外无细粒度授权**：整个域只要求"已登录"（`SecurityConfig.kt:52-53` 兜底），无角色/权限码；`tenant_id` 只在 create 用一次（`AgentTaskServiceImpl.kt:79`），MyBatis 层不设租户拦截器（无任何自动过滤）。属主条件（M0.1）是目前唯一的隔离手段，比租户隔离更弱。
5. **`agent_task_execution` 缺索引**：✅ **已落地（第三批次 G4，V27）**——补了 `(status, create_time)` 与 `(create_time)`，并同步了 `harnax-entity` 的 `schema-test.sql`。原本的状况：现表只有 PK、`uk_task_trigger(task_id, trigger_time)`、`idx_task_id`、`idx_trigger_time`，`status` 与 `create_time` 都没有索引，而 S1 的 housekeeping 把 `deleteOldExecutions`（按 `create_time`）与 `deleteStaleRunning`（按 `status = 0 AND create_time < ...`）挂成每 5 分钟一轮，这张表又按触发次数线性增长 → 每轮两次全表扫，扫描范围锁与 `tryAcquireLock` 的 INSERT 互顶。**尚未做的是"这两条语句在真库上确实走新索引"**：那要靠 `EXPLAIN` 在真 MySQL 上看，而真 MySQL 只在集成测试的 Testcontainers 里有——`mvn -o -pl harnax-scheduler verify -Pintegration-test`（需要 Docker 守护进程）跑起来之后顺手补，**这条现在仍是"没观察过"而不是"已验证"**。对应 spec 第 9 节 F10。
6. **`scheduler.jobs.scheduled` 的语义跳变已经发生**：发布 1 把 store 换成共享 JDBC 之后，同一个表达式的读数就是**集群视图**（两台的取值相同），不再是每台各报自己注册的那一份。这条现在只剩**运维侧动作**：既有看板与告警阈值若按旧含义设过（例如"某台报 0 就是它没在调度"），必须重读一遍。对应 spec 第 9 节 F9。
7. **🔴 `GET /api/admin/channels/page` 没有租户过滤——发布 3 判定超范围，明确未修**（对应 spec 第 9 节 F12）。`selectChannelList` 除了 `active`/keyword/type/status 不过滤任何东西，`tenant_id` 不在 WHERE 里，而 admin 的 MyBatis 层根本没有租户拦截器（就是上面第 4 条说的同一个东西），于是一份频道列表可以带上别的租户的行，连 `configJson` 凭证与 `sessionId` 一起下发。发布 3 之后它还多一个连带后果：那份混合列表的 sessionId 会被整批送去 router 的 workspace-status，而 `chn-` 现在答得出归属，别的租户那一个就整批抛 `SecurityException`——webui 侧是空 catch，所以现象是**同租户操作员**的 Sandbox 列与 Workspace 按钮静静失灵。它同时顶穿了" `chn-{uuid}` 不可枚举"这个前提（列表页就是拿到别人 UUID 的地方）。最小正确修法是列表查询上的租户谓词 + 一条显式的平台管理员豁免，正文写在 spec F12。
8. **归属变更最多 5 分钟内不可见（发布 3 留下的已知窗口）**。router 的 `SessionInfoClient` 把 admin `/internal/sessions/{id}/info` 的答复连结果一起缓存（写后 5 分钟过期、上限 5000 条），所以一次归属改动的生效是**渐进**的：本次发布把 `chn-` 从 Unknown 变成 Found，也仍然要等每个 id 自己的那 5 分钟。这是既有缓存的既有语义，不是新缺陷；要立刻见效只能重启 router 或等 TTL。
9. **🔴 F13 启停/触发面没有写侧属主门禁（发布 2 原样搬过来，登记未修）**：`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:106-107` 的 `updateStatus` 不带 `creator` 条件（同文件的 `updateById`/`deleteById` 都带），而 `AgentTaskController.kt:177`/`:181`/`:185` 三条 scheduling 端点直接 `relay`，没有 `toggle`（`:148-156`）与 `stop`（`:199-201`）那样的可见性/属主前置读。净结果：任何一枚登录态凭据都能按 id 启停或立即执行别人的任务。论证与修法正文在 spec 第 9 节 F13，此处不重复。
10. **🔴 F14 已删任务的名字永久不可复用，且撞键报的是 500**：`uk_name`（`V2__agent_task_domain.sql:53`）不含 `active`，`deleteById` 只置 `active = 0`（`AgentTaskMapper.xml:67-69`），而重复名预检查用的 `selectByName` 带 `active = 1`（`:98-99`）——于是"名字仍被占用，但查不到占用者"，最后由 INSERT 撞键、被 `AgentTaskController.kt:99-101` 包成 code 500。修法要先定"已删名字该不该复用"，正文在 spec 第 9 节 F14。
11. **🟡 F15 sessionId 的 agentId 不再被交叉校验**：域搬走之后 admin 读不到 `agent_task`，`InternalApiController.kt:369-373` 于是**信任**字符串里的 agentId 来装配 spec，可达集合从"有任务的 agent"扩大到"任意 agent"（仍需内部调用方身份，见 8.2 与 spec F3-B）。补偿是生成侧同源 + 解析侧严格四段；正解是 F3-A 的归属扩展。正文在 spec 第 9 节 F15。

## 14. 运维 checklist

> **本节是发布 2（域搬迁 + 数据源切换）的切口清单，不是发布 1 的操作步骤**，而且它**已经按"什么都不迁"重写过了**：用户确认本部署没有历史数据，D8 的迁移整项取消，所以原来那 10 步里的 `mysqldump` 留档、"迁 `agent_task` 并核对四个数字"、"签认后合入 `V27__drop_agent_task_tables.sql`" 都不存在了，"任务不需要人工重建"那句也作废——**恰恰相反，任务要人工重建**。逐步可照敲的版本在 `docs/deploy-harnax-scheduler.md` 的「发布 2 切口」，这里是同一件事的清单形态。

1. 确认 `harnax_scheduler` 库已建好并授权（`init-databases.sql` 已含；**存量部署要手工补那两行 + `FLUSH PRIVILEGES`**，那脚本只在 MySQL 首次初始化空数据目录时执行）。
2. `.env` 侧检查三件事：`SCHEDULER_DB_URL`（要换库时才需要设；不设就走 compose 默认的 `harnax_scheduler`）、**两个服务同值的 `HARNAX_AUTH_SECRET`**、**所有 scheduler 节点 NTP 同步**。
3. 停 admin + scheduler 的**全部副本**。两条不能分批的理由都与数据无关（C4 拒未签名 HTTP、C1 的四段 id 双向不兼容），所以这两边必须同时下线；这一步唯一的"排空"考虑是**在途执行**：一条跨过切口的执行永远定不了态——它的日志行留在旧库，切口后的 scheduler 只会在 `harnax_scheduler` 里找它，`finishExecution`/`markStopping`/`expireStale` 全部 0 行，那行就停在 `status=3` 且再没有回收扫描看得见它。零数据的部署里这一步仍然要做（判据是 `harnax_admin.agent_task_log` 里 `status IN (3,4)` 为 0），因为执行是运行时的东西，跟有没有历史无关。
4. 起**一个** scheduler 副本（数据源指 `harnax_scheduler`），让 Flyway 把 `V1` + `V2` 应用出来。
5. 核对表：三张 `agent_task*` + 11 张 `QRTZ_*` + `flyway_schema_history_scheduler` 两行。**`agent_task_log` 即使空着也必须在**——`selectTaskList` 自联它取 `lastRunStatus`/`lastRunTime`，缺表是列表页 500。
6. 让对账跑一轮（等 60s 清扫，或建一个任务由 admin 转发触发），核对**被调度任务是 0 个**：`/actuator/health` 的 `scheduledJobCount`，或 `GET /api/scheduler/tasks/status` 的 `scheduledTaskCount`（后者从 C4 起要带内部 JWT）。非 0 = 这台连的还是旧库。
7. 起 admin，三客户端主链路各一遍（webui 列表/创建/编辑/启停/删除/立即执行/日志轮询、CLI `task list|get|create|trigger|stop`、小程序任务页）。
8. 起第二副本（`--scale scheduler=2`），回读 `harnax_scheduler.QRTZ_SCHEDULER_STATE`：**看两个 `INSTANCE_NAME` 各自的 `LAST_CHECKIN_TIME` 每 15s 前进**，别数行数（节点不删自己那行，刚起完表里可能还有没被对端清掉的尸行）。
9. 用户在界面重建任务。**没有合并路径把切口后新建的东西带回旧库**，所以旧库那三张表一旦被删，回滚就只剩"先重建表"这一条路——而删表的动作现在在第 7 步（见下一条）。
10. 清旧库：`harnax_admin` 的三张 `agent_task*` 由 admin 的 `V39__drop_agent_task_tables.sql` 删除，随第 7 步的启动自动生效，不再是一个能拖着的手工步骤；11 张 `QRTZ_*` 仍由运维就地 DROP，且只在跑过发布 1 的安装里有东西可删。都不留观察期，因为没有任何活着的读者。

**发布公告（四条，与"迁不迁数据"无关的那四条）**：

1. **定时任务需要重新创建**。这次切换不搬任何数据（本部署没有历史数据），所有任务在切口后从空库开始，请各任务的创建人在界面上重建自己的任务。
2. **停服窗口内到点的 cron 会被跳过，不会延后补跑**。`concurrent=0` 的任务用的正是 `withMisfireHandlingInstructionDoNothing`，堆在 `QRTZ_TRIGGERS` 里的过期触发按 misfire 丢弃。
3. **定时任务的 sessionId 形态变了**：`task-{taskId}-{uuid}` → `task-{taskId}-{agentId}-{uuid}`（契约 C1）。**外部有存储或解析这些 id 的地方要检查**（列宽、按 `-` 分段的解析器）；`agent_task_log.session_id` 已相应放宽到 `VARCHAR(128)`。
4. **admin 的 HTTP 契约不变，客户端不需要升级**：`/api/admin/agent-tasks/**` 的路径、方法、`ResultVo` 外壳、`Page` 的 7 个键、`records[*]` 的 18 个字段名与 `40901/40902/40903` 的含义一字未改，改的只是背后从"admin 查库"变成"admin 带身份转发"。

> 原来还要写的第五条——"历史执行日志不迁，页面表现为任务存在但无历史"——**随迁移一起取消**：没有历史可失去，所以那条公告不再成立。（第 5 步要求 `agent_task_log` 这张表存在，是 schema 完整性，与有没有历史是两回事。）
