# harnax-scheduler 部署文档

## 服务概述

`harnax-scheduler` 是**定时任务触发器**：读 `agent_task` 表里的任务定义，按 cron 到点后经 router 发起一次智能体执行，并回写执行状态。它不做业务 CRUD（那些在 admin），也不跑智能体（那些在 agent-service）。

一句话结论先说：**当前形态只允许一个调度实例开着 `SCHEDULER_ENABLED=true`**。原因见「Quartz 存储模式」，这是本文件最重要的一节。

端口默认 `8084`（`SCHEDULER_PORT`），对外发布 `28084`。

---

## 环境依赖

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK | 21 | |
| MySQL | `harnax_admin` 库 | 与 admin **共用**：读 `agent_task` / 写 `agent_task_log`。表结构由 admin 的 Flyway 维护，本服务 `FLYWAY_ENABLED` 默认 `false`，**不要去开** |
| router | 必须可达 | 执行入口 `SCHEDULER_ROUTER_URL` |
| admin | 必须可达 | 会话管理与系统 Key 获取 |
| Redis / MinIO | 不需要 | |

---

## Quartz 存储模式（决定能不能多实例）

`QUARTZ_JOB_STORE` 默认 `memory`：调度状态活在进程内存里。

| 模式 | 现状 | 后果 |
|---|---|---|
| `memory` | **当前唯一可用**：仓库里没有任何 `QRTZ_*` 建表脚本（admin 的迁移目录与本模块都没有） | 两个实例同时开启调度 = 每个实例自己 fire 一次，**任务重复执行**。第二实例不会接管也不会去重 |
| `jdbc` | **目标形态，尚未落地**：独立 `harnax_scheduler` 库 + `QRTZ_*` 表 + 集群配置，见 `docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md` | 落地后才可跑 2 实例并具备故障接管与 misfire 补偿 |

所以今天部署多实例的唯一正确姿势是：**一个实例 `SCHEDULER_ENABLED=true`，其余实例 `false`**（只作为 API 侧的转发存在，不参与调度）。设成两个 `true` 的后果不是性能问题，是用户会看到同一任务跑两遍、`agent_task_log` 出现成对记录。

`SCHEDULER_INSTANCE_ID` 留空时启动自动生成，供执行守卫（`AgentTaskExecutionGuard`）标记「这条执行是谁发起的」；它是同库多实例之间的写入归属标记，**不是**跨实例去重锁——不要指望它能替代 JDBC 集群。

## 优雅停机（当前与目标不一致，务必知道）

- 本模块 compose 里**没有** `stop_grace_period`，于是沿用 Docker 默认的 10s。
- 一次任务执行的预算是 `SCHEDULER_TIMEOUT=300s`（到 router 的调用超时）。
- 两者相加意味着：**发布或重启时，正在跑的执行最多只有 10 秒收尾窗口**，之后容器被 SIGKILL。表现为该次执行停在 `agent_task_log` 的中间态，而不是等它跑完。
- 设计文档里 D6 定的目标是 `waitForJobsToCompleteOnShutdown=true` + `stop_grace_period: 360s` + 逐台滚动；**这部分尚未实现**，所以别按 360s 安排发布窗口。
- 在此之前，可用的缓解是：避开任务密集时刻重启，并在重启后核对停在中间态的执行记录。

`QUARTZ_THREAD_COUNT` 默认 `10`，是并发执行的上限；设计文档明确**不再上调**（提高会直接放大对 router / agent-service 的下游压力）。

---

## 环境变量说明

### 基础与端口

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SCHEDULER_PORT` | `8084` | HTTP 端口 |
| `SCHEDULER_ENABLED` | `true` | 本实例是否参与调度。**多实例时只留一个 true** |
| `SCHEDULER_INSTANCE_ID` | 空（自动生成） | 执行归属标记 |

### 数据源

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/harnax_admin?...` | 与 admin 同库 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `root` / `123456` | |
| `DB_POOL_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `3` | Hikari（注意与 admin 的 20/5 不同） |
| `FLYWAY_ENABLED` | `false` | 迁移归 admin；本服务开了会和 admin 抢同一套表 |

### 调度与下游

| 变量 | 默认值 | 说明 |
|---|---|---|
| `QUARTZ_JOB_STORE` | `memory` | 见上一节，改 `jdbc` 目前会因缺表失败 |
| `QUARTZ_THREAD_COUNT` | `10` | 并发执行上限，不建议上调 |
| `SCHEDULER_ROUTER_URL` | `http://localhost:8081` | 执行入口 |
| `SCHEDULER_API_KEY` | 空 | 留空则启动时向 admin 申请一把 SYSTEM 型 Key（`RouterClient` 里显式判断）；显式配置可去掉这条启动依赖，代价是 admin 必须先于本服务可用 |
| `SCHEDULER_TIMEOUT` | `300` | 单次到 router 的调用超时（秒） |
| `SCHEDULER_ADMIN_URL` | `http://localhost:8080` | 会话管理等 |
| `SCHEDULER_ADMIN_SECRET` | 占位串 | 调 admin 内部 API 的密钥，compose 里取 `${ADMIN_INTERNAL_API_SECRET}`，**必须与 admin 同值** |

### 日志与文档端点

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LOG_LEVEL_ROOT` / `LOG_LEVEL` | `INFO` / `INFO` | |
| `MYBATIS_LOG_IMPL` | `org.apache.ibatis.logging.slf4j.Slf4jImpl` | **与 admin 不同**：admin 默认打到 stdout，本服务默认走 slf4j，生产无须改 |
| `SWAGGER_ENABLED` | `true` | `/swagger-ui.html` 与 `/v3/api-docs`，生产建议关 |
| `HARNAX_AUTH_SECRET` | 占位串 | 出向服务间 token 的签名密钥 |

---

## 认证边界（读代码得到的事实）

`harnax.auth.enabled: false` —— 本服务的**入向**统一鉴权是关掉的，`HARNAX_AUTH_SECRET` 只用于出向签 token。也就是说 `8084` 上的接口不是靠服务间 token 保护的，暴露面必须靠网络：只让 admin / 内网可达，**不要**把 `28084` 直接放到公网。这一条的取舍与后续计划写在 `prod_doc/agent-task-scheduler.zh-CN.md` §8.2，别在这里重新论证。

## 与 MCP / 用户身份的关系

定时任务发起的会话 id 形如 `task-{taskId}-...`，运行时会**以任务创建人的身份**解析其 MCP 授权（`McpSessionOwnerResolver` 走 `agent_task` 行）。部署上意味着：

- 任务创建人撤销或过期了自己的 OAuth 授权，会直接反映到该用户创建的任务上——表现为任务执行时提示「请重新授权该 MCP 服务」，而不是调度失败。
- 人员离职 / 账号删除后，其任务不再有任何可花的授权；停用任务要走 admin 的通道，不是删库。
- 任务运行在 `BYPASS` 权限模式下（当前硬编码），这是既有设计口径，见上面同一份文档。

## 健康检查与观测

| 端点 | 内容 |
|---|---|
| `/actuator/health/liveness` | 进程是否活着（**刻意不与"有没有在调度"绑定**，数据库慢不该让容器被重拉） |
| `/actuator/health` | 聚合视图，含 `scheduler` 指示器与实际执行状态 |
| `/actuator/metrics`、`/actuator/prometheus` | tag `application=harnax-scheduler` |

`/actuator/channels` 之类没有；`show-details` 未开 `always`（与 channel 服务不同）。

## 常见问题

| 现象 | 先看什么 |
|---|---|
| 任务跑了两遍 | 是否有两个实例都 `SCHEDULER_ENABLED=true`（`memory` 模式不去重，见上文） |
| 任务到点不触发 | 本实例是否 `SCHEDULER_ENABLED=false`；cron 表达式；`agent_task` 的启用状态 |
| 触发后无执行 | `SCHEDULER_ROUTER_URL` 是否可达、`SCHEDULER_API_KEY` 是否拿到了（留空时要问 admin） |
| 内部 API 401 | `SCHEDULER_ADMIN_SECRET` 与 admin 的 `ADMIN_INTERNAL_API_SECRET` 是否同值；admin 现在**拒绝占位默认值**走业务接口，两边都得换成真值 |
| 发布后出现中间态执行 | 就是那个 10s 的 `stop_grace_period` 缺口，目前无解，只能挑时段重启 |
| 执行里缺 MCP 工具 | 任务创建人是否有有效授权；挂的是 OAuth 类 MCP 而未授权就会缺 |

---

## 相关文档

- `prod_doc/agent-task-scheduler.zh-CN.md`：职责划分、数据归属、admin↔scheduler 契约
- `docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md`：真集群（JDBC JobStore、2 实例、D6 停机语义）的设计与里程碑
- `docs/superpowers/plans/2026-09-11-scheduler-execution-semantics.md`：执行语义修复计划
- `docs/deploy-harnax-session-router.md`：router 部署（执行入口）
- `docs/deploy-harnax-agent-service.md`：运行时侧的 MCP 与身份语义
