# harnax-scheduler 部署文档

## 服务概述

`harnax-scheduler` 是**定时任务域本身**：`agent_task` / `agent_task_log` / `agent_task_execution` 三张表归它（库是自有的 `harnax_scheduler`），任务的 CRUD 与业务校验（cron 合法性、名称唯一、属主与可见性规则）在这里，执行日志的查询在这里，按 cron 到点后经 router 发起一次智能体执行、并回写执行状态也在这里。它不校验终端用户的 JWT（那是 admin 的 `JwtAuthenticationFilter`，admin 校验完带身份转发过来），也不跑智能体（那些在 agent-service）。

一句话结论先说：**既定形态是两个调度实例同时开着 `SCHEDULER_ENABLED=true`**。Quartz 跑真正的 JDBC 集群 store（`isClustered=true`，11 张 `QRTZ_*` 表是全集群唯一的调度真相），一次 cron 触发在集群里只投递一次、只由一个节点执行；一台死了另一台接管。怎么核对与怎么滚动见「双实例与逐台滚动」，这是本文件最重要的一节。

端口默认 `8084`（`SCHEDULER_PORT`）。compose 里**不发布宿主端口**，只有 `expose: ["8084"]`：admin 走容器网络 `http://scheduler:8084`。

---

## 环境依赖

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK | 21 | |
| MySQL | `harnax_scheduler` 库（本服务自有） | **本服务独占这个库**：11 张 `QRTZ_*` 集群表 + `agent_task` / `agent_task_log` / `agent_task_execution` 三张业务表都在这里面，表结构全部由本服务的 Flyway 建（`V1__quartz_tables.sql` + `V2__agent_task_domain.sql`，记在自有的 `flyway_schema_history_scheduler`），没有任何别的工具往这个库写表。所以迁移开关（`SCHEDULER_FLYWAY_ENABLED`，未设时回退 `FLYWAY_ENABLED`）默认 `true` 且**必须保持开**——关掉就一张表都没有，`QUARTZ_JOB_STORE=jdbc` 的节点直接起不来。库与授权由 `docker-new/sql/init-databases.sql` 预建（存量部署要手工补那两行，那脚本只在 MySQL 首次初始化空数据目录时执行）。历史注：这三张业务表原本就在 `harnax_admin` 里，发布 1 建的那 11 张 `QRTZ_*` 也临时落在同一处（那是当时的中间态，spec 的修正 C），到发布 2 才一起搬进本库——见「发布 2 切口」 |
| router | 必须可达 | 执行入口 `SCHEDULER_ROUTER_URL` |
| admin | 必须可达 | 会话管理与系统 Key 获取 |
| Redis / MinIO | 不需要 | |
| 时钟同步 | **必须** | 集群靠比对 `QRTZ_SCHEDULER_STATE.LAST_CHECKIN_TIME` 判断节点死活，各节点时钟都取自 MySQL 之外自己的 JVM。宿主机 NTP 偏差必须 < 1s：偏差超过 checkin 间隔会把活节点判死并触发误抢 |

---

## Quartz 存储模式（决定能不能多实例）

`QUARTZ_JOB_STORE` 默认 `jdbc`：调度状态在共享的 `QRTZ_*` 表里，`instanceName=HarnaxScheduler` 是集群名（两实例必须同值，它是每张 `QRTZ_*` 表的主键首列 `SCHED_NAME`），`instanceId=AUTO` 把节点区分开。

| 模式 | 定位 | 后果 |
|---|---|---|
| `jdbc` | **当前默认与目标形态**：`V1__quartz_tables.sql` 由本服务的 Flyway 建表，`isClustered=true` + `clusterCheckinInterval=15000` + `acquireTriggersWithinLock=true` | 多实例安全：一次触发全集群只有一个节点抢到；故障接管与 misfire 补偿都由引擎负责 |
| `memory` | **逃生门，不是运行形态**：本地无库启动、以及回滚（见下一节） | 该实例**不是集群成员**：它读不到也写不进共享 store，自己按自己的 cron 各 fire 一次。同一发 cron 被两台同时 fire 时**全集群只执行一次**：每一发（cron 与 one-shot 都是）都要过 `AbstractAgentTaskJob` 的 `AgentTaskExecutionGuard.tryAcquireLock(taskId, triggerTime)`，`uk_task_trigger(task_id, trigger_time)` 只让一台赢，`agent_task_log` 也就一行（这正是集群化前的多实例形态，见 `prod_doc/agent-task-scheduler.zh-CN.md` §2.1）。真正的代价是三样：赢家由一次 INSERT 抢出来、不是由调度器决定；这台**没有故障接管也没有 misfire 补偿**，它停机期间错过的触发永久跳过；它的 schedule 是私有的，一次只落到 jdbc 那台的 CRUD 会让两台跑着**不同的 cron 表达式**——不同表达式就是不同触发时点，也就不同 `trigger_time`，这才是真会成对写 `agent_task_log` 的那条路径 |

`org.quartz.jobStore.class` 故意**不写**：Boot 注入 DataSource 后会强制覆盖成 `LocalDataSourceJobStore`，写死 `JobStoreTX` 反而连不上 Spring 管理的数据源。

`SCHEDULER_INSTANCE_ID` 留空时启动自动生成，供执行守卫（`AgentTaskExecutionGuard`）标记「这条执行是谁发起的」；它是同库多实例之间的写入归属标记，**不是**跨实例去重锁，也不是 Quartz 的集群身份（后者是 `instanceId=AUTO`）。compose 里把它留空成**一份插值**，所以给所有副本配同一个值只会让 `agent_task_execution.instance_id` 在两台上说同一句话——保持留空。

### 回滚（回到内存 store）

「离开集群」这一条退路在仓库里只有这里写全（数据源那一步见「发布 2 切口」的回滚小节，两个开关是同一套）。两个开关一起动，只动一个会把节点留在集群外却没有 store：

```bash
QUARTZ_JOB_STORE=memory             # 本实例退出集群
SCHEDULER_FLYWAY_ENABLED=false     # compose 与手工部署是同一个键；未设时它退回 ${FLYWAY_ENABLED:true}
```

`QRTZ_*` 表与其中的数据**保留不删**：行是可再生数据（reconcile 会按 `agent_task` 重建全部 job），所以回滚不需要迁数据，重新开启集群也不需要。代价写在上一节的表里——memory 节点不是集群成员，两实例里退掉哪台，哪台就只剩「接收 admin 转发的 HTTP 面」这一个作用。

## 双实例与逐台滚动

副本数**不由 compose 决定**：`docker-compose.yml` 里 scheduler 既没有 `deploy.replicas`，也**故意没有 `container_name`**（固定名唯一，Docker 会直接拒绝 `--scale`）。数字只从命令行来，一共三处会写它：`roll-scheduler.sh`（`SCHEDULER_REPLICAS`，默认 2）、`deploy-all.sh` 的冷启动 `up -d --scale scheduler=$SCHEDULER_REPLICAS`、以及你自己手敲的 `up`（`build.sh` 收尾打的那条、与 `docker-compose.yml` 头部 usage 里那条，都只是这第三处的样子——两处都已带 `--scale`，别再删掉它）。**任何不带 `--scale` 的 `up` 都是在要求 1 个副本，compose 会把服务缩回一台**——所以从脚本之外拉起这个服务时，`--scale` 必须带上。

```bash
docker-compose -f docker-new/docker-compose.yml up -d --scale scheduler=2 --no-recreate scheduler
```

发布新版本走 `docker-new/roll-scheduler.sh`（`deploy-service.sh scheduler` 的第 4 步就是它）。它先补齐到 `SCHEDULER_REPLICAS`、再逐台 `docker stop -t <grace>` + `rm` + `up --no-recreate` 换掉，全程集群里至少有一台在跑。

**禁止对 scheduler 用 `--force-recreate`**：`up -d --force-recreate scheduler` 一次重建该 service 的**所有**副本，等于最长 400s（一台容器从 SIGTERM 到被 SIGKILL 的宽限）全集群无调度。这期间 `QRTZ_TRIGGERS` 里堆起来的过期触发会走 misfire 路径，而 `concurrent=0` 的任务用的正是 `withMisfireHandlingInstructionDoNothing`——**堆起来的触发被直接丢弃**，发布于是静默跳过本该跑的定时任务，和 400s 宽限「绝不丢执行」的初衷正好相反。同理，`SCHEDULER_REPLICAS=1` 的滚动会被脚本拒绝：一台都停的话，就没有第二台可接管了。

**`docker-new/deploy-all.sh` 是同一条越界里更长的那一档，它照做不误**：第 5 步 `down` 停掉全部副本，第 6 步才 `up -d --scale`，中间要过 mysql 的健康门（`healthcheck` 最坏 10s×5）再起一台 JVM——全集群无调度的窗口比一次 `--force-recreate` 只长不短，堆在 `QRTZ_TRIGGERS` 里的那些发同样按 DoNothing 丢弃。它不做成滚动形态是刻意的：这是一次全新集群的冷启动（所有服务都要换镜像），逐台滚动那条路径需要「有副本在跑时换镜像」这个前提，此刻并不成立，`roll-scheduler.sh` 也不负责拉起 redis/minio/mysql。所以这里的规则是运维的而不是代码的：**只在安静时段跑 `deploy-all.sh`**；如果这次只动了 scheduler 的镜像，就走 `deploy-service.sh scheduler`（它以 `roll-scheduler.sh` 收尾，全程至少一台在跑，不丢触发）。脚本在 `down` 前会把这句话打一遍，`roll-scheduler.sh` 的那把滚动锁也**故意不覆盖** `deploy-all.sh`——一把锁拦不住一次设计上就要清空整栈的部署。

集群成员的直接读数是 `QRTZ_SCHEDULER_STATE`（在 `harnax_scheduler` 库，本服务的数据源就指那里）：

```sql
SELECT INSTANCE_NAME, LAST_CHECKIN_TIME, CHECKIN_INTERVAL FROM harnax_scheduler.QRTZ_SCHEDULER_STATE;
```

- 每行是一个成员，`LAST_CHECKIN_TIME` 是 unix 毫秒，存活节点的这一列每 15s 前进一次。**看这一列有没有在动**，比数行数可靠。
- **刚滚完 2 副本时看到 3~4 行是正常的**：一个节点优雅停机**不会**删掉自己那行（Quartz 2.5.2 的 `JobStoreSupport.shutdown()` 只停集群线程并关连接池），行是由**对端**在判定它心跳过期后、于 `clusterRecover` 里顺手 `deleteSchedulerState` 清掉的。判定线 = 死节点行的 `LAST_CHECKIN_TIME` + `CHECKIN_INTERVAL`(15s) + Quartz 硬编码的 7500ms ≈ **22.5s**，而这条判据只在存活节点自己的 ClusterManager 线程醒来时求值（间隔同样是一个 checkin 周期 15s），所以清行落在 **22.5~37.5s**；对端那一轮若被慢库拖过一整个周期，`max()` 取到的是它自己实际的间隔，判据线再外推 15s → 最坏 **52.5s**。想行数等于副本数，一分钟后再跑一次这条 SQL。

故障接管的窗口就是上面那串算式：**约 22.5~52.5s**（22.5 = 15000ms checkin 间隔 + 7500ms 常量；+15s 对端轮询粒度；+15s 对端自身 checkin 滞后）。写在这里而不是写一个约数，是因为这三个数都来自 `application.yml` 与 Quartz 源码，改 `clusterCheckinInterval` 就是要回到这段重算。它还解释了为什么单节点死掉不丢触发：接管最坏 52.5s，仍在 `misfireThreshold: 60000` 之内，那一发根本不会被判成 misfire——而 400s 的全集群下线一定越过它。

**约束：`SCHEDULER_ENABLED=false` 的实例不要继续注册在同一个 compose service 名下。** admin 现在只发**一次**转发（`SchedulerClientImpl`，共享 store 之后广播已失去意义），Docker 的 DNS 轮询会把这一发落到任意一个同名副本上；落到一台关了调度的实例上，用户就看到 40903（`CODE_SCHEDULER_DISABLED`），而且是**按运气出现的**——重试一次可能就通了。更糟的是 reload 路径：那台实例答的 40903 会被 admin 改判成 40902（只有它的文案留在 message 里），运维读到的意思是「已存库但没人调度它」。要么把这台从 service 里摘掉，要么给它另一个 compose service 名（另一份 `docker-compose.*.yml`），让 `HARNAX_SCHEDULER_URL` 只指向开着的实例。compose 侧做不到按副本区分——`SCHEDULER_ENABLED` 是一份插值、对所有副本生效，所以这只能是拓扑规则。

`SCHEDULER_ENABLED=false` 也不意味着这台完全 inert，需要知道的边界：

- 它**不进集群**：`spring.quartz.auto-startup` 跟着 `scheduler.enabled` 走。这不是保守，是因为一个开着但拒跑的成员会照样 acquire 触发、然后拒绝执行——那一发被吃掉了，不是交出去；one-shot 丢了就是永远丢了。留在集群外是唯一不吃工作的拒绝方式。
- 它仍然接受 `/tasks/logs/{id}/stop`（那是一行状态改写，不是调度写），仍然注册 housekeeping 清扫，也**仍可能被共享 store 分到一次 fire**（比如上面那条拓扑规则被违反时）——那次 fire 会在 `AbstractAgentTaskJob` 的 enabled 检查处让给别的节点。
- 僵尸回收是**集群级**的：清扫 job 只有一行、每 5 分钟由一个节点 fire，所以只要集群里还有一台开着，回收就还在跑；一台 `SCHEDULER_ENABLED=false` 的节点没有、也不需要私有的回收路径。
- 它会拒绝 `/reload`、`/tasks/{id}/start`、`/pause`、`/trigger`、`/run-once`，全部回 40903。

## 优雅停机

- 本模块 compose 有 `stop_grace_period: 400s`，`application.yml` 显式写了 `spring.quartz.wait-for-jobs-to-complete-on-shutdown: ${QUARTZ_WAIT_FOR_JOBS:true}`（Boot 的默认是 `false`，必须写出来）。**400 是一串求和的上取整**：chat 读超时 300 + `clearSession` 上限 60 + 两次调用各 10s 的 connect 预扣 20 + 定态写回 8 + Spring 关停钩子 4 = 392。逐项推导写在 `docker-new/docker-compose.yml` 的 scheduler 段注释里，改 `SCHEDULER_TIMEOUT` 或 `SCHEDULER_CLEAR_SESSION_TIMEOUT` 都要回到那里重算，别让注释变成谎话。
- 宽限和 `waitForJobsToCompleteOnShutdown` 必须成对：等任务的前提是内核没先 SIGKILL；`docker stop -t` 的默认 10s 会把一次跑到一半的执行切成 `agent_task_log` 的 `status=3` 与 `agent_task_execution` 的 `status=0`，等 housekeeping 最坏 2× 超时后才回收。
- **`roll-scheduler.sh` 里有一份同一个数**（`SCHEDULER_STOP_GRACE`，默认 400），滚动时逐台花掉这个窗口；两处要一起改——滚动超时短于 `stop_grace_period` 等于在 mid-run 上 SIGKILL，正是这个宽限要挡的事。
- **保护范围 = 本模块的全部执行路径**：cron 与手动执行现在是同一类对象。`/tasks/{id}/trigger` 与 `/tasks/{id}/run-once` 都只往共享 store 投一枚 one-shot job（组 `AgentTaskGroup_ONCE`、非 durable、`startNow()`），由 Quartz worker 就地跑完，所以 `waitForJobsToCompleteOnShutdown` 有东西可等、这 400s 对两条路径同样生效。两副本下仍然猜不出是哪台忙——admin 那一发落到 DNS 选中的任一台，所以滚动必须假设两台都可能忙，逐台给满宽限。
- **点一下拿到 200，不等于会留下一行执行记录**。投递成功只代表那枚 one-shot 进了 store；fire 时还要过 `AbstractAgentTaskJob` 的两道判断——`concurrent=0` 且本任务已有活着的执行（重叠闸口）、`tryAcquireLock` 输给另一个节点（集群锁）——任一条命中就直接返回，而这两处**都在插 `agent_task_log` 那行之前**。于是 webui 的"执行成功 → 打开日志列表"可以合法地是空列表，这不是前端坏了。真相在 scheduler 的日志里：`skipping this fire` / `already being executed by another instance`。
- **容量规则（运维必读）**：一次手动执行占用 `QUARTZ_THREAD_COUNT`（默认 10）个 worker 之一，直到跑完；`SimpleThreadPool` **没有队列**，worker 全忙时到期的 cron 只能干等，等到越过 `misfireThreshold: 60000` 就变成一次 misfire，而 `concurrent=0` 的任务用的正是 `withMisfireHandlingInstructionDoNothing`——**那一发定时任务被跳过，不是延后跑**。所以：**不要用 1~2 个 worker 跑 scheduler**。worker 数是"同时在跑的执行数"和"cron 不被饿死"两件事的同一个余量，手动执行混进来之后，余量必须留在 cron 这一侧。

`QUARTZ_THREAD_COUNT` 默认 `10`，是**每节点**并发执行的上限（2 实例 = 全集群最多 20）；设计文档明确**不再上调**（提高会直接放大对 router / agent-service 的下游压力，而 agent-service 仍是单实例）。它与 `DB_POOL_SIZE` 之间是下界关系，见数据源一节。**它同时也是 cron 的余量**：发布 3 之后手动执行与定时执行共用这批 worker（没有独立的手动池），所以这个数只有上限、没有下调空间——上面那条"1~2 个 worker 不算可用配置"的下限就是从这里来的。

---

## 环境变量说明

### 基础与端口

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SCHEDULER_PORT` | `8084` | HTTP 端口（compose 不发布到宿主） |
| `SCHEDULER_ENABLED` | `true` | 本实例是否参与调度。**两个副本都该是 true**；`false` 的实例不进集群，因此不该继续留在同名 service 里（见上一节的约束）。compose 里这是一份插值，对所有副本同时生效——没有「这台开、那台关」的配法 |
| `SCHEDULER_INSTANCE_ID` | 空（自动生成） | 执行归属标记，保持留空 |

### 数据源

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/harnax_scheduler?...` | 本服务自有的库：`QRTZ_*` 与三张 `agent_task*` 表都由它建、由它读写。指回 `harnax_admin` 只有一种合法用途——切口后的回滚（见「发布 2 切口」） |
| `SCHEDULER_DB_URL` | 空（用上面的 yml 默认值） | **compose 侧的连接串只由它决定**。它故意不是 admin / agent-service / channel-service 共用的那条 `DB_URL`：那三个服务靠 `DB_URL` 打同一句 `harnax_admin`，scheduler 复用同一变量的话，改一处就带走三个不该动的服务 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `root` / `123456` | compose 侧走 `DB_USERNAME` / `DB_PASSWORD`（与 admin 同一个 MySQL 用户，它对两库都有权限，见 `init-databases.sql`） |
| `DB_POOL_SIZE` / `DB_POOL_MIN_IDLE` | `30` / `3` | Hikari。30 是按下界选的：≥ `QUARTZ_THREAD_COUNT`(10) 个 worker（每个在一次 fire 里占一条连接）+ 业务查询 + 集群 checkin，全走这一个池。**`QUARTZ_THREAD_COUNT` 与它要一起动**——只加 worker 不加池不会多出容量，只是把等待从调度线程挪到 30s 的 connection-timeout 上。与 admin 的 20/5 不同 |
| `FLYWAY_ENABLED` | `true` | 本服务的迁移**必须开**：关掉就一张表都没有。它建的是本库的全部两张脚本（`V1__quartz_tables.sql` 的 11 张 `QRTZ_*` + `V2__agent_task_domain.sql` 的三张业务表），历史记在自有的 `flyway_schema_history_scheduler`——这个库里只有这一个迁移工具，admin 的 `flyway_schema_history` 在 `harnax_admin`，两边不再同库。它同时是调度节点迁移的**回退位**而不是决定位——`application.yml` 读的是 `${SCHEDULER_FLYWAY_ENABLED:${FLYWAY_ENABLED:true}}`，所以只要 `SCHEDULER_FLYWAY_ENABLED` 设了值，改这个 admin 同名的键就不起作用（它原本就是防着「手工恢复时顺手改了 admin 那个值，把调度节点停了迁移」） |
| `SCHEDULER_FLYWAY_ENABLED` | `true` | 本服务迁移的决定位，compose 与手工部署同一个键：`application.yml` 的 `spring.flyway.enabled` 外层就是它。compose 里另有 `SPRING_FLYWAY_ENABLED: "${SCHEDULER_FLYWAY_ENABLED:-true}"`，那是一条显式 env 覆盖、优先级仍高于 yml，两条路径因此不会分叉成两个开关。`=false` 是回滚的逃生门（见「回滚」一节） |

### 调度与下游

| 变量 | 默认值 | 说明 |
|---|---|---|
| `QUARTZ_JOB_STORE` | `jdbc` | 见前两节。`=memory` 只用于本地无库启动与回滚，代价是该实例不进集群 |
| `QUARTZ_THREAD_COUNT` | `10` | 每节点并发执行上限，不建议上调；上调必须同时上调 `DB_POOL_SIZE` |
| `QUARTZ_WAIT_FOR_JOBS` | `true` | 见「优雅停机」，必须与 `stop_grace_period` 配对 |
| `SCHEDULER_RECONCILE_INTERVAL` | `60` | 对账扫描的周期（秒）。**清扫行在共享 store 里，所以这是整个集群一个值**：后启动的节点若配了不同值，会把 store 里那条 trigger 改成自己的——请在 `application.yml` 一侧定死，不要按实例改。低于 ~15s 时 store 读取不再是可忽略的量 |
| `SCHEDULER_ROUTER_URL` | `http://localhost:8081` | 执行入口 |
| `SCHEDULER_API_KEY` | 空 | 留空则启动时向 admin 申请一把 SYSTEM 型 Key（`RouterClient` 里显式判断）；显式配置可去掉这条启动依赖，代价是 admin 必须先于本服务可用 |
| `SCHEDULER_TIMEOUT` | `300` | 单次到 router 的调用超时（秒），同时也是回收扫描判僵尸的基线（×1.5）；上调它要一并上调 `stop_grace_period` 与 `SCHEDULER_STOP_GRACE` |
| `SCHEDULER_CLEAR_SESSION_TIMEOUT` | `60` | 执行后 session 清理的上限，生效值 `min(此值, SCHEDULER_TIMEOUT)`；它是停机预算求和的第二项 |
| `SCHEDULER_COMMAND_TIMEOUT` | `10` | `/stop` 的 INTERRUPT 上限，与执行超时是两回事：它必须落在 admin 转发 `/stop` 的 30s 读超时之内，也**不进**停机预算的求和（`/stop` 跑在请求线程上，不占 Quartz worker） |
| `SCHEDULER_ADMIN_URL` | `http://localhost:8080` | 会话管理等 |
| `SCHEDULER_ADMIN_SECRET` | 占位串 | 调 admin 内部 API 的密钥，compose 里取 `${ADMIN_INTERNAL_API_SECRET}`，**必须与 admin 同值** |

### 日志与文档端点

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LOG_LEVEL_ROOT` / `LOG_LEVEL` | `INFO` / `INFO` | |
| `MYBATIS_LOG_IMPL` | `org.apache.ibatis.logging.slf4j.Slf4jImpl` | **与 admin 不同**：admin 默认打到 stdout，本服务默认走 slf4j，生产无须改 |
| `SWAGGER_ENABLED` | `true` | `/swagger-ui.html` 与 `/v3/api-docs`，生产建议关 |
| `HARNAX_AUTH_SECRET` | 占位串 | 服务间 token 的签名密钥，发布 2 起是**双向**的：既签本服务的出向调用，也验 admin 转发进来的 bearer，**必须与 admin 同值**且 ≥32 字符，见「认证边界」 |

---

## 认证边界（读代码得到的事实）

`harnax.auth.enabled: false` 这条**仍然成立**，但它的含义在发布 2 变了：关掉的只是 `harnax-auth` 那套统一入向鉴权（API Key、限流、`@InternalOnly`，那是 spec §9 F1 的完整方案），本服务自己另装了一道只认服务间 token 的门禁（`support/InternalCallerInterceptor`）。从发布 2 起，`/api/scheduler/**` 的**全部**接口——**读也在内**——都要带一枚 `typ=internal` 的 `Authorization: Bearer <内部 JWT>`，否则直接 401（响应体是 `ResultVo`，`code=401`）。`/actuator/**` 不在门禁内，它不在这个前缀下，所以 compose 的健康检查照旧匿名可用。身份的另一半走转发头：`X-Forwarded-User` 与 `X-Tenant-Id` 由 admin 盖，本服务只在接受了内部 JWT 之后才读它们；`X-Forwarded-Tenant` **一律不读**，那是浏览器自己能发的头。

读面也被关进去，是对 spec §2.3「全部写面」那句的一次有意偏离：那句话出自 scheduler 只有写面的时候，而发布 2 的 C5 加了 `GET /api/scheduler/agent-tasks/{id}/owner`——任何能碰到 `8084` 的人都能凭一个任务 id 读出创建人与租户。理由与写面逐字相同，所以不再留豁免。

网络那一条**没有因此放松**：`8084` 依旧只有 `expose`、不发布宿主端口（compose 里就是这么写的，别把 `28084` 之类的映射加回来），nginx 也不再代理 `/api/scheduler/`。服务间 token 挡的是「进了容器网络的人」，不是「把 8084 暴露出去、签名密钥又写歪了的人」。这一条的取舍与后续计划写在 `prod_doc/agent-task-scheduler.zh-CN.md` §8.2，别在这里重新论证。

**admin 与 scheduler 必须同窗口升级。** 门禁一上，旧版 admin 转发的调用一律 401（它不带内部 JWT），症状是用户点「立即执行」「暂停」「删除」时看到「Scheduler service unavailable」或 40902，而调度本身照常在跑——看着像 scheduler 连不上，其实是它把请求拒了。同时两边的 `HARNAX_AUTH_SECRET` 必须同值：compose 里 admin 与 scheduler 两段都由同一个变量插值，配一次就同源；唯一能配错的是手工/裸机部署，那里两边各写一次。`HARNAX_AUTH_SECRET` 短于 32 字符时本服务**起不来**（`SchedulerConfig` 与 admin 的 provider 都按这条硬性拒绝），这是有意的：宁可启动就报错，也不要起来后把每一发转发都拒成 401。

## 与 MCP / 用户身份的关系

定时任务发起的会话 id 形如 `task-{taskId}-{agentId}-{uuid}`（契约 C1，四段）。运行时**以任务创建人的身份**解析其 MCP 授权，但**不再由 admin 本地读表**：`McpSessionOwnerResolver.fromTask` 改打本服务的 C5 端点 `GET /api/scheduler/agent-tasks/{id}/owner`（`AgentTaskOwnerController.kt:48-58`，读 `selectAnyById`，答 `creator` + `tenantId` + `agentId`；行不存在回 `data:null`）。部署上有三条后果：

- **冷路径坏了不会让任务失败，只会让那次执行缺一类工具**。owner 查询失败（本服务停机、密钥不同值、网络不通）⇒ 解析结果为 `null` ⇒ 那次执行的 OAuth 类 MCP 工具不可用，任务本身照常跑完。admin 侧的故障形态是 **WARN 日志**（带 taskId 与原因），不是静默 debug——排查"任务成功但 OAuth 工具不见了"先看这条。
- **这个端点也在 C4 门禁之内**（它答的是"谁的任务"），所以它需要与 admin 同值的 `HARNAX_AUTH_SECRET`，并且只从容器网络可达（`8084` 不发布宿主端口）。把它当成匿名内部接口来 curl 会得到 401，那不是回归。
- **维护窗口内停掉 scheduler 的连带影响多了一项**：那段窗口里跑起来的定时任务解析不出 OAuth 属主（任务照跑、OAuth 工具缺席）。要避开就把这类任务排在窗口之外，不要靠"反正是冷路径"。

另外两条与身份有关、但不由这条链决定的事实：任务创建人撤销或过期了自己的 OAuth 授权，会直接反映到该用户创建的任务上——表现为任务执行时提示「请重新授权该 MCP 服务」，而不是调度失败；人员离职 / 账号删除后，其任务不再有任何可花的授权，停用任务要走 admin 的通道，不是删库。任务运行在 `BYPASS` 权限模式下（当前硬编码在 admin 的 `resolveFromTask`），这是既有设计口径（spec D5 / F5）。**同一处还有一个已登记的残留**：admin 装配 spec 用的 agentId 是直接从 sessionId 字符串里读出来的，域搬走之后它没法再拿 `agent_task.agent_id` 核对——见 spec §9 F15。

## 健康检查与观测

| 端点 | 内容 |
|---|---|
| `/actuator/health/liveness` | 进程是否活着（**刻意不与"有没有在调度"绑定**，数据库慢不该让容器被重拉）。compose 的健康检查与滚动脚本看的都是它 |
| `/actuator/health` | 聚合视图，含 `scheduler` 指示器：`quartzStarted`、`instanceId`（集群下的真实实例名）、`storeType`（`LocalDataSourceJobStore` 才算进了集群，`RAMJobStore` 说明这台在集群外）、`scheduledJobCount`（**读 store，因此是集群视图**）、`lastReconcileAt` / `lastReconcileError` |
| `/actuator/metrics`、`/actuator/prometheus` | tag `application=harnax-scheduler`。指标口径见 `prod_doc/agent-task-scheduler.zh-CN.md` §9 |

`lastReconcileAt` 只能当**本节点**的观察读：推进它的有两处——60s 的清扫（集群单例，只有 fire 它的那台会更新）和 admin 转发的 `/reload`（只落到调用方解析到的那一台）。所以某台的这个字段很旧，意思只是"最近没人叫它收敛过"，不等于集群没在收敛；要看集群得看**真的跑过一轮的那台**的 `lastReconcileError`。别对这个时间戳的年龄设告警——它告的是这份工作怎么分配到副本上的。

`/actuator/channels` 之类没有；`show-details` 未开 `always`（与 channel 服务不同）。

## 发布 2 切口：数据源切到 `harnax_scheduler`

这一步把本服务的数据源从 `harnax_admin` 换进自有的 `harnax_scheduler`，是整个改造里唯一需要停服的动作，也是唯一不能滚动做的动作。

**它不搬任何数据。** 用户确认没有历史包袱，spec 的 D8（迁任务定义、不迁历史日志）因此取消：没有迁移脚本，没有自校验查询，也没有"历史清空 / 最近运行两列变空"这类要公告的损失——没有东西可失去。新库从空开始，**切口后由用户在界面重建任务**。旧库 `harnax_admin` 里的 `agent_task` / `agent_task_log` / `agent_task_execution` 与 11 张 `QRTZ_*` 在切口后没有任何活着的读者，**当场 DROP 即可，不需要观察期**（本仓库不随发布合入 DROP 脚本，那条 SQL 由运维执行）。

**为什么 admin 与 scheduler 必须一起下线**——两条理由都与数据无关，所以"先把库换过去、代码以后再合"这种分两批的做法在这里不成立：

1. **C4**：scheduler 现在拒收未签名的 HTTP，`/api/scheduler/**` 全部接口（读也在内）都要带一枚 `typ=internal` 的 bearer。旧版 admin 不发这一枚，它的每一次转发都是 401。两边的 `HARNAX_AUTH_SECRET` 还必须同值：compose 里两个服务由同一个变量插值，配一次就同源；手工/裸机部署两边各写一次，是唯一能写歪的地方。
2. **C1**：sessionId 现在是四段 `task-{taskId}-{agentId}-{uuid}`，旧 admin 读不懂这个形态（它的解析器只认三段），反过来旧 scheduler 发的三段 id 新 admin 会直接拒。所以**任何新旧混跑的组合都不成立**，一新一旧凑一对就是坏的一侧在坏的一侧看不见地丢执行。

### 顺序

1. 停 scheduler 的**全部副本** + admin。窗口内堆积的 cron 走 misfire 路径，`concurrent=0` 用的是 `withMisfireHandlingInstructionDoNothing`——**那一发被跳过，不是延后补跑**，公告要这么写。
2. 起**一个** scheduler 副本，数据源指向 `harnax_scheduler`。库与授权由 `docker-new/sql/init-databases.sql` 预建（存量部署要手工补建库 + `GRANT` + `FLUSH PRIVILEGES`，那个脚本只在 MySQL 首次初始化空数据目录时跑），Flyway 在这个空库里应用 `V1__quartz_tables.sql` + `V2__agent_task_domain.sql`。
3. 核对建出来的表：三张 `agent_task*` + 11 张 `QRTZ_*` + `flyway_schema_history_scheduler` 的两行。**`agent_task_log` 哪怕注定是空的也必须在**——`AgentTaskMapper.xml` 的 `selectTaskList` 自联这张表取 `lastRunStatus` / `lastRunTime`，缺表是列表页 500，不是"某一列空着"。
4. 让对账跑一轮（等 60s 的集群清扫，或建一个任务由 admin 转发触发 `/reload`），核对结果是 **0 个被调度的任务**：`/actuator/health` 的 `scheduledJobCount`（读 store，匿名可用），或 `GET /api/scheduler/tasks/status` 的 `scheduledTaskCount`——后者从 C4 起要带内部 JWT，别按匿名端点 curl 它。非 0 说明这台连的还是旧库。
5. 起 admin（发布 2 版本），三客户端主链路各跑一遍：webui 列表/创建/编辑/启停/删除/立即执行/日志轮询、CLI `task list|get|create|trigger|stop`、小程序任务页。
6. 起第二副本（`--scale scheduler=2`，或 `roll-scheduler.sh`），回读 `harnax_scheduler.QRTZ_SCHEDULER_STATE`：要看到的是**两个 `INSTANCE_NAME` 各自的 `LAST_CHECKIN_TIME` 每 15s 前进**，不是"两行"（见「双实例与逐台滚动」）。
7. 用户在界面重建任务。
8. DROP `harnax_admin` 里的三张 `agent_task*` 与 11 张 `QRTZ_*`。

### 回滚

```bash
SPRING_DATASOURCE_URL=…/harnax_admin…      # compose 侧改的是 SCHEDULER_DB_URL
QUARTZ_JOB_STORE=memory                    # 不去接旧库里那批发布 1 留下的 QRTZ_*
SCHEDULER_FLYWAY_ENABLED=false             # 否则它会拿 V2 去碰 harnax_admin
```

三个都要：`QUARTZ_JOB_STORE=memory` 让这台节点不进集群、不往一张已经没有活着的对端承诺同源更新的旧 store 里写调度真相（离集群的完整代价见「Quartz 存储模式」）；`SCHEDULER_FLYWAY_ENABLED=false` 是因为 V2 对旧库虽是 `IF NOT EXISTS` 的空转，却会把 V2 记进旧库的 `flyway_schema_history_scheduler`，让一个回滚状态看起来像应用过发布 2 的 schema。**并且要说清**：只把 URL 指回去**不等于回到发布 1 的行为**——C4 的门禁与 C1 的四段 id 都在代码里，旧 admin 与新 scheduler 仍然互相读不懂，要退就得连镜像一起退、两个服务同时退。回滚的残留是明确的：切口之后新建/改过的任务只存在于 `harnax_scheduler`，不会跟着回到旧库，也没有合并路径。

> **这条退路有截止日期**：上面三步只在**第 8 步还没执行**时成立。一旦 `harnax_admin` 的三张 `agent_task*` 与 11 张 `QRTZ_*` 被 DROP，指回旧库就连表都没有——要退就得先把表建回来（运维手工建表是干净的一条；把 `SCHEDULER_FLYWAY_ENABLED` 临时开成 true 让 V1+V2 在旧 URL 上重放也行，但前提是旧库那张 `flyway_schema_history_scheduler` 台账还在——被一起删过就得先把它对齐，否则 validate 会先拦下来），然后再关回去。所以第 8 步之前先确认新库跑顺，这一步做完之后回滚的成本就不再是"改三个变量"。

## 全新部署一次（2026-09-16 实测）

`bash docker-new/deploy-all.sh` 一把梭（Maven 全模块 → webui `npm run build` → 产物入 `docker-new/dist` → 沙箱镜像 → 6 个服务镜像 `--no-cache` → `down` → `up -d --scale scheduler=${SCHEDULER_REPLICAS:-2}`）。**开跑前有两件事不做就一定失败**：

1. **`docker-new/.env` 里那两个占位密钥必须换掉真值**。`ADMIN_INTERNAL_API_SECRET` 与 `HARNAX_AUTH_SECRET` 一旦还是仓库里公开的 `change-me-in-production-min-32-chars!!`，router 在 `CACHE_TYPE=redis` 下会被 `harnax-session-router/.../config/PlaceholderSecretCheck.kt` 在 `@PostConstruct` 里直接 `error(...)`——**容器起不来，不是降级起来**。顺手给 `HARNAX_AES_SECRET_KEY` 一个**恰好 32 字节**的值（`AesUtil` 只告警不拦，但空库时是唯一次没有代价的设定时机：晚设会让已加密的模型 key / MCP header 读不出来）。
2. **"清空数据库"在这套部署里等价于移走 bind mount**。MySQL 的数据在 `${MYSQL_DATA_DIR:-./data/mysql}`，`sql/init-databases.sql` 只在**目录为空**时由 `docker-entrypoint-initdb.d` 执行一次；删库名、`TRUNCATE`、或只重启容器都不会让 `harnax_scheduler` 重新出现（它连库都不建，建表是 scheduler 自己的 Flyway）。做法：`docker compose -f docker-new/docker-compose.yml down` 之后把 `docker-new/data/mysql` 改名（比 `rm -rf` 可回退），再起来，五个库（`harnax_admin` / `harnax` / `agentscope` / `harnax_router` / `harnax_scheduler`）与授权会由脚本重建。Redis 只有派生状态，跟着 `docker volume rm docker-new_redis-data` 一起清掉最省事（`down` 不动卷）。

实测结论（`kotlin-dev` @ `d60eab3`）：

- 11 个容器全 `healthy`；`harnax_scheduler` 恰好 15 张表 = 11 张 `QRTZ_*` + `agent_task` / `agent_task_log` / `agent_task_execution` + `flyway_schema_history_scheduler`，V1、V2 均 `success=1`；`harnax_admin` 的 Flyway 到 V28。
- `QRTZ_SCHEDULER_STATE` 两行、各按 15s 前进；`docker kill docker-new-scheduler-2` 之后存活副本 **21s** 打出 `ClusterManager: detected 1 failed or restarted instances` → `Freed 1 acquired trigger(s)`，`up -d --scale scheduler=2` 后重新两行。**接管窗口不是纸面推的了**（推算过程见 spec §11 第 2 条）。
- 观测面无漂移：`scheduler.jobs.scheduled`=0（空库），`scheduler.reconcile.rounds{outcome=success}` 每分钟一次、没有 `failure` 标签，`scheduler.reconcile.drift` 从未被采样。
- 界面链路通：`https://localhost/` 200（80 端口 301 跳 443），`/api/admin/auth/cli-login` 拿到 JWT，`GET /api/admin/agent-tasks/page` 与 `GET /api/admin/agent-tasks/{id}/logs` 经 admin 转发到 scheduler 均 200 且 `Page` 形状完好。webui 调用的 11 条 agent-task 路径与 admin `AgentTaskController` 暴露的一一对得上，搬迁没漏路由。
- **仍未覆盖**：spec §11 的第 3/4/5/7 条（带执行中任务重启、执行中点"停止"、界面建任务/立即执行、OAuth MCP 的 C5 回归）。它们的前置是库里有一个 agent，而建 agent 要真实的模型 API Key——这一步只能由使用方给。
- 两条新登记的记账：`harnax_admin` 里会被历史迁移重放出三张 0 行的孤儿 `agent_task*` 表（spec §9 F16）；两副本冷启动时系统 sweep 撞一次重复键、20ms 后自愈，代价是一条带 SQL 字样的 WARN（spec §9 F17）。

## 常见问题

| 现象 | 先看什么 |
|---|---|
| 任务跑了两遍 | 先分清是不是**同一个时点**跑了两遍。不同触发时点各跑一遍（`trigger_time` 不同，`uk_task_trigger` 挡不住）＝有实例以 `QUARTZ_JOB_STORE=memory` 起：它不进集群、按自己私有的那份 schedule 到点，而只有 jdbc 那台收到的 CRUD 让它带着旧 cron 一直在跑（见前两节）。同一个时点真跑了两遍＝有两套不同 `instanceName` 的部署共用了同一个库 |
| 任务到点不触发 | 集群里是否**至少一台** `SCHEDULER_ENABLED=true`；`QRTZ_SCHEDULER_STATE` 有没有行、`LAST_CHECKIN_TIME` 有没有在动（空表说明没人进过集群）；cron 表达式；`agent_task` 的启用状态 |
| 触发后无执行 | `SCHEDULER_ROUTER_URL` 是否可达、`SCHEDULER_API_KEY` 是否拿到了（留空时要问 admin） |
| 内部 API 401 | `SCHEDULER_ADMIN_SECRET` 与 admin 的 `ADMIN_INTERNAL_API_SECRET` 是否同值；admin 现在**拒绝占位默认值**走业务接口，两边都得换成真值 |
| 发布后 admin 的每次调度操作都失败（「Scheduler service unavailable」/ 40902），但 cron 照常触发 | 大概率是 C4 门禁拒了那一发，不是网络不通：scheduler 日志里 `Refusing /api/scheduler/...` 的 WARN 把原因写在最后一段——`no internal service bearer token presented`＝admin 还没升到发布 2（两边必须同窗口发）；`bearer rejected (…)`＝两边 `HARNAX_AUTH_SECRET` 不同值或密钥被换过（compose 同源，手工部署才会歪）；`caller 'xxx' is <type>, not an internal service`＝拿来的那枚不是 `typ=internal` 的 token（例如误用了用户 JWT 或另一服务的 key） |
| 用户看到 40903 / 40902 但任务确实保存了 | admin 那一发转发落到了关了调度的实例上——检查同名 service 下是否还挂着 `SCHEDULER_ENABLED=false` 的副本 |
| 发布后出现中间态执行 | 手动与 cron 现在同受停机宽限保护（见「优雅停机」），所以中间态只意味着**宽限真的被截断过**：核对 `stop_grace_period` 与 `SCHEDULER_STOP_GRACE` 是否被单独改小过、`QUARTZ_WAIT_FOR_JOBS` 是否还是 `true`、以及是否用了 `--force-recreate` 而不是 `roll-scheduler.sh` |
| 点了「立即执行」但日志列表是空的 | 先看 scheduler 日志有没有 `skipping this fire` / `already being executed by another instance`：那一发在 fire 时被重叠闸口或集群锁丢掉，按设计**不留日志行**（见「优雅停机」第二条）。两处都没有再看 `agent_task` 的 `active`——软删除的任务在 fire 时同样被拒，而 one-shot **不看** `task_status`，暂停中照样跑 |
| 执行里缺 MCP 工具 | 两问：① 任务创建人是否有有效授权（挂的是 OAuth 类 MCP 而未授权就会缺）；② 那次执行**读得到属主吗**——发布 2 起这一步是跨服务的 C5 冷路径，本服务停机或两边密钥不同值都会让它返回 null，症状同样是"任务成功但缺一类工具"，线索是 admin 侧那条带 taskId 的 WARN（见「与 MCP / 用户身份的关系」） |

---

## 相关文档

- `prod_doc/agent-task-scheduler.zh-CN.md`：职责划分、数据归属、admin↔scheduler 契约、指标口径
- `docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md`：真集群（JDBC JobStore、2 实例、D6 停机语义）的设计与里程碑
- `docs/superpowers/plans/2026-09-14-scheduler-jdbc-cluster.md`：发布 1 的实现计划（里程碑 S2，QRTZ 集群 + reconcile + 部署形态）
- `docs/superpowers/plans/2026-09-14-scheduler-domain-migration.md`：发布 2 的实现计划（里程碑 S3，域搬迁 + C1/C4/C5 + 上面「发布 2 切口」那一节的出处）
- `docker-new/roll-scheduler.sh`：逐台滚动脚本，头部注释是这段拓扑规则的出处
- `docs/deploy-harnax-session-router.md`：router 部署（执行入口）
- `docs/deploy-harnax-agent-service.md`：运行时侧的 MCP 与身份语义
