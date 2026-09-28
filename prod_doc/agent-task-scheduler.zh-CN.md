# Harnax 定时任务与 Scheduler 服务

> 本文是「智能体定时任务」（Agent Task）这个业务域的完整设计文档：服务边界、数据模型与状态机、
> Quartz 集群语义、任务生命周期（创建 → 注册 → 触发 → 执行 → 停止 → 回收）、跨服务鉴权契约、
> API 面、可观测性与部署约束。每条陈述都对照当前代码核实过。

## 1. 结论先行

1. **定时任务是独立服务，不是 admin 的一个功能模块。** `harnax-scheduler`（Kotlin，端口 8084）独占
   Quartz 引擎与 `agent_task` / `agent_task_log` / `agent_task_execution` 三张表：实体、mapper、XML、
   CRUD 规则、调度、执行、回收、对账全在本模块（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/`
   下 40 个 `.kt`），Flyway 自管，记录表 `flyway_schema_history_scheduler`。
2. **数据源是本服务自有的 `harnax_scheduler` 库**，`application.yml` 的默认 URL 就写着它。11 张
   `QRTZ_*` 与三张业务表都在那里，由本模块唯一的迁移脚本
   `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql` 建出来，这个库里只有这一个迁移工具。
   admin 的 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 既不建这三张表、也不删它们，
   所以一次全新部署的 `harnax_admin` 里没有本域同名表；改造前就建好的 `harnax_admin` 可能还留着三张空表，
   删除归运维（见「部署与运维 checklist」第 10 条）。
3. **Quartz 用 JDBC 集群模式**：共享 JobStore + `isClustered = true`，`QRTZ_*` 表是唯一调度真相。
   一次 cron 触发在全集群只投递一次、只由一个节点执行。故障接管窗口 22.5~52.5 秒（由
   `JobStoreSupport.calcFailedIfAfter` 与 `clusterCheckinInterval = 15000` 推出，见「Quartz 集群配置」中对
   该式的展开）。
4. **业务层的抢锁是兜底防线。** `agent_task_execution` 的 `uk_task_trigger (task_id, trigger_time)`
   仍然每次 fire 抢一次，但它身后已经有引擎层的单次投递。
5. **admin 在本域只剩「校验用户 JWT + 带身份转发」。** 三个客户端（webui / cli / 小程序）继续打
   `/api/admin/agent-tasks/**`，路径、方法、`ResultVo` 外壳、`Page` 的键、字段名与业务码都由
   `AgentTaskController`（admin）原样透传，`JsonNode` 直接回传；scheduler 的 HTTP 面不对浏览器开放。
6. **CRUD 与它的调度通知在同一个进程里。** 写 `agent_task` 与跑那一轮 reconcile 都在 scheduler 内，
   `/reload` 的作用只剩「外部也可以手动叫一轮收敛」。reconcile 的语义是**跑一轮对账**：写
   `agent_task` 与写 store 不在一个事务里，所以通知仍可能在提交后丢失，丢掉的那次由
   `SchedulerReconcileJob` 每 60 秒的集群清扫兜住。
7. **对账是 diff 收敛，不是全删重建。** 共享存储下「把 `AgentTaskGroup` 里的 job 全删再重建」会让任一
   节点重启就瞬时报掉全集群任务。`TaskScheduleReconciler` 按期望态/实际态求差，`SchedulerReconcileJob`
   每 60 秒兜底跑一轮。
8. **手动执行是一次 Quartz fire。** `/trigger` 与 `/run-once` 都只往共享 store 投一枚 one-shot job，
   与 cron 跑在同一批 worker 上，因此 `wait-for-jobs-to-complete-on-shutdown` 对两条路径同时成立。
9. **入站门禁覆盖 `/api/scheduler/**` 的全部接口，读也在内。** 只接受 `typ=internal` 的 HMAC JWT；
   最终用户身份经 `X-Forwarded-User` + `X-Tenant-Id` 两个转发头注入，且 `InternalCallerInterceptor.preHandle`
   **先验签后读头**。
10. **`harnax.auth.enabled` 在本服务保持 `false`**——那个开关同时会打开外部 API Key 接受、限流与
    `@InternalOnly` 模型，是另一个设计；本服务只装自己那道只认服务令牌的拦截器。

## 2. 服务边界与模块结构

```
                    ┌──────────────────────────────────────────────┐
  浏览器 / CLI / 小程序 │                nginx（唯一入口）             │
                    └───────┬──────────────────────────┬───────────┘
                            │ /api/admin/agent-tasks/** │ /api/router/**
                            ▼                           ▼
                    ┌───────────────┐           ┌──────────────┐
                    │  harnax-admin │           │     router   │
                    │ 校验用户 JWT   │           └──────┬───────┘
                    │ 注入身份头转发 │                  │
                    └───────┬───────┘                  │
        internal JWT + 两个 │ HTTP（容器网，不对浏览器开放）
        转发头 + X-Caller-Id ▼                         │
        ╔═══════════════════════════════════════════════════════════╗
        ║  harnax-scheduler  （N 个实例，无状态，可任意 scale）        ║
        ║  ┌────────────────┐ ┌──────────────┐ ┌──────────────────┐ ║
        ║  │ CRUD Service   │ │ Quartz 引擎   │ │ AgentTaskJob     │ ║
        ║  │ + reconcile    │ │ JobStoreTX   │ │ (fire 时回查行)  │ ║
        ║  └───────┬────────┘ │ isClustered  │ └────────┬─────────┘ ║
        ╚══════════╪══════════╧══════╤═══════╧══════════╪═══════════╝
                   ▼                 ▼                  ▼
        ┌─────────────────────────────────────┐   POST /api/router/agent/chat
        │  harnax_scheduler 库（Flyway 独占）    │   会话 task-{taskId}-{agentId}-{uuid}
        │  agent_task / agent_task_log /       │
        │  agent_task_execution / QRTZ_* ×11   │
        └─────────────────────────────────────┘
```

### 2.1 职责划分

| 服务 | 负责 | 不负责 |
|---|---|---|
| **harnax-admin** | 校验用户 JWT、解析 `tenantId` / `username`、以 internal token + `X-Forwarded-User` + `X-Tenant-Id` 转发到 scheduler、`agent-spec` 装配（解析 `task-{taskId}-{agentId}-{uuid}` 取 agentId，纯字符串，不查库也不问人）、冷路径上调 owner 端点取任务属主 | 本域四张表的任何 SQL、Quartz、CRUD 业务规则、reload 广播 |
| **harnax-scheduler** | 任务定义存储、CRUD 校验与属主规则、Quartz 集群调度、执行与状态机、执行日志、僵尸回收、对账收敛、guard 清理、入站的 internal-JWT 门禁 | 读别人的库——本服务只有一个数据源，就是 `harnax_scheduler` |

### 2.2 包结构

| 包 | 内容 |
|---|---|
| `scheduler/controller` | `AgentTaskController`（CRUD + 日志 + 停止）、`SchedulerController`（调度动作与 `/reload`）、`AgentTaskOwnerController`（属主读端点 `GET /api/scheduler/agent-tasks/{id}/owner`） |
| `scheduler/service`（+ `impl`） | `AgentTaskCrudService`、`SchedulerService`、`AgentTaskLogQueryService`、`TaskScheduleReconciler`、`AgentTaskExecutionGuard` |
| `scheduler/job` | `AbstractAgentTaskJob` 与两个子类、`TaskQuartzRegistrar`、`SchedulerReconcileJob`、`SchedulerHousekeepingJob` |
| `scheduler/entity` + `scheduler/mapper` + `resources/mapper/*.xml` | 三个实体、三个 mapper、三份 XML |
| `scheduler/support` | `InternalCallerInterceptor`、`CallerContext`、`SchedulerBizException` |
| `scheduler/health` | `SchedulerHealthIndicator`、`SchedulerStatus`、`QuartzJobInventory` |
| `scheduler/client` | `RouterClient`、`CommandDelivery` |
| `scheduler/dto` | `Page`、四个任务 DTO |
| `scheduler/config` | `SchedulerConfig`（bean 装配与内部令牌 provider）、`SchedulerWebConfig`（拦截器注册 + 异常 advice） |
| `harnax-common` 的 `common/session/TaskSessionId` | 任务会话 id 的语法，生成侧与解析侧共用一份 |

## 3. 数据归属

`harnax_scheduler` 库由 scheduler 的 Flyway 独占管理（`harnax-scheduler/src/main/resources/db/migration/`），
admin 的 Flyway 不涉及它。

| 表 | 性质 | 谁写 | 谁读 |
|---|---|---|---|
| `agent_task` | 业务真相（任务定义） | scheduler CRUD | scheduler（注册 job、fire 时回查） |
| `agent_task_log` | 执行历史 + 运行时状态机 | scheduler | scheduler（并发拦截、回收）、admin 经转发读 |
| `agent_task_execution` | 集群抢锁记录（引擎实现细节） | scheduler | scheduler |
| `QRTZ_*` × 11 | 引擎状态 | 只有 Quartz | 只有 Quartz |

三张业务表与 `QRTZ_*` 同库，因此两条跨职责边界的 SQL（列表页自联 `agent_task_log` 取最近一次执行、
回收语句 JOIN `agent_task` 取 `timeout_seconds`）都在同一个服务、同一个库里执行，无需任何跨服务调用。

### 3.1 业务表要点

`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql` 是三张业务表的 schema 真相源，
也是本仓库里唯一的一份定义——admin 的 baseline 不描述这三张表，所以没有第二份可与之比对。这份文件关于
本域的三条事实：

- `agent_task_log.session_id` 为 `VARCHAR(128)`（四段式任务会话 id 更长）；
- `agent_task_execution` 的两条清扫索引 `(status, create_time)` 与 `(create_time)` 内联在建表语句里；
- 文件不含任何移除语句，`harnax_admin` 侧遗留同名表的处置不属于它。

决定读行为的约束：

| 约束 | 行为 |
|---|---|
| `agent_task` 的 `UNIQUE KEY uk_name (name)` | 不含 `active`。`deleteById` 只置 `active = 0`，行仍占着这个名字；而重复名预检查用的 `selectByName` 带 `active = 1`，查不到占用者，最后由 INSERT 撞键 |
| `agent_task.idx_tenant_id` | 存在。`tenant_id` 由 `CallerContext.tenantId` 写入（无值时为 1），读侧的可见性判据是 `creator` 与 `is_public`，不是 `tenant_id` |
| `agent_task_execution.uk_task_trigger (task_id, trigger_time)` | 抢锁的实现：插入成功即获得执行权，唯一键冲突即放弃。`trigger_time` 取 `JobExecutionContext.scheduledFireTime`，不是墙上时钟——各节点必须对「同一次触发」算出同一个键 |
| `agent_task.cron_expression` | 6 段 Quartz 风格表达式，写入前由 `AgentTaskCrudServiceImpl.isValidCron()` 校验 |
| `agent_task.task_status` | 0 暂停 / 1 运行 |
| `agent_task.concurrent` | 0 禁止重叠 / 1 允许，决定 job 类与 misfire 指令（`TaskQuartzRegistrar.jobClassFor`） |
| `agent_task.timeout_seconds` | 驱动两件事：router 调用的读超时上限与回收判定的基线（`expireStaleExecutions`） |

## 4. 执行状态机

`agent_task_log.status`：`3` running、`1` success、`0` failed、`4` stopping、`5` stopped、`2` timeout。

```
  3 running ──正常结束：finishExecution──> 1 success
      │        └───异常─────────────────> 0 failed
      │
      ├── 任意节点请求停止：markStopping   3 ──> 4 stopping（前端立刻得到反馈）
      │                                     │
      │             执行线程收尾 finalizeStopped            4 ──> 5 stopped
      │             停止节点收到「无在途执行」（Missed）      4 ──> 5 stopped
      │
      └── 超时未被收尾：expireStale       3|4 ──> 2 timeout
                                              │
                        真实结果事后回来：reclaimExpired  2 ──> {0,1}（error_info 留标记）
                        停止确曾被读到过：reclaimExpired  2 ──> 5
```

`3 → {0,1,4,2}`、`4 → {5,2}`、`2 → {0,1,5}`，与 `AgentTaskLogMapper` 的类注释同源。

四条写语句都是**带状态条件的 UPDATE**，靠「影响行数为 0」判断被别的节点抢先定态：

| 语句 | WHERE 条件 | 谁写 |
|---|---|---|
| `finishExecution` | `id = #{id} AND status = 3` | 执行线程的正常收尾。守卫只取 3 不取 4：一行既然动到了 4，就是用户停的，执行线程要经 `finalizeStopped` 报告，而不是用自己的结果盖掉那次停止 |
| `finalizeStopped` | `id = #{id} AND status = 4` | 执行线程读到 4 之后 |
| `markStopping` | `id = #{id} AND status = 3` | 受理停止的节点 |
| `reclaimExpired` | `id = #{id} AND status = 2` | 迟到结果的覆盖写。它永远不可能撤销一次用户停止（4/5）或一次已写下的真实失败（0/1）；`error_info` 里的 `(completed after auto-expiry)` 标记区分两种 0/1 |
| `expireStale` | `status IN (3, 4)` 且 `COALESCE(start_time, create_time)` 早于 `CEIL(timeout × 1.5)` 秒前 | 回收扫描（`expireStaleExecutions`） |

这套 CAS 语义要求日志表就在执行节点能用 SQL 直连的那个库里——业务表、guard 表与 `QRTZ_*` 同库正是这个安排。

**一条不可逆边**：`expireStale` 也扫 4，而 4 是用户停止意图的唯一存放处，该语句会把
`error_info` 换成过期文案。于是执行线程事后重读这一行时，它和「从没被停止过」的行完全一样（都停在
2 且标记不同），线程写回真实的 0/1，那次停止从记录上消失。要闭合它需要一个独立的停止标记列，
不是更聪明的 UPDATE。

## 5. Quartz 集群配置

`harnax-scheduler/src/main/resources/application.yml` 是真相源，形状如下：

```yaml
spring:
  quartz:
    job-store-type: ${QUARTZ_JOB_STORE:jdbc}
    auto-startup: ${SCHEDULER_QUARTZ_AUTO_STARTUP:${scheduler.enabled:true}}
    jdbc:
      initialize-schema: never
    wait-for-jobs-to-complete-on-shutdown: ${QUARTZ_WAIT_FOR_JOBS:true}
    properties:
      org:
        quartz:
          scheduler: { instanceName: HarnaxScheduler, instanceId: AUTO }
          threadPool: { class: org.quartz.simpl.SimpleThreadPool, threadCount: ${QUARTZ_THREAD_COUNT:10}, threadPriority: 5 }
          jobStore:
            driverDelegateClass: org.quartz.impl.jdbcjobstore.StdJDBCDelegate
            tablePrefix: QRTZ_
            isClustered: "true"
            clusterCheckinInterval: 15000
            misfireThreshold: 60000
            acquireTriggersWithinLock: "true"
            useProperties: "true"
```

逐项理由：

- **不写 `org.quartz.jobStore.class`。** Spring 的 `SchedulerFactoryBean` 在注入 DataSource 后会把 store
  类覆盖成 `LocalDataSourceJobStore`，好让 store 用 Spring 管理的那个连接池。手写 `JobStoreTX` 会被
  覆盖，还会因为 Quartz 不认识 `quartzDataSource` 这个逻辑名而启动失败。健康指示器的 `storeType`
  detail 就是把运行时真实的 store 类名读出来，用于核对这件事。
- **不需要 `@QuartzDataSource`。** `job-store-type: jdbc` 时 Boot 自动接入唯一的 DataSource。本服务确实
  只有一个数据源，这让 Spring 管理的连接池与集群 store 是同一个东西。
- **`initialize-schema: never`。** Boot 的 `always` 每次启动都跑自带脚本，而那个脚本以
  `DROP TABLE IF EXISTS` 开头——在集群里就是每个节点重启删一次全集群共享的调度状态。Boot 对该键的
  默认值是 `embedded`，MySQL 下本就不会建表，写死 `never` 是把「schema 归 Flyway 独占」固化成约定，
  防的是有人改成 `always`。
- **`instanceName` 全集群必须一致。** 它是 `QRTZ_*` 的 `SCHED_NAME` 列，集群成员靠它相认。
- **`instanceId: AUTO` 在容器化下可用**（hostname + 时间戳 + 线程）。前提是 compose 里不能有
  `container_name: harnax-scheduler`——固定容器名唯一，Docker 会直接拒绝 `--scale`，也可能造成 hostname
  重复从而 instanceId 撞车。compose 的 scheduler 段注释写着这条理由，也写着为什么进容器要
  `docker compose … exec scheduler` 而不是 `docker exec harnax-scheduler`。
- **`clusterCheckinInterval: 15000` 与 `misfireThreshold: 60000`**：misfire 判定必须外于接管窗口。
- **`acquireTriggersWithinLock: true`**：集群下没有本地锁保护 acquire 过程，不开这个两个节点可能各自
  声明同一次 fire，其中一个做完活才发现自己输了。
- **`useProperties: true`**：JobDataMap 以文本 kv 存进 `QRTZ_JOB_DETAILS`，引擎表里没有任何 Java 序列化
  BLOB，实体字段变更不会让已注册任务读不回来。此时非字符串值是硬错误，配合「注册的唯一入口只放 `taskId`
  字符串」这条规则。
- **`threadCount` 与连接池一起动**：`QUARTZ_THREAD_COUNT`（默认 10）是**每节点**闸口，2 实例 × 10 =
  全集群最多 20 个并发执行。Hikari `maximum-pool-size` 默认 30，下界来自 10 个 worker 各占一条连接
  （job 在 worker 线程内同步跑，占用时长就是一次执行）+ 业务查询 + 集群 checkin，全走同一个池。
  只加线程不加池不会多出容量，只是把等待从调度线程挪到 30 秒的 connection-timeout 上。
- **时钟要求**。集群靠比对 `QRTZ_SCHEDULER_STATE.LAST_CHECKIN_TIME` 判断节点死活，节点间时钟偏移超过
  checkin 间隔会误判死亡并触发误抢，所有节点必须 NTP 同步。cron 表达式在 JVM 默认时区解释，各节点 TZ
  要一致（compose 统一挂载 `/etc/localtime`，镜像 `TZ=Asia/Shanghai`）。
- **关掉调度的实例不进集群。** `auto-startup` 跟着 `scheduler.enabled`：Boot 默认会启动 scheduler，
  那样的节点会 checkin、会 acquire trigger，然后拒执行——fire 被吃掉而不是交出去，一次 one-shot
  丢了就永远丢了。退出集群是唯一一种不损耗工作的拒绝。

### 5.1 故障接管窗口

`JobStoreSupport.calcFailedIfAfter` 判一行状态记录过期的依据是：该行自己的最后一次 check-in
+ `max(CHECKIN_INTERVAL, 求值节点距自己上次 check-in 的时长)` + 硬编码 7500ms。代入
`clusterCheckinInterval = 15000` 得到 **22.5 秒**；而存活节点只在自己的 ClusterManager 线程醒来时
求值这个式子（线程睡的就是一个 checkin 周期），于是 **+15 秒轮询粒度**；对端那一轮被慢库拖过时
`max()` 取到 30000 而不是 15000，判定线再外推 15 秒。**下界 22.5 秒，上界 52.5 秒，不会是 15 秒。**

### 5.2 停机等待与宽限期

`wait-for-jobs-to-complete-on-shutdown: true`（Boot 元数据里默认 `false`，必须显式写）让 SIGTERM 之后
等正在跑的 job 收尾。因为 `AbstractAgentTaskJob.run()` 在 Quartz worker 线程内跑完才返回，这条等待对
cron 与手动两条路径同时成立；不做的话一次 SIGTERM 会留下 `agent_task_log` 的 `status = 3` 行与
`agent_task_execution` 的 `status = 0` 锁行，两者都被读成「还在跑」，最长要等一次
`expireStale`（`timeout × 1.5`）才被定成 `2 timeout`。

它必须与容器的 `stop_grace_period` 一起配：compose 里是 `400s`，算式写在
`harnax-deploy/docker-compose.yml` 与 `application.yml` 的注释里——执行超时 300 + 会话清理上限
`min(clear-session-timeout, timeout)` 60 + 两次调用的 connect 预扣 20 + 定态写回与释放锁 12 = 392，
向上取整。**改 `SCHEDULER_TIMEOUT` 或 `SCHEDULER_CLEAR_SESSION_TIMEOUT` 就要在同一改动里改
`stop_grace_period`。**

容量是同一件事的另一面：一次执行占用 `threadCount` 个 worker 之一直到跑完，`SimpleThreadPool`
没有队列，worker 被占满时到期的 cron 只能干等；越过 `misfireThreshold` 就成一次 misfire，而
`concurrent = 0` 用的正是 `withMisfireHandlingInstructionDoNothing`——那一发被跳过，不延后跑。
下限规则：**不要用 1~2 个 worker 跑 scheduler。**

## 6. 任务生命周期全链路

### 6.1 创建 / 更新 / 删除

```
用户在 webui 新建任务
  → POST /api/admin/agent-tasks      （admin：JwtAuthenticationFilter 校验用户 JWT
                                       → SchedulerClient.forward 带 internal JWT + 两个转发头）
  → POST /api/scheduler/agent-tasks  （scheduler：InternalCallerInterceptor 验签 → 读头入 CallerContext
                                       → 属主规则与 cron 校验 → 写 harnax_scheduler.agent_task
                                       → 事务提交后跑一轮 reconcile）
  → reconcile：diff 落进所有节点共读的 QRTZ_* store
```

`AgentTaskCrudServiceImpl` 是本域业务规则的唯一落点，admin 侧一条也没有：

| 操作 | 判据 |
|---|---|
| 读单个 | `selectById(id, currentUsername)`：`active = 1` 且 `(is_public = 1 OR creator = 当前用户)` |
| 列表 | `selectTaskList`，同一条可见性谓词，自联 `agent_task_log` 取 `lastRunStatus` / `lastRunTime` |
| 写（更新） | `selectById` 先放行公共任务，再判 `creator`，不是本人抛 `Only the task creator can modify this task`。`updateById` 的 WHERE 也带 `creator = #{currentUsername}` |
| 删除 | `deleteById` 的 WHERE 带 `creator`，动作是 `active = 0` |
| 创建 | `creator = requireUsername()`（`CallerContext.username`，缺失即拒），`tenantId = CallerContext.tenantId ?: 1` |
| 日志读 | 一律经 `selectOwnedById` / `selectLogList`，可见性挂在**所属任务**上 JOIN 出来，不挂在日志列上：日志行原样重复 `prompt` / `response` / `error_info`，只按日志列过滤等于任何猜得到 task id 的人都能读别人的会话内容。这条谓词里没有租户条件——`tenant_id` 是创建时的快照，任务列表的读也不带租户形参，按租户收窄会出现「列表里有这条任务、它的执行日志是空的」 |

**`afterCommit` 这条规则**：写 `agent_task` 与写 store 不在一个事务里。通知若在事务内发出，对账读到的是
提交前的行，于是把一份尚未生效的定义注册回去（删除场景更糟——它会把 UI 已经认为消失的任务重新注册）。挂在
`TransactionSynchronization.afterCommit` 上，回滚的事务就不通知任何人。

这一发仍可能丢（进程之间没有分布式事务），后果是「定义已存库、没有任何 scheduler 调度它」，出码
**40902 `CODE_SCHEDULER_SYNC_FAILED`**——由 scheduler 自己出，admin 原样透传——而不是回滚：前者只需
重试调度，后者要重做一次保存。**边界要说清**：40902 只保证「这一发没成」，不保证任务永远错着；
60 秒的集群清扫是这条的最后兜底。落在 `SCHEDULER_ENABLED = false` 的实例上时该实例答 40903，
reload 路径把它改判成 40902 回给调用方（40903 的文案留在 message 里）；start / pause / trigger 原样
透传 40903。

`/reload` 由 `SchedulerController.reload()` 承接，语义是**跑一轮对账**（`reconcileTasks()` →
`TaskScheduleReconciler.reconcile()`）：`ReconcileReport.converged` 为真才回成功，一轮没收干净就是非
200，并且答案里直接把人指向 `/actuator/health`（明细在 `lastReconcileError`）。因为它改的是所有节点
共读的 store，所以**一台收敛 = 全集群收敛**，admin 侧因此把逐节点广播塌缩成**一次转发**
（`SchedulerClientImpl` 的 `forward()` 与 `postToScheduler()` 都取 `urls.firstOrNull()`，列表为空时回
一条「No scheduler URL configured」的业务错误而不是抛出）。

### 6.2 注册

`TaskQuartzRegistrar` 是把任务写进 store 的唯一入口，形状：

```
job 类      = concurrent == 0 ? AgentTaskNonConcurrentJob : AgentTaskJob
JobKey      = AgentTask_{id}      / group AgentTaskGroup
TriggerKey  = AgentTask_{id}_trigger / group AgentTaskGroup
JobDataMap  = { "taskId": "123" }    ← 只有 ID，不放实体
misfire     = concurrent == 0 ? DoNothing : FireAndProceed
```

注册是一次 `scheduler.scheduleJob(jobDetail, setOf(trigger), true)`：`replace = true` 原子交换
job + trigger，并且能救回一条没有 trigger 的孤儿 job 行。`checkExists → deleteJob → scheduleJob`
中间有一段「表里 `task_status = 1` 而 store 里什么都没有」的窗口；`rescheduleJob` 在 trigger key
未知时返回装箱的 `null` 而不是 `false`，不是可用的替代。cron 在 build 阶段就被 Quartz 校验过。

`concurrent` 的语义**不**由 misfire 指令承担——misfire 只决定一个*迟到*的 fire 怎么处理，从不决定两个
活的 fire 能否重叠。真正的开关是 `@DisallowConcurrentExecution`，而它是类级注解、无法按 job 实例切换，
所以拆两个 job 类，由 `TaskQuartzRegistrar.jobClassFor(task)` 选；这个函数在 companion 上，因为
`register`、`runTaskOnce` 构造 one-shot、以及 `TaskScheduleReconciler`（拿它和 store 里已有的类比）
三处必须给出同一个答案——否则 diff 会看出一场并不存在的变更，每轮重写每一条 job。

**手动那一发**：`/tasks/{id}/trigger` 与 `/tasks/{id}/run-once` 都调同一个
`SchedulerServiceImpl.runTaskOnce`，投的是

```
JobKey      = AgentTask_{id}_ONCE_{unique}   / group AgentTaskGroup_ONCE
TriggerKey  = 同名 + "_trigger"              / group AgentTaskGroup_ONCE
startNow()，不带 storeDurably()              ← fire 完即被 Quartz 清掉，不留 reconcile 读不懂的行
```

落在这个组而不是 `AgentTaskGroup`，是因为 reconcile 删的是「`agent_task` 里已没有对应期望条目的 job」——一次刚点下去、还在
等 fire 的点击不是谁的过期 schedule。副作用要说清：这一组**不在 reconcile 的视野里**，也不在
`scheduledJobCount` 的计数里（两者都只读 `AgentTaskGroup`）。

### 6.3 触发与执行

```
QRTZ_TRIGGERS.NEXT_FIRE_TIME 到期（cron 与手动 one-shot 是同一类对象）
  → 某个节点在 TRIGGER_ACCESS 锁内把该行 ACQUIRED（全集群只有一个节点成功）
  → 该节点的 Quartz worker 执行 job，AbstractAgentTaskJob.run() 依次：
      ├─ fireTaskId：读 JobDataMap 的 taskId；拿不到就不是我们的 job，直接拒
      ├─ 本机 schedulingEnabled？否则让给别的节点（共享 store 会把 job 分给没注册它的节点）
      ├─ taskToRun：按 taskId 回查 agent_task；行没了就把 store 里的孤儿 job 删掉
      ├─ 守卫：active != 1 一律拒；taskStatus != 1 只对 cron 拒（one-shot 是用户几秒前刚点的意图）
      ├─ 闸口一：concurrent == 0 且本任务已有活执行 → 跳过这一发
      ├─ 闸口二：guard.tryAcquireLock(taskId, scheduledFireTime) 抢不到 → 别的实例在跑
      ├─ executeTaskOnce → insertRunningLog：插 agent_task_log(status = 3,
      │     session_id = task-{id}-{agentId}-{uuid})
      └─ POST router /api/router/agent/chat → session-router → agent-service
         结束：finishExecution 定态 1/0；读到过 4 则 finalizeStopped 4→5；清 session
```

顺序是有讲究的三处：

- **enabled 判定在回查之前**。回查既是读也是写（行没了会 `deleteJob`），一台不该调度的节点先回查就在
  做调度写；而且拒绝本身白花一次数据库往返。
- **两道闸口都在插日志行之前**，所以一次被挡掉的 fire 在 `agent_task_log` 里不留任何痕迹。对手动执行
  这就够成一次用户可见的意外：`/trigger` 拿到 200（one-shot 已成功进 store），fire 时撞上闸口被丢，
  「执行成功 → 打开日志列表」于是可以是空列表。唯一线索是 scheduler 日志里那两行
  `skipping this fire` / `already being executed by another instance`。投递时机还有第三道，与前两道
  不同：`blocksManualRun`（= `concurrent == 0 && hasActiveRunningExecution`）在 `runTaskOnce` 里就拒，
  返回 40901，那一次用户明确看到冲突提示。
- **`taskStatus` 守卫只作用于 cron**（用 JobDetail 的 group 判定，因为 JobDataMap 只有字符串）：一条
  存着的 cron job 是 `agent_task` 的滞后副本，所以 fire 时要问它是否还在跑；一次 one-shot 的注册本身
  就是当前意图，所以任务在「点击之后、fire 之前」被暂停也要跑。软删除两边都拒。

**去重一共三处，各司其职**：投递时的 `blocksManualRun`、fire 时同一个按 task 键的读
（`@DisallowConcurrentExecution` 按 JobDetail 互斥，而一个任务现在握着 cron + 每次点击多个 JobDetail，
跨不过去）、以及 `guard.tryAcquireLock` 作为集群兜底。按 task 键的读是 check-then-act，同一瞬间读到
的两个 fire 都能通过；要闭合需要按任务的锁，而 `(task_id, trigger_time)` 这个键表达不了。

**agent-spec 反查**：agent-service 拿 `task-{taskId}-{agentId}-{uuid}` 回问
`GET /api/admin/internal/agent-spec/{sessionId}`，admin 的 `resolveFromTask()` 用**与生成侧同一份**
`TaskSessionId.parse` 解出两个 id，然后**直接用其中的 agentId** 装配 spec——它不对 `agent_task` 发任何
查询（域在本服务之外也发不出来）。语法只认四段形态：`task-` 之后正好三个 `-` 分隔段，两个 id 都是
纯十进制正数，尾段非空（`TaskSessionId.of` 写入时会把 UUID 的连字符去掉，段数因此才成立）。段数不对、
带符号、超长的都直接拒，`resolveFromTask` 的异常信息带上期望格式与原串。
**这条链上没有东西核对「这个 agentId 就是那次任务真用的那个」**——`agent_task` 已不在 admin 库里，
那次比对不可执行。补偿是两件：生成侧同源（两个段出自同一次行读）+ 解析侧严格四段。可达集合因此是从
session id 里点名的任意 agent，而不限于「有任务的 agent」（仍需内部调用方身份）。

### 6.4 停止

停止是跨节点的，因为状态在 DB 行上而不是内存里：

```
前端点「停止」→ admin 转发 → scheduler 任一节点 stopTask(logId)
  ├─ requireOwnedLog：按所属任务的 creator 取那一行
  ├─ markStopping: 3→4（CAS，抢不到说明已定态）
  ├─ routerClient.sendCommand(sessionId, INTERRUPT)   ← 会话 id 存在日志行里，所以任何节点都能发
  │     它走 scheduler.command-timeout-seconds（10 秒），不共用执行的 300 秒：整条 /stop 必须落在
  │     admin 转发那 30 秒之内，否则用户先看到失败而状态仍不确定。超时算 Unanswered，不算 Missed
  ├─ 送达（有实例答「我这儿有在途执行」）→ 真正执行的那个线程收尾时看到 4，写成 5
  ├─ Missed（没有任何实例在推进它）→ **受理停止的节点当场** finalizeStopped 4→5，不等回收扫描
  └─ Unanswered（命令根本没送到）→ 行留在 4：谁也不知道执行是否还活着，交给执行节点或回收扫描
```

写 5 的因此是**两个**地方：执行线程（它拿到过真实结果）与受理停止的节点（它拿到过一个「未命中」答复，
那本身就是结论）。第二条边存在的理由是不能把「未命中」折叠成「已送达」——否则停在 4 的一行最后会被
`expireStale` 写成 `2 timeout`。

`/tasks/logs/{id}/stop` **不受 `scheduler.enabled` 门禁**：它不写任何 Quartz 对象，把行翻到 4 并请
router 中断一条活的会话，在一台拒绝*调度*新活的节点上同样正确；在这里拒绝会把已经在跑的执行困住。
一台关闭调度的节点因此可能留下停在 4 的行——那由回收扫描接管，`SchedulerHousekeepingJob` 是集群单例，
由开着调度的那台 fire。

admin 的转发**发给一个具体地址**（`HARNAX_SCHEDULER_URL` 逗号分隔列表的第一个，落到哪个副本由 Docker
对 `scheduler` 这个 service 名的 DNS 轮询决定）。它反过来也成立：受理停止的节点不是执行节点时，它写 5
的依据只有那个「未命中」答复，所以同一次停止若被广播到两台，第二台会答「我这儿没有在途执行」并当场
把行 4→5，把真实结果从执行线程手里抢走。一次转发因此不只是省事，它是正确性要求。

### 6.5 回收

`expireStaleExecutions()` 把 `status IN (3, 4)` 且已超过该行所属任务 `timeout_seconds`（存 0 或 NULL 时
回落到 `scheduler.timeout-seconds`）的 **1.5 倍**的行判成 `2 timeout`。判据取 1.5 倍而不是 1 倍：一次只是
在 agent-service 里排在别人后面的执行还活着，按 1 倍判就会在用户自己的超时那一刻被误判成僵尸；写进
`error_info` 的仍是任务自己配的那个值，因为那是用户能据以行动的数字。它是节点被 kill 之后不留永久
「运行中」僵尸的最后防线，对两条执行路径用同一套判据：手动执行也是一次 Quartz fire，被 SIGKILL 截断时
留下的同样是 `status = 3` 行 + `status = 0` 锁行，按同一套判据定成 2。两条路径都在 worker 线程上跑，
所以 400 秒宽限之内根本走不到回收。

调用点三处：启动加载、housekeeping 每 5 分钟一轮、以及 fire 前的并发判断
（`hasActiveRunningExecution`）。最后这一处限流成**每节点每 30 秒至多一次**
（`MIN_STALE_SWEEP_INTERVAL_MS`）：它是扫 `idx_status` 活跃端的 UPDATE，会和同一时刻插入新行的抢锁在
MySQL 上互撞，输的一方丢的是真执行；看不到活行的那一次 fire 根本不发起它。

「最后」是关键字：`2` 不是不可逆的。执行线程事后带着真实结果回来时走 `reclaimExpired` 覆盖成
`0/1`，或在自己读到过 4 的情况下覆盖成 `5`。回收扫描只能是猜测的兜底，不能当定论的出口。

**housekeeping 的一轮做四件事**（`SchedulerHousekeepingJob`，常量在类上）：

1. `expireStaleExecutions()`；
2. `cleanupOldExecutionLogs(LOG_RETENTION_DAYS = 90)`——按 `create_time`（有索引）而非 `start_time`
   删，没拿到 start 时间的行否则会永远留下；`status NOT IN (3, 4)` 是每一处写都带的同一道守卫：一行
   既然还读作「活」，它就属于某次可能还要被报告的执行，年龄不是论据；
3. `guard.cleanupOldExecutions(GUARD_RETENTION_DAYS = 7)`；
4. `guard.cleanupLeakedLocks()`——`agent_task_execution` 里超过两倍执行超时仍停在 `status = 0` 的行是
   一个持有者已经不在了的锁；不删掉它，`(task_id, trigger_time)` 这个唯一键会永久挡住那一次触发的重投。

清扫比自己的周期慢时不会叠第二轮：两条 retention DELETE 都在带
`@DisallowConcurrentExecution` 的 job 里跑。

store 是全集群共享的，housekeeping 这一轮因此是：清扫 job 是 store 里的一行，因此**全集群每 5 分钟只有
一个节点 fire 它**，不是每台各扫一遍。对运维的实际意思是：**只要集群里还有一台开着调度，回收就还在
跑**；一台 `SCHEDULER_ENABLED = false` 的节点没有也不需要私有的回收路径，它自己 `stopTask` 留在 4 的那
一行由 fire 到共享清扫 job 的那台收走。单节点且把开关关掉是唯一没人回收的情形。

## 7. 引擎层逻辑

### 7.1 对账

`TaskScheduleReconciler.reconcile()` 做一次 diff：

```
期望态 = agent_task 中 task_status = 1 AND active = 1 的 { taskId → (cron, concurrent) }
实际态 = QRTZ store 中 AgentTaskGroup 的 { jobId → (cron, job 类) }

  期望有、实际无                    → 注册
  实际有、期望无                    → 删除
  两边都有但 cron / job 类不一致     → reschedule
  两边一致                          → 不动（保留 NEXT_FIRE_TIME，不重置执行历史）
```

**cron 的比较忽略大小写**：`CronExpression` 的 String 构造器会把入参大写归一化，一条按用户输入存进
`agent_task` 的 cron 从 store 读回来是大写的。逐字比较会把每一条带字母的 cron 都判成「变了」，于是每轮
都重写整个组——正是这个类要永久消灭的行为，而且它还会喂脏漂移指标。

三处复用同一个函数：启动收敛、`/reload`（admin 转发 CRUD 后触发）、每 60 秒的集群清扫 job。两个节点
同时跑之所以安全，靠三件事而不是运气：注册走 `scheduleJob(replace = true)`（幂等交换）、
`SchedulerReconcileJob` 带 `@DisallowConcurrentExecution`（一轮慢过 60 秒不会叠第二轮）、以及
**先读 store 再读表**这个刻意顺序——一次 CRUD 若正好落在两次读之间，它会出现在表里而不在快照里，
于是这一轮重注册它（一次良性写）；反过来的顺序则会误删集群刚要的那条 schedule 并带走落在窗口里的 cron 边界。

单条任务注册失败记为漂移而不是抛出：一条不能用的 cron 不该让整轮没收。`ReconcileReport` 带
`converged`（`failedIds` 为空）与分方向的 drift 计数。

两个系统 job（reconcile 与 housekeeping）都注册在 `SchedulerSystemGroup`，**不是** reconcile 会收敛的
`AgentTaskGroup`——否则它自己删自己。清扫 trigger 也在共享 store 里，因此**间隔是集群级一个值**：一台
以不同 `scheduler.reconcile-interval-seconds` 启动的节点会把存库里的 trigger 改成自己的，最后启动的那台
说了算。这个键因此不该按实例改。

同理，reconcile job 自己就存在共享 store 里，所以**全集群每 60 秒只有一个节点执行**。

### 7.2 JobDataMap 只存 taskId

注册的唯一入口只放 `taskId` 字符串（`useProperties: true` 下非字符串是硬错误），
`AbstractAgentTaskJob` 在 fire 时回查 `agent_task` 拿当前 prompt / cron / status。这样任务定义的唯一
真相落回业务表，实体字段变更也不会让已注册任务读不回来，而「改了 prompt / cron 但没重新注册」这条
路径不存在。

一个运维结论：**被删掉的任务不会「还能火」**。回查拿不到行时这一发直接把 store 里那个孤儿 job 删掉
（`deleteJob` 从正在跑的 job 内部调用是安全的：`JobStoreSupport.triggeredJobComplete` 不会把它写回去），
下一轮 reconcile 做同一件事。所以「删除已生效但 store 里还留着那条 job」这个窗口最迟在一次到点触发后
自己关掉。

### 7.3 执行是同步的，且只有一条路径

`AbstractAgentTaskJob.run()` 在 Quartz worker 线程内跑完才返回。job 类是唯一告诉 Quartz「这次执行是活的」
的东西：把活交给后台线程，`execute()` 立刻返回，Quartz 把这一发归档为完成并删掉它的
`QRTZ_FIRED_TRIGGERS` 行——之后故障接管没有东西可接管，`waitForJobsToCompleteOnShutdown` 没有东西可等，
`@DisallowConcurrentExecution` 也没有活的执行可挡。

两条执行路径是同一类对象：没有绕过 Quartz 的人工执行通道，`/trigger` 与 `/run-once` 都只投 one-shot，
因此停机宽限、故障接管、并发互斥对两条同时成立；进程崩在 fire 之前由 Quartz 补火而不是静默丢失。
`SchedulerConfig` 里没有私有 `taskExecutor` 线程池——手动执行没有自己的池，`QUARTZ_THREAD_COUNT` 就是
节点闸口。

没有子类实现 `InterruptableJob`：真正的中断是 `stopTask` 发给 router 的 INTERRUPT 命令，广告一个
Quartz 级中断只会误导读者。

### 7.4 启动收敛与注册重试

`SchedulerServiceImpl` 的初始加载带重试：初始 2 秒、上限 60 秒退避，第 5 次尝试起升级为告警级日志。
两个系统 job 的注册走 `registerSweep` + `keepStoredIntervalOrMoveIt`：store 里已有的 trigger 间隔保持
不变，除非本次配置与它不同。

## 8. 跨服务边界与鉴权

### 8.1 admin → scheduler 的转发契约

| 项 | 做法 |
|---|---|
| 出站签名 | `InternalTokenProvider` + `AuthRestTemplateInterceptor` 签 `typ=internal` 的 HMAC JWT，密钥是 `harnax.auth.internal.shared-secret`（与 admin 同一个 `HARNAX_AUTH_SECRET`）。对面真的验：`InternalCallerInterceptor` 只放行验签通过且 `callerType == INTERNAL_SERVICE` 的请求，其余一律 401 |
| 出站头 | `Authorization: Bearer …`、`X-Caller-Id`（本服务的 `harnax.auth.service-id`，这里是 `scheduler`；admin 侧是 `admin`）、`X-Forwarded-User`、`X-Tenant-Id`。**不发 `X-Forwarded-Tenant`**，双向都不发——那三个里只有它是浏览器自己能加的头 |
| 地址 | `harnax.scheduler.url` 只取逗号分隔列表的**第一个**（其余仍解析、不报错，但没有代码路径会用）。落点等价，因为改的是所有节点共读的 store；**但落到的那台必须开着调度**：一台 `SCHEDULER_ENABLED = false` 的副本若仍注册在同一个 service 名下，这一发会按 DNS 的运气落到它身上并回 40903（reload 路径改判成 40902），用户看到的就是「任务保存了但没生效」。禁用实例要么摘掉，要么换 service 名 |
| 超时 | `forward` 与内部 POST 的读超时 30 秒（`SchedulerClientImpl`）。`stopTask` 那条 10 秒的上限就是从这 30 秒倒推的 |
| 响应 | admin 按 `JsonNode` 原样回传，不在 admin 再镜像一份 DTO——那会是同一份契约的第二套定义，漂移要等到客户端读到 `null` 才看得见 |
| 身份缺失 | `forwardedUser()` 只在**没有 authentication、`AnonymousAuthenticationToken`、或主体名为空**这三种情况下给出 `null`。内部共享密钥调用（agent-service 的 spec 查询）在 admin 侧的主体标记是 `internal-service`，它被映射成 `SYSTEM`——和 `UserContextUtil` 对同一主体的叫法一致——所以 `X-Forwarded-User` 带上 `SYSTEM`，不会写出一个谁都对不上的名字。scheduler 侧两个头都是可选的，缺了不拒——`InternalCallerInterceptor` 只按 bearer 的验签结果决定是否放行 |
| 幂等 | 中间态存在且有人兜底：`agent_task` 的写与 store 的收敛不是一个事务，提交后那轮 reconcile 若失败或没跑到，调用方拿 40902，store 与 `agent_task` 就处在这个差里，直到 60 秒的集群清扫收敛掉。这个差整个住在 scheduler 进程内，但它不会消失——进程之间本来就没有分布式事务。reconcile 是 diff 且幂等，所以重试一次 `/reload` 或等清扫，结果一样 |

### 8.2 scheduler 的入站门禁

`InternalCallerInterceptor` 注册在 `SchedulerWebConfig.addInterceptors`，路径模式来自
`InternalCallerInterceptor.PROTECTED_PATHS = ["/api/scheduler/**"]`——一个模式、**没有排除清单**，
读面也在内。`/actuator` 下的探测端点天然在外面（它们在 `/actuator` 前缀下，不是靠豁免列出的），
compose 的健康检查因此不受影响。

顺序是**先验签、后读头**：

1. `CallerContext.clear()`（在方法开头，让「一次拒绝之后没有身份残留」是这个方法的性质而不是容器的卫生习惯）；
2. 取 `Authorization`，必须能拆出非空 bearer；
3. `InternalTokenProvider.verifyToken`（签名 + 过期 + `typ=internal`），任何异常一律 401，不回显
   token 也不回显底层异常文案；
4. `callerType != INTERNAL_SERVICE` 单独拒绝。这一步是承重的，不是第 3 步的重述：把一枚带 `userId` 的
   登录令牌拿给 `verifyToken`，用同一个密钥签时它会答 `EXTERNAL_API`——一个把同一个 secret 指给两种
   角色是照部署文档做，不是破坏文档；
5. 之后才把 `X-Forwarded-User` 与 `X-Tenant-Id` 放进 `CallerContext`（ThreadLocal）。两个都可空：
   一次没有用户在其后的内部调用（比如执行期读属主）是正常调用，不是拒绝理由；`X-Tenant-Id` 不是数字
   就当没有。
6. 清场的唯一位置是 `afterCompletion`。`postHandle` 刻意不碰：它跑在 handler 之后且 handler 抛异常时
   会被跳过，在那里清理正是本对象要防的泄漏——线程会带着这个身份回到池子里，而本模块的任务属主与
   可见性判据读的就是这两个值。

拒绝的回答体是 `ResultVo`，与 `InternalAuthorizationInterceptor` 同形，客户端不必学第二种。

`harnax.auth.enabled` 保持 `false`：那个开关同时装配 `UnifiedAuthFilter`（外部 API Key、限流、
`@InternalOnly`），是另一个设计。另外即使打开，`UnifiedAuthFilter` 也**验不了用户的登录 JWT**——它用
`harnax.auth.internal.shared-secret` 验签，而 admin 签用户 token 用的是另一个 key `jwt.secret`。

**部署层仍是纵深的一部分**：compose 只 `expose: ["8084"]`、不发布宿主端口；`harnax-deploy/nginx.conf` 没有
`/api/scheduler/` 的 location，原位留着禁止回加的注释。门禁是第二层，不是唯一一层。

### 8.3 scheduler 的出站

| 目标 | 用途 | 参数 |
|---|---|---|
| router `POST /api/router/agent/chat` | 执行一次任务 | 读超时 `scheduler.timeout-seconds`（300）；凭 `scheduler.api-key`，留空时向 admin 现申请一把 SYSTEM 型 key |
| router `POST /api/router/agent/command` | 停止时发 INTERRUPT | 读超时 `scheduler.command-timeout-seconds`（10） |
| router `DELETE /api/router/agent/session/{id}` | 执行后清会话 | 上限 `scheduler.clear-session-timeout-seconds`（60），生效值 `min(this, timeout-seconds)` |
| admin 内部接口 | SYSTEM key 申请 | `scheduler.admin-url` + `scheduler.admin-secret` |

用 SYSTEM key 调 router 意味着**一次定时执行写下的 `api_call_log` 行是 `tenant_id IS NULL` 的行**：
这类凭证没有租户，`ApiCallLogFilter` 没有租户可盖章。而 `GET /api/router/monitor/call-logs` 带租户的
调用方只看自己那些行（谓词由服务端从凭证推出，该端点从不接受 `tenantId` 参数），两条规则合起来：**租户
用户在自己的 monitor 页面里看不到自己任务的调用记录**，那些行只在无租户的内部/运维调用方视图里。这是
「无法归属的行不能变成所有人可见」的另一面。排查一次定时执行为什么失败看的是 `agent_task_log`
（那里有 prompt、response、error_info 与耗时），不是 router 的调用日志。同 controller 的
`GET /api/router/monitor/instances` 刻意不做同样的收口：它答的是集群拓扑（host、port、心跳年龄、该
实例持有几个会话），不属于任何租户，是运维视图；停在一个实例「是什么」，不给谁的会话、更不给任何一次
会话的内容。

## 9. API 面

### 9.1 scheduler 自己暴露的（全部在 `InternalCallerInterceptor` 的入站门禁之后）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/scheduler/agent-tasks/page` | 分页列表，`Page` 外壳 |
| GET | `/api/scheduler/agent-tasks/{id}` | 详情 |
| POST | `/api/scheduler/agent-tasks` | 创建（请求体带 admin 已解析的 `agentName`，缺失回 400） |
| PUT | `/api/scheduler/agent-tasks/{id}` | 更新 |
| DELETE | `/api/scheduler/agent-tasks/{id}` | 删除 |
| POST | `/api/scheduler/agent-tasks/toggle/{id}?status=` | 启停开关（写前先做可见性读） |
| POST | `/api/scheduler/agent-tasks/{id}/start` | 启动调度 |
| POST | `/api/scheduler/agent-tasks/{id}/pause` | 暂停调度 |
| POST | `/api/scheduler/agent-tasks/{id}/trigger` | 立即执行（投 one-shot） |
| POST | `/api/scheduler/agent-tasks/logs/{logId}/stop` | 停止一次执行（先做属主读） |
| GET | `/api/scheduler/agent-tasks/{id}/logs` | 按任务 id 分页取执行日志（`Page` 外壳，带 `taskName` / `status` / 时间区间 / `keyword` 过滤） |
| GET | `/api/scheduler/agent-tasks/{id}/owner` | 属主读：返回 `AgentTaskOwner`（creator + tenantId）。它是冷路径，admin 的 `McpSessionOwnerResolver` 在构造 OAuth MCP 客户端时调，不在每条消息都走的 agent-spec 查询上 |
| POST | `/api/scheduler/tasks/{id}/trigger`、`/start`、`/pause`、`/run-once` | 调度动作；这四条与下面的 `/reload` 共**五处写入全部先过 `SchedulerController.requireEnabled`**（`trigger` / `start` / `pause` / `run-once` / `reload` 各自的第一行就是它），本节点 `scheduler.enabled = false` 时一律拒绝 |
| POST | `/api/scheduler/reload` | 跑一轮对账，未收敛即非 200；同样先过 `requireEnabled` |
| GET | `/api/scheduler/tasks/status` | `scheduledTaskCount` + `scheduledTaskIds`，当场读 store |
| POST | `/api/scheduler/tasks/logs/{logId}/stop` | 停止执行，**不受 `scheduler.enabled` 门禁**：它不写任何 Quartz 对象，只把日志行置 4 并请 router 中断活会话，两件事在拒绝调度写入的节点上一样正确；拒绝它反而会搁下已经在跑的执行 |

`updateStatus`（start / pause 走的那条 UPDATE）的 WHERE 只有 `id` 与 `active = 1`，不带 `creator`；
`/start`、`/pause`、`/trigger` 三条端点直接 relay 给 `SchedulerController`，没有 `toggle` 与 `stop`
那样的可见性前置读。净结果：任何一枚有效的内部转发（admin 侧只要登录就会签）都能按 id 启停或立即执行
别人的任务；读、改、删三条路径的属主判据不覆盖这里。

### 9.2 admin 侧的对外契约

`AgentTaskController`（admin）挂在 `/api/admin/agent-tasks`，12 个端点里 11 条走
`SchedulerClient.forward`，`/agents` 留在 admin 自己的域。路径、方法、`ResultVo` 外壳、`Page` 的 7 个键
（形状由 `PageContractTest` 钉住）、`records[*]` 的字段名与 `40901` / `40902` / `40903` 的含义由
scheduler 侧保持与客户端今天看到的一致。两个 case 刻意按原样转发不做本地判断：可见性门禁随数据一起
搬走了，转发的端点自己按 `X-Forwarded-User` 读并把不匹配答成「Agent task not found」。

## 10. 可观测性

| 信号 | 含义 | 位置 |
|---|---|---|
| `/actuator/health` 的 `scheduler` 指示器 | 本进程是否真的在调度。detail：`quartzStarted`、`instanceId`（集群下的真实实例名）、`storeType`、`scheduledJobCount`（**当场读 store**）、`lastReconcileAt`、可选 `lastReconcileError`。判据只有三条：Quartz 未启动 → DOWN；从未成功收敛过 → DOWN；最近一轮失败或留漂移 → DOWN。`scheduler.enabled = false` → UP 且只带 `enabled: false`。`storeType` 与 `scheduledJobCount` 是 detail，**不参与判据**；store 读不出来时计数是 -1 而不是一个像样的 0。`lastReconcileAt` 是**本节点**的最后一轮，不是集群的——60 秒清扫是单例，只有 fire 它的那台会盖这个字段，两节点集群上另一台的可以是几小时前的，任何规则（含告警）都不该读它的年龄 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/SchedulerHealthIndicator.kt` |
| `/actuator/health/liveness` | 只回答「进程活着吗」，**故意不含**上面的指示器：一轮收敛失败是数据库或任务表的问题，容器重启既不修好它、还会杀掉正在等的重试循环。compose 的健康检查与 `roll-scheduler.sh` 看的就是它 | 设计说明在 `SchedulerHealthIndicator` 类注释 |
| `scheduler.reconcile.rounds{outcome=success\|failure}` | **一轮对账的判决**，跑它的那台发一个样本。三个来客都算：启动收敛、admin 转发的 `/reload`、60 秒集群清扫。它不是重启计数器 | `SchedulerMetrics.recordReconcileRound` |
| `scheduler.reconcile.drift{action=add\|remove\|update}` | 这一轮修掉的漂移量，按方向分桶；没动的桶不发样本（健康集群每分钟那一轮什么都不发）。这是「CRUD 与 store 脱节」最早的显形点。**读法**：每轮只有一台发样本（清扫是集群单例，一次转发也只落一台），所以跨 `instance` 求和得到的是**集群总量**；把**单台**序列当集群读数才是会骗人的读法。告警设在「任一实例非零」上 | `SchedulerMetrics.recordReconcileDrift` |
| `scheduler.jobs.scheduled` | store 里 `AgentTaskGroup` 的 job 数，直接读 `QuartzJobInventory`。store 是共享的，这个表达式因此**就是集群视图**（两台的取值相同），按「本实例视图」设过的阈值要重读。store 读不出来时它是 NaN | `SchedulerMetrics` + `QuartzJobInventory` |
| `QRTZ_SCHEDULER_STATE` | 运维直查：有几个 `INSTANCE_NAME`、各自的 `LAST_CHECKIN_TIME` 有没有在推进，是判断「第二台到底进没进群」最快的办法。**别拿行数当副本数**：节点从不删自己那行（优雅停机也不删），行是**对端**在 `calcFailedIfAfter` 判它过期后、于 `clusterRecover` 里顺带删的。刚滚完 2 副本时看到 3~4 行是正常答案 | `SELECT INSTANCE_NAME, LAST_CHECKIN_TIME, CHECKIN_INTERVAL FROM harnax_scheduler.QRTZ_SCHEDULER_STATE;`（`roll-scheduler.sh` 结尾的回读用的就是这条） |

## 11. 配置项

`scheduler` 前缀（`harnax-scheduler/src/main/resources/application.yml`）：

| 键 | 默认值 | 作用 |
|----|--------|------|
| `scheduler.enabled` / `SCHEDULER_ENABLED` | `true` | 本节点是否接受调度工作。它同时决定 `spring.quartz.auto-startup`，所以 `false` 是「退出集群」而不是「热备」。它是 `SchedulerController.requireEnabled` 拒绝写入的唯一依据：`SchedulerFactoryBean` 照样启动，`init()` 照样填 Quartz context（共享 store 会把任意 fire 交给本机，含两个系统清扫，协作对象必须在场），所以没这道门就会注册一个既 fire 又跑在本机的 job，而调用方已经拿到 200 |
| `scheduler.reconcile-interval-seconds` | 60 | 集群清扫间隔。**集群级一个值**（trigger 在共享 store 里，最后启动的节点决定），不要按实例改 |
| `scheduler.instance-id` | 空 | 抢锁行的 `instance_id` 标签。compose 侧留空并注释了原因：所有副本拿到同一个值时，这一列会说出「一个节点」而实际是两个。Quartz 自己的集群身份不读这个键（`instanceId: AUTO`） |
| `scheduler.router-url` / `SCHEDULER_ROUTER_URL` | `http://localhost:8081` | 执行与停止的下游 |
| `scheduler.api-key` / `SCHEDULER_API_KEY` | 空 | 调 router 的 SYSTEM key；留空时向 admin 现申请一把 |
| `scheduler.admin-url` / `SCHEDULER_ADMIN_URL` | `http://localhost:8080` | key 申请与内部接口 |
| `scheduler.admin-secret` | `SCHEDULER_ADMIN_SECRET` | admin 内部接口密钥 |
| `scheduler.timeout-seconds` / `SCHEDULER_TIMEOUT` | 300 | 两个读者：router chat 的读超时，以及回收判定 `timeout × 1.5` 的基线。拆开会让回收扫描把只是慢的执行判成僵尸 |
| `scheduler.clear-session-timeout-seconds` | 60 | 执行后清会话的上限，生效值 `min(this, timeout-seconds)`。不共用 `timeout-seconds` 是因为那会让一次执行占住 worker 两倍超时。它进 `stop_grace_period` 的算式 |
| `scheduler.command-timeout-seconds` | 10 | `/stop` 路径上 INTERRUPT 的上限。唯一约束是用户等的那条链（admin 转发 `/stop` 用 30 秒），不进 `stop_grace_period` 的算式——`stopTask` 跑在请求线程上，从不跑在 Quartz worker 上。这里超时算 Unanswered，行留在 4 |
| `harnax.auth.enabled` | `false` | 打开它会同时装配 `UnifiedAuthFilter`（外部 API Key 接受、限流、`@InternalOnly`），而本服务只装 `InternalCallerInterceptor` 那道只认服务令牌的门；且 `UnifiedAuthFilter` 用内部共享密钥验签，验不了 admin 用 `jwt.secret` 签的用户令牌 |
| `harnax.auth.service-id` | `scheduler` | 本机签出的内部令牌的标签，也是出站 `X-Caller-Id`。入站路径上没有任何东西由它决定 |
| `harnax.auth.internal.shared-secret` | `HARNAX_AUTH_SECRET` | 双重职责：签出站令牌的 key，也是验 admin 转发 bearer 的 key。**两边必须同值**；compose 用一个变量喂所有服务，手工部署是唯一能配歪的地方 |
| `harnax.auth.internal.token-ttl-seconds` | 300 | 内部令牌有效期 |

`spring.*` 侧的 Quartz / Flyway / 数据源 / Hikari 逐项理由对着
`harnax-scheduler/src/main/resources/application.yml` 的 `spring.quartz` 块读；Quartz 相关环境变量：
`QUARTZ_JOB_STORE`、`QUARTZ_WAIT_FOR_JOBS`、`QUARTZ_THREAD_COUNT`、`SCHEDULER_QUARTZ_AUTO_STARTUP`、
`SCHEDULER_FLYWAY_ENABLED`（外层默认 `true`，嵌套回落 `FLYWAY_ENABLED`）、`DB_POOL_SIZE`、
`SPRING_DATASOURCE_URL`（compose 侧走独立的 `SCHEDULER_DB_URL`，**不复用** admin/agent/channel 共享的
`DB_URL`，否则一次改动带走三个服务）。
`management.endpoints.web.exposure.include: health,info,prometheus,metrics`，`probes.enabled: true`，
`springdoc` 由 `SWAGGER_ENABLED` 控制。

## 12. 部署与运维 checklist

镜像与拓扑：`harnax-deploy/Dockerfile.scheduler`（`eclipse-temurin:21-jre-alpine`、`TZ=Asia/Shanghai`、
非 root 用户、`EXPOSE 8084`、容器级 healthcheck 打 `/actuator/health/liveness`）；
`harnax-deploy/docker-compose.yml` 的 `scheduler` 段（`expose: ["8084"]`、无 `container_name`、
`stop_grace_period: 400s`、volume `scheduler-logs`、挂载 `/etc/localtime`、
`depends_on` mysql healthy + admin/router started）。`harnax-deploy` 是本仓库唯一可用的部署入口。

上线一次带本域改动的版本：

1. 确认 `harnax_scheduler` 库已建好并授权（`harnax-deploy/sql/init-databases.sql` 已含；该脚本只在 MySQL
   首次初始化空数据目录时执行，已有部署要手工补那两行 + `FLUSH PRIVILEGES`）。
2. `.env` 侧检查三件事：需要换库时才设 `SCHEDULER_DB_URL`（不设走 compose 默认的
   `harnax_scheduler`）、admin 与 scheduler **同值的 `HARNAX_AUTH_SECRET`**、**所有 scheduler 节点
   NTP 同步**。
3. 停 admin + scheduler 的**全部副本**。两条不能分批的理由：scheduler 的门禁只接受 internal JWT，不带
   签名的转发一律 401；任务会话 id 的形状由 `TaskSessionId` 严格解析（`task-` 之后正好三段、两个 id 是
   纯十进制正数、尾段非空），铸造侧是 `SchedulerServiceImpl`、解析侧是 admin 的
   `resolveFromTask`，铸造别的形状的调用方在那一侧直接被拒。这一步的「排空」判据是
   `agent_task_log` 里 `status IN (3,4)` 为 0——一次横跨停启时刻的
   执行永远定不了态：它的日志行写在停机前连的那个库，重启后的 scheduler 在自己的库里找它，
   `finishExecution` / `markStopping` / `expireStale` 全部 0 行。
4. 起**一个** scheduler 副本，让 Flyway 应用本模块的基线
   `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql`。
5. 核对表：三张 `agent_task*` + 11 张 `QRTZ_*` + `flyway_schema_history_scheduler` 一行。
   **`agent_task_log` 即使空着也必须在**——`selectTaskList` 自联它取 `lastRunStatus` / `lastRunTime`，
   缺表是列表页 500。
6. 让对账跑一轮（等 60 秒清扫，或建一个任务由 admin 转发触发），核对被调度的任务是 0 个：
   `/actuator/health` 的 `scheduledJobCount`，或 `GET /api/scheduler/tasks/status` 的
   `scheduledTaskCount`（后者要带内部 JWT）。非 0 = 这台连的还是别的库。
7. 起 admin，三客户端主链路各一遍：webui 列表 / 创建 / 编辑 / 启停 / 删除 / 立即执行 / 日志轮询、
   CLI `task list|get|create|trigger|stop`、小程序任务页。
8. 起第二副本（`--scale scheduler=2`），回读 `harnax_scheduler.QRTZ_SCHEDULER_STATE`：看两个
   `INSTANCE_NAME` 各自的 `LAST_CHECKIN_TIME` 每 15 秒前进，**别数行数**。
9. 逐台滚动用 `harnax-deploy/roll-scheduler.sh`：它持一把跨进程互斥锁跑完整个流程（一台台停—起，
   每起一台等新容器健康），结尾回读上面那条 SQL。**锁的键名的是集群而不是脚本所在目录**：取
   `COMPOSE_PROJECT_NAME`，未设时取「持有 compose 文件的那个目录的 basename」，小写并裁成 compose
   允许的字符集，再拼上 docker daemon 名——compose 的项目名规则就是这样，而本仓库三个 worktree 里
   那个目录都叫 `harnax-deploy`，用检出目录名当键会让一个集群持有多把锁。`LOCK_BASE_DIR` 默认 `/tmp`
   （刻意不是 `$TMPDIR`：那会让每一次 GUI 登录持有一个各自的用户级锁目录，cron 与 sudo 部署又落在另
   一个值上，两个不同的值就是两把锁、一个无人看守的集群），**每一个调用方都要传同一个值**。
   `GRACE` 与 `HEALTH_WAIT` 分别对应 `stop_grace_period` 与健康等待。
10. 处理 `harnax_admin` 库里的本域同名表：admin 的基线既不建这三张 `agent_task*` 也不删它们，所以没有任何
    一步会自动收口——改造前就建好、并且把 `QUARTZ_JOB_STORE=jdbc` 指向过它的安装，库里可能还留着三张
    `agent_task*` 和 11 张 `QRTZ_*`。本服务的 scheduler 不连那个库，本域的运行读路径没有任何一条指向那里，
    删除由运维就地执行。
11. 回滚：把 scheduler 的数据源指回 `harnax_admin` + `QUARTZ_JOB_STORE=memory` +
    `SCHEDULER_FLYWAY_ENABLED=false`（`application.yml` 与 compose 都读这个键），并明确接受
    「数据源指向 `harnax_scheduler` 期间新建或改过的任务不会跟着回来」——那个库一旦被写过，回滚就不是一次
    `revert`。回到 `harnax_admin` 库还要先让那三张表在那里存在（admin 的基线不建它们）：运维手工建表最干净，
    临时把 `SCHEDULER_FLYWAY_ENABLED` 开成 true 让本模块的基线在那个库上建出来也行，前提是该库的
    `flyway_schema_history_scheduler` 台账与实际 schema 对得上，否则 `validate-on-migrate` 会先拦下来。

## 13. 明确不做与边界

| 边界 | 现状 |
|---|---|
| 用户令牌直接打 scheduler | 不支持。scheduler 的门禁只认服务令牌，最终用户身份只经 admin 的两个转发头进来。复刻 router 的 `X-Api-Key` + 远端校验 + 手写租户判断那条路要三个客户端同时改发送的凭证 |
| `harnax.auth.enabled = true` 的完整自鉴权 | 不在本服务范围内。那一套会同时引入外部 API Key 接受、限流与 `@InternalOnly` 模型 |
| `agent_task_log` 的响应脱敏 | 未做。日志的读经过所属任务的可见性 JOIN，但 `prompt` / `response` / `error_info` 仍是全文下发 |
| 按角色的细粒度授权 | 没有。admin 侧本域只要求「已登录」（`SecurityConfig` 的兜底），无角色/权限码；`tenant_id` 只在 create 时写一次，MyBatis 层不设租户拦截器（无任何自动过滤）。属主条件是目前唯一的隔离手段，它比租户隔离更弱，且不覆盖 `/api/scheduler/agent-tasks/{id}/start`、`/pause`、`/trigger` 这三条 |
| 跨库读 | 没有。本服务只有一个数据源 |
| `SCHEDULER_ENABLED = false` 当热备用 | 不是用途。`false` 让整个部署退出集群，compose 用一个插值喂所有副本正是为了防止把它当按实例的开关；热备由 `roll-scheduler.sh` 承担 |
| 一个实例私有的人工重建路径 | 没有。禁用实例留在 4 的行由 fire 到共享清扫 job 的那台收走 |
| Quartz 级中断 | 不做：没有任何 job 类实现 `InterruptableJob`，中断只经 router 的 INTERRUPT 命令 |
| `agent_task_execution` 的两条清扫索引在真库上确实被走 | 没有观察过：要 `EXPLAIN` 在真 MySQL 上看，而真 MySQL 只在集成测试的 Testcontainers 里存在 |
| 集成测试 | 五个 IT 类（`ClusterSingleFireIT`、`ReconcileConvergenceIT`、`HousekeepingGuardIT`、`AgentTaskOwnerScopeIT`、`AgentTaskMapperSemanticsIT`）在 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/it/`，走 `mvn -Pintegration-test verify -pl harnax-scheduler`，被 `-Pintegration-test` 挡在默认 reactor 之外，需要 Docker 守护进程。「kill 其一、另一台在 22.5~52.5 秒内接管」没有任何自动化在证明，只能手工 `docker kill` 一次并回读 `QRTZ_SCHEDULER_STATE` |

## 14. 排障速查

| 症状 | 先看 | 结论怎么读 |
|---|---|---|
| 任务到点没跑 | `/actuator/health` 的 `scheduledJobCount` 与 `scheduler.jobs.scheduled` | 0 → 这一条根本没进 store，看 `lastReconcileError` 与 `scheduler.reconcile.drift` |
| 保存了任务但没生效，接口回 40902 | 转发落到的那台是否开着调度 | 一次转发只落一台；`SCHEDULER_ENABLED = false` 的实例会答 40903 并被改判成 40902。等一轮 60 秒清扫，或把禁用实例摘掉 |
| 第二台没进集群 | `QRTZ_SCHEDULER_STATE` 里两个 `INSTANCE_NAME` 的 `LAST_CHECKIN_TIME` 是否都在推进 | 只有一行在动 = 另一台连的不是同一个库或同一个 `instanceName`；行数比副本数多是正常答案 |
| 节点被 kill 后任务接管慢 | `QRTZ_SCHEDULER_STATE.LAST_CHECKIN_TIME` 与 `JobStoreSupport.calcFailedIfAfter` 的算式 | 22.5 秒是最好情况，52.5 秒是对端 check-in 也迟到时的最坏情况；「15 秒就接管」不是这套配置的行为 |
| 一次执行永远停在「运行中」 | `expireStale` 的 30 秒限流与 5 分钟一轮 | 上限是 `timeout × 1.5` + 一轮清扫的等待；单节点且 `SCHEDULER_ENABLED = false` 时没有人回收 |
| 重启打断了一次执行 | 容器的 `stop_grace_period` 与 `QUARTZ_WAIT_FOR_JOBS` | 400 秒是从 `timeout + clear + 20 + 12` 推出来的；两者任一被改动而另一个没跟上，就会看到停在 3 的行 |
| 手动执行返回成功但日志列表是空的 | scheduler 日志里的 `skipping this fire` / `already being executed by another instance` | 两道 fire 时闸口在插日志行之前，一次被挡掉的执行不留痕迹；200 只说明 one-shot 进了 store |
| 立即执行时并发被拒 | `runTaskOnce` 的 `blocksManualRun` | 40901 就是「本任务已有活执行且禁重叠」，该轮询而不是重试 |
| 用户点了停止但状态不动 | `stopTask` 的三分支：送达 / Missed / Unanswered | Unanswered 时行留在 4，等执行节点或回收；先查 router 的 `/api/router/agent/command` 是否可达、是否超过了 10 秒 |
| 停止之后又看到一次真实结果 | 该行的 `error_info` 是否带 `(completed after auto-expiry)` | 执行线程迟回来走 `reclaimExpired` 覆盖了回收的猜测；若那次停止从记录上消失，那是 `expireStale` 把 4 覆盖成 2 之后又被 `reclaimExpired` 写回的结果 |
| 每次重启后所有任务都被重写一遍 | `scheduler.reconcile.drift{action=update}` 在每分钟那一轮是否非零 | 先看 `TaskScheduleReconciler` 的 cron 比较是否忽略大小写，再看 `TaskQuartzRegistrar.jobClassFor` 的三处调用是否给出同一个答案 |
| 收不到 401 但接口本该拒绝 | bearer 是不是 `typ=internal` | 用登录令牌或别的密钥签的令牌一律 401；`X-Caller-Id` 是 `admin` 而签名密钥不同也 401 |
| 改了任务但属主规则没生效 | 这一跳是否带上了 `X-Forwarded-User` | 没有那个头 → `CallerContext.username` 为 `null` → 写路径的 `requireUsername()` 直接拒 |
| monitor 页面看不到定时执行的调用记录 | `api_call_log.tenant_id IS NULL` | 执行用的是 SYSTEM key，这类凭证没有租户，`ApiCallLogFilter` 无租户可盖章，而带租户的调用方只看自己那些行——设计行为；查执行本身看 `agent_task_log` |
| 已删任务的名字建不回来 | `uk_name` 与 `selectByName` 的 `active = 1` | 软删的行仍占着这个名字，而重复名预检查查不到占用者，最终由 INSERT 撞键报 500 |

## 15. 关键文件索引

| 关注点 | 文件 |
|---|---|
| 服务入口与 bean 装配 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/SchedulerApplication.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/config/SchedulerConfig.kt` |
| Web 层（门禁注册 + 异常外壳） | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/config/SchedulerWebConfig.kt` |
| 入站门禁与身份上下文 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/support/InternalCallerInterceptor.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/support/CallerContext.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/support/SchedulerBizException.kt` |
| CRUD 规则 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt` |
| 调度与执行主服务 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/SchedulerService.kt` |
| 对账 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/TaskScheduleReconciler.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/SchedulerReconcileJob.kt` |
| store 写入唯一入口 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/TaskQuartzRegistrar.kt` |
| 一次 fire 的全部判定 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AbstractAgentTaskJob.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskJob.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskNonConcurrentJob.kt` |
| 回收 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/SchedulerHousekeepingJob.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/AgentTaskExecutionGuard.kt` |
| 日志查询与可见性 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskLogQueryServiceImpl.kt` |
| HTTP 端点 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskOwnerController.kt` |
| 下游调用 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/CommandDelivery.kt` |
| 健康与指标 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/SchedulerHealthIndicator.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/QuartzJobInventory.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/SchedulerStatus.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/metrics/SchedulerMetrics.kt` |
| 实体与语句 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTask.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskExecution.kt`、`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml`、`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml`、`harnax-scheduler/src/main/resources/mapper/AgentTaskExecutionMapper.xml` |
| 响应外壳 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/Page.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskResponse.kt`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskLogResponse.kt` |
| schema | `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql`（本域全部 14 张表的唯一定义）、`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`（其中没有本域的表） |
| 运行配置 | `harnax-scheduler/src/main/resources/application.yml` |
| 任务会话 id 语法 | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/session/TaskSessionId.kt` |
| admin 侧鉴权 + 转发 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/SchedulerClient.kt` |
| admin 侧 agent-spec 反查 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`（`resolveFromTask`） |
| 任务会话的 MCP 身份 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt` |
| 部署 | `harnax-deploy/Dockerfile.scheduler`、`harnax-deploy/docker-compose.yml`、`harnax-deploy/roll-scheduler.sh`、`harnax-deploy/nginx.conf`、`harnax-deploy/sql/init-databases.sql` |
