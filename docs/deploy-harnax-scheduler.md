# harnax-scheduler 部署文档

## 服务概述

`harnax-scheduler` 是**定时任务触发器**：读 `agent_task` 表里的任务定义，按 cron 到点后经 router 发起一次智能体执行，并回写执行状态。它不做业务 CRUD（那些在 admin），也不跑智能体（那些在 agent-service）。

一句话结论先说：**既定形态是两个调度实例同时开着 `SCHEDULER_ENABLED=true`**。Quartz 跑真正的 JDBC 集群 store（`isClustered=true`，11 张 `QRTZ_*` 表是全集群唯一的调度真相），一次 cron 触发在集群里只投递一次、只由一个节点执行；一台死了另一台接管。怎么核对与怎么滚动见「双实例与逐台滚动」，这是本文件最重要的一节。

端口默认 `8084`（`SCHEDULER_PORT`）。compose 里**不发布宿主端口**，只有 `expose: ["8084"]`：admin 走容器网络 `http://scheduler:8084`。

---

## 环境依赖

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK | 21 | |
| MySQL | `harnax_admin` 库 | 与 admin **共用同一个库、但各管各的表**：本服务读 `agent_task` / 写 `agent_task_log`，并且**自己建自己的 `QRTZ_*` 集群表**。表结构由两边的 Flyway 各写一份历史表（本服务 `flyway_schema_history_scheduler`、admin `flyway_schema_history`），互不干扰，所以本服务的迁移开关（`SCHEDULER_FLYWAY_ENABLED`，未设时回退 `FLYWAY_ENABLED`）默认 `true` 且**必须保持开**——关掉就没有 `QRTZ_*`，`QUARTZ_JOB_STORE=jdbc` 的节点直接起不来。发布 2 才把数据源搬进 `harnax_scheduler`（`docker-new/sql/init-databases.sql` 已预建该库并授权，发布 1 用不上它） |
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

仓库里只有这里写全了退路。两个开关一起动，只动一个会把节点留在集群外却没有 store：

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

集群成员的直接读数是 `QRTZ_SCHEDULER_STATE`（发布 1 里它在 `harnax_admin` 库，因为本服务的数据源此刻还指那边）：

```sql
SELECT INSTANCE_NAME, LAST_CHECKIN_TIME, CHECKIN_INTERVAL FROM harnax_admin.QRTZ_SCHEDULER_STATE;
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
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/harnax_admin?...` | 与 admin 同库；`QRTZ_*` 就建在这里，发布 2 才搬走 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `root` / `123456` | compose 侧走 `DB_USERNAME` / `DB_PASSWORD` |
| `DB_POOL_SIZE` / `DB_POOL_MIN_IDLE` | `30` / `3` | Hikari。30 是按下界选的：≥ `QUARTZ_THREAD_COUNT`(10) 个 worker（每个在一次 fire 里占一条连接）+ 业务查询 + 集群 checkin，全走这一个池。**`QUARTZ_THREAD_COUNT` 与它要一起动**——只加 worker 不加池不会多出容量，只是把等待从调度线程挪到 30s 的 connection-timeout 上。与 admin 的 20/5 不同 |
| `FLYWAY_ENABLED` | `true` | 本服务的迁移**必须开**：`V1__quartz_tables.sql` 建 `QRTZ_*`，历史记在自有的 `flyway_schema_history_scheduler`，与 admin 的 `flyway_schema_history` 互不干扰。它是调度节点迁移的**回退位**而不是决定位——`application.yml` 读的是 `${SCHEDULER_FLYWAY_ENABLED:${FLYWAY_ENABLED:true}}`，所以只要 `SCHEDULER_FLYWAY_ENABLED` 设了值，改这个 admin 同名的键就不起作用（它原本就是防着「手工恢复时顺手改了 admin 那个值，把调度节点停了迁移」） |
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

定时任务发起的会话 id 形如 `task-{taskId}-...`，运行时会**以任务创建人的身份**解析其 MCP 授权（`McpSessionOwnerResolver` 走 `agent_task` 行）。部署上意味着：

- 任务创建人撤销或过期了自己的 OAuth 授权，会直接反映到该用户创建的任务上——表现为任务执行时提示「请重新授权该 MCP 服务」，而不是调度失败。
- 人员离职 / 账号删除后，其任务不再有任何可花的授权；停用任务要走 admin 的通道，不是删库。
- 任务运行在 `BYPASS` 权限模式下（当前硬编码），这是既有设计口径，见上面同一份文档。

## 健康检查与观测

| 端点 | 内容 |
|---|---|
| `/actuator/health/liveness` | 进程是否活着（**刻意不与"有没有在调度"绑定**，数据库慢不该让容器被重拉）。compose 的健康检查与滚动脚本看的都是它 |
| `/actuator/health` | 聚合视图，含 `scheduler` 指示器：`quartzStarted`、`instanceId`（集群下的真实实例名）、`storeType`（`LocalDataSourceJobStore` 才算进了集群，`RAMJobStore` 说明这台在集群外）、`scheduledJobCount`（**读 store，因此是集群视图**）、`lastReconcileAt` / `lastReconcileError` |
| `/actuator/metrics`、`/actuator/prometheus` | tag `application=harnax-scheduler`。指标口径见 `prod_doc/agent-task-scheduler.zh-CN.md` §9 |

`lastReconcileAt` 只能当**本节点**的观察读：推进它的有两处——60s 的清扫（集群单例，只有 fire 它的那台会更新）和 admin 转发的 `/reload`（只落到调用方解析到的那一台）。所以某台的这个字段很旧，意思只是"最近没人叫它收敛过"，不等于集群没在收敛；要看集群得看**真的跑过一轮的那台**的 `lastReconcileError`。别对这个时间戳的年龄设告警——它告的是这份工作怎么分配到副本上的。

`/actuator/channels` 之类没有；`show-details` 未开 `always`（与 channel 服务不同）。

## 常见问题

| 现象 | 先看什么 |
|---|---|
| 任务跑了两遍 | 先分清是不是**同一个时点**跑了两遍。不同触发时点各跑一遍（`trigger_time` 不同，`uk_task_trigger` 挡不住）＝有实例以 `QUARTZ_JOB_STORE=memory` 起：它不进集群、按自己私有的那份 schedule 到点，而只有 jdbc 那台收到的 CRUD 让它带着旧 cron 一直在跑（见前两节）。同一个时点真跑了两遍＝有两套不同 `instanceName` 的部署共用了同一个库 |
| 任务到点不触发 | 集群里是否**至少一台** `SCHEDULER_ENABLED=true`；`QRTZ_SCHEDULER_STATE` 有没有行、`LAST_CHECKIN_TIME` 有没有在动（空表说明没人进过集群）；cron 表达式；`agent_task` 的启用状态 |
| 触发后无执行 | `SCHEDULER_ROUTER_URL` 是否可达、`SCHEDULER_API_KEY` 是否拿到了（留空时要问 admin） |
| 内部 API 401 | `SCHEDULER_ADMIN_SECRET` 与 admin 的 `ADMIN_INTERNAL_API_SECRET` 是否同值；admin 现在**拒绝占位默认值**走业务接口，两边都得换成真值 |
| 发布后 admin 的每次调度操作都失败（「Scheduler service unavailable」/ 40902），但 cron 照常触发 | 大概率是 C4 门禁拒了那一发，不是网络不通：scheduler 日志里 `Refusing /api/scheduler/...` 的 WARN 会写清是「没有 bearer」还是「签名验不过」。前者＝admin 还没升到发布 2（两边必须同窗口发），后者＝两边 `HARNAX_AUTH_SECRET` 不同值（compose 同源，手工部署才会歪） |
| 用户看到 40903 / 40902 但任务确实保存了 | admin 那一发转发落到了关了调度的实例上——检查同名 service 下是否还挂着 `SCHEDULER_ENABLED=false` 的副本 |
| 发布后出现中间态执行 | 手动与 cron 现在同受停机宽限保护（见「优雅停机」），所以中间态只意味着**宽限真的被截断过**：核对 `stop_grace_period` 与 `SCHEDULER_STOP_GRACE` 是否被单独改小过、`QUARTZ_WAIT_FOR_JOBS` 是否还是 `true`、以及是否用了 `--force-recreate` 而不是 `roll-scheduler.sh` |
| 点了「立即执行」但日志列表是空的 | 先看 scheduler 日志有没有 `skipping this fire` / `already being executed by another instance`：那一发在 fire 时被重叠闸口或集群锁丢掉，按设计**不留日志行**（见「优雅停机」第二条）。两处都没有再看 `agent_task` 的 `active`——软删除的任务在 fire 时同样被拒，而 one-shot **不看** `task_status`，暂停中照样跑 |
| 执行里缺 MCP 工具 | 任务创建人是否有有效授权；挂的是 OAuth 类 MCP 而未授权就会缺 |

---

## 相关文档

- `prod_doc/agent-task-scheduler.zh-CN.md`：职责划分、数据归属、admin↔scheduler 契约、指标口径
- `docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md`：真集群（JDBC JobStore、2 实例、D6 停机语义）的设计与里程碑
- `docs/superpowers/plans/2026-09-14-scheduler-jdbc-cluster.md`：本发布的实现计划（发布 1 = 里程碑 S2）
- `docker-new/roll-scheduler.sh`：逐台滚动脚本，头部注释是这段拓扑规则的出处
- `docs/deploy-harnax-session-router.md`：router 部署（执行入口）
- `docs/deploy-harnax-agent-service.md`：运行时侧的 MCP 与身份语义
