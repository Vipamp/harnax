# Harnax Session Router

Session Router 是 harnax 分布式 Agent 平台的核心网关层，负责将外部请求路由到正确的 agent-service 实例，并提供会话粘性、健康检查、故障转移和请求幂等性保障。

## 架构概览

### 单机部署（local 模式，默认）

适合开发、测试、小规模生产环境。单 Router 实例，纯内存状态，零外部依赖。

```
Channel / 外部 HTTP ──JWT/API Key──> Router :8081 ──JWT──> Agent-service :8082
                                     │ (进程内存状态)
                                     │   ├ 实例注册表
                                     │   ├ session 绑定
                                     │   ├ 幂等性去重
                                     │   └ 熔断器状态
                                     ├── admin :8080 (API Key 校验 + Session 信息查询)
                                     └── SQLite (仅 api_call_log 调用日志，嵌入式)
```

**特点：**
- 零外部依赖（SQLite 嵌入式 + 进程内存状态），启动即用
- 状态仅存于本进程内存，重启后丢失（实例需重新注册）
- 只能部署一个 Router 实例（多实例间状态不共享，会话粘性失效）

### 分布式部署（redis 模式）

适合生产环境。多 Router 实例 + Redis 共享状态，前置负载均衡。

```
                                    ┌─ Router :8081 (节点 A) ─┐
Channel / 外部 HTTP ── LB ──>      ├─ Router :8081 (节点 B)  ─┼──JWT──> Agent-service :8082
                                    └─ Router :8081 (节点 C) ─┘
                                              │
                                              ├── Redis (共享状态)
                                              │   ├ 实例注册表
                                              │   ├ session 绑定
                                              │   ├ 幂等性去重
                                              │   └ 熔断器状态
                                              ├── admin :8080 (API Key 校验 + Session 信息查询)
                                              └── MySQL (仅 api_call_log 调用日志)
```

**特点：**
- 任意 Router 节点都能处理任意 session 的请求（状态共享）
- 熔断状态全局一致（节点 A 熔断的实例，节点 B/C 也不会路由）
- 水平扩展：增加 Router 节点不影响现有会话
- Redis 故障降级：Redis 不可达时 Router 不崩，也不会把每个请求都打挂——会话绑定读本节点累积的影子
  缓存，实例列表读最后一次 Redis 快照（快照按心跳超时自然老化，之后宁可不路由，也不一直投向可能已经全
  灭的集群），熔断读失败时放行（fail-open）。代价是这段窗口内跨节点的会话粘性无法保证；Redis 恢复后
  一切仍以 Redis 为准。

### 部署模式对比

| 能力 | local（单机） | cluster（分布式） |
|------|------------|----------------|
| 数据库 | SQLite（嵌入式） | MySQL |
| 缓存 | 进程内存（ConcurrentHashMap） | Redis |
| 外部依赖 | 无 | MySQL + Redis |
| 水平扩展 | 不支持 | 支持 |
| 状态持久化 | 进程内存，重启丢失 | Redis，重启不丢失 |
| 熔断一致性 | 仅本节点 | 全节点共享 |
| 故障转移 | 本节点执行 | 任意节点均可执行（CAS 竞争） |
| 会话粘性 | 仅本节点可见 | 全节点可见，任意节点可路由 |
| 适用场景 | 开发 / 测试 / 个人版 | 生产 / 多节点集群 |

通过 Spring Profile 切换部署模式：

```bash
# 单机模式（默认，无需任何参数）
java -jar harnax-session-router.jar

# 分布式模式（激活 cluster profile → MySQL + Redis）
java -jar harnax-session-router.jar --spring.profiles.active=cluster
```

**`application.yml`（默认 / local 模式）**：SQLite 嵌入式数据库 + 进程内存状态，零外部依赖。  
**`application-cluster.yml`（cluster 模式）**：MySQL + Flyway 自动迁移 + Redis 共享缓存，由 `spring.profiles.active=cluster` 激活。

MySQL 仅用于调用日志（`api_call_log` 表），实例注册、会话绑定、API Key 校验均不依赖 MySQL。

### Redis Key 设计

仅 `redis` 模式使用：

```
router:instance:{instanceId}           Hash   实例信息 (host, port, status, lastHeartbeat)，TTL 24h
router:instances:healthy               Set    健康实例 ID 集合
router:instances:all                   Set    所有活跃实例 ID 集合
router:session:{sessionId}             String 绑定的 instanceId（TTL 24h，每次请求刷新）
router:instance_sessions:{instanceId}  Set    反向索引：实例绑定的 session 集合
router:idempotency:{requestId}         String 幂等性标记（TTL 60s）
router:circuit:{instanceId}            Hash   熔断器状态 (state / failures / openedAt / probeUntil)，
                                              恢复后直接删键；TTL 覆盖熔断窗口 + 探测租约
router:lock:session:{sessionId}        String reroute 单飞锁（TTL 10s）
router:lock:index_reconcile            String 反向索引对账锁（TTL 4min，多节点仅一个执行）
```

`SESSION_TTL` 由 `RedisSessionMappingService` 定义，local 模式复用同一个常量，因此一个会话在两种模式
下能保持其 agent 的时长完全一致。

反向索引（`router:instance_sessions:*`）与绑定不是同一个原子写入，掉盘或 Redis 抖动会让两边漂移，
`SessionIndexReconciler` 每 5 分钟（`router.reconcile.interval-ms`）对账一次：清掉指向已消失会话的
残留条目，并补回丢失的索引项。

## 核心职责

- **会话路由**：同一 session 的请求始终路由到同一 agent-service 实例（会话粘性）
- **健康检查**：5 秒间隔心跳检测，自动发现并剔除故障实例
- **故障转移**：实例宕机时自动将绑定的 session 迁移到健康实例（详见下方故障转移策略）
- **请求幂等**：基于 requestId 的去重，防止网络重试导致重复调用
- **熔断保护**：基于三态状态机的熔断模式，防止级联故障（详见下方熔断器章节）
- **调用日志**：异步批量记录 `/api/router/agent/` 下每次 API 调用的耗时、状态、agent/model 信息

## 快速开始

### 本地开发（local 模式，默认）

```bash
# 1. 无需任何外部依赖（SQLite 嵌入式 + 进程内存状态）

# 2. 编译
mvn clean package -pl harnax-session-router -am -DskipTests

# 3. 直接启动（默认 local 模式）
java -jar harnax-session-router/target/harnax-session-router.jar

# 4. 打开监控面板（面板 API 需要凭证，用 JWT 或 API Key 换 token）
open "http://localhost:8081/ui?token=<jwt or api key>"
```

### 集群模式启动（MySQL + Redis）

```bash
# 激活 cluster profile，自动加载 application-cluster.yml
java -jar harnax-session-router.jar --spring.profiles.active=cluster

# 或通过环境变量指定
SPRING_PROFILES_ACTIVE=cluster \
  DB_URL=jdbc:mysql://your-host:3306/harnax_router \
  REDIS_HOST=your-redis-host \
  java -jar harnax-session-router.jar
```

### Docker 部署

```bash
# 一键构建并启动（生产环境，自动激活 cluster profile）
cd docker && bash build.sh personal

# 或手动启动
docker compose -f docker/docker-compose.prod.yml up -d
```

Docker 编排包含：Backend、Agent-Service、Router、Redis、Frontend 五个服务，详见 [docker/README.md](../docker/README.md)。

### agent-service 接入

agent-service 启动后向 Router 注册即可自动接入（以下端点均为 `@InternalOnly`，需携带内部 JWT）：

```
POST /api/router/instance/register?instanceId=agent-1&host=10.0.1.5&port=8082
POST /api/router/instance/heartbeat?instanceId=agent-1    # 每 10s 心跳
POST /api/router/instance/drain?instanceId=agent-1        # 优雅停机
POST /api/router/instance/unregister?instanceId=agent-1   # 注销
```

## API 端点

### 对话代理（AgentProxyController）

需要凭证（内部 JWT 或外部 `X-Api-Key`），但**无** `@InternalOnly` 限制，因此两类调用方都能用。
所有请求会被 `ApiCallLogFilter` 记录调用日志。认证上下文里带终端用户身份时（外部 key、或运维/用户自己的登录 JWT），该身份始终覆盖请求体里自称的 `userId`，两者不一致还会打 WARN 记下调用方、声明值与真实值。只有认证上下文里**没有**终端用户身份的调用方（内部服务 token、SYSTEM key）才会采纳 body 的 `userId`——channel 与 scheduler 本来就替缺席用户跑任务——但这条「body 说了算」的通道每次采纳都打一条 INFO，留下谁在替谁说话。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/router/agent/chat` | 直接对话请求代理 |
| POST | `/api/router/agent/chat/stream` | SSE 流式对话代理 |
| POST | `/api/router/agent/command` | 命令请求代理 |
| POST | `/api/router/agent/confirm` | 确认请求代理（SSE） |
| DELETE | `/api/router/agent/session/{sessionId}` | 清除会话 |
| GET | `/api/router/agent/chat/history/{sessionId}` | 获取聊天历史 |
| GET | `/api/router/agent/session/{sessionId}/plans` | 获取计划列表 |
| GET | `/api/router/agent/session/{sessionId}/current-plan` | 获取当前计划 |
| GET | `/api/router/agent/workspace/{sessionId}/files` | 列出工作区文件 |
| GET | `/api/router/agent/workspace/{sessionId}/read` | 读取工作区文件 |
| GET | `/api/router/agent/workspace/status?sessionIds=` | 批量查询工作区状态 |
| GET | `/api/router/agent/workspace/{sessionId}/status` | 单会话工作区状态 |
| POST | `/api/router/agent/workspace/{sessionId}/upload` | 上传工作区文件 |
| GET | `/api/router/agent/workspace/{sessionId}/download` | 下载工作区文件（上限 50MB） |

### 实例管理（InstanceRegistryController，@InternalOnly）

类级别标注 `@InternalOnly`，仅允许内部服务（JWT）访问。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/router/instance/register` | 注册实例（含 SSRF 防护） |
| POST | `/api/router/instance/heartbeat` | 心跳刷新 |
| POST | `/api/router/instance/unregister` | 注销实例 |
| POST | `/api/router/instance/drain` | 优雅停机（drain 模式） |
| GET | `/api/router/instance/list` | 列出所有活跃实例 |

### 监控面板（RouterMonitorController）

只读的可观测性接口，**无** `@InternalOnly` 限制（操作者浏览器持有的是登录 JWT，不是服务间凭证），
但**需要凭证**：`Authorization: Bearer <jwt>` 或 `X-Api-Key` 二选一，缺失时 `UnifiedAuthFilter` 直接
401。实例列表是整个集群图，调用日志是完整请求轨迹，两者都不该匿名可读。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/router/monitor/instances` | 实例列表（含 session 数、心跳延迟） |
| GET | `/api/router/monitor/call-logs` | 调用日志分页查询（支持 sessionId / instanceId / agentName / statusCode 等过滤） |

### 监控 UI

访问 `http://<host>:8081/ui?token=<jwt or api key>` 打开内置的静态监控面板，展示：

- 已注册的 agent-service 实例列表（IP、端口、状态、session 数、心跳延迟）
- API 调用明细（按 sessionId / instanceId / agentName 查询，含耗时）

页面把 token 存进 `localStorage`（键 `harnax.monitor.token`），因此一次性带上后即可轮询；
`?token=` 会从地址栏抹掉，避免它留在浏览器历史和访问日志里。凭证失效（401/403）时页面停止轮询并显
示需要凭证，而不是刷出一片空白。也可在控制台用 `harnaxMonitor.setToken(...)` / `clearToken()` 手动
更换。

### 运维

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/router/health` | 当前可路由的健康实例数（内部 JWT，类级 `@InternalOnly`） |
| GET | `/api/router/metrics/cache` | 缓存实现统计（内部 JWT） |

这两个是**容量报表**，不是探针：`/api/router/health` 会在最后一个 agent 停止心跳时返回 DOWN，而那
恰恰是 Router 自身仍然健康、只是在拒绝接活的时候。容器与负载均衡的探针请用
`/actuator/health/liveness` 与 `/actuator/health/readiness`。

## 认证鉴权

Router 通过 `harnax-auth` 共享库实现统一认证：

- **内部调用**（channel → router → agent）：JWT Bearer Token，通过 `@InternalOnly` 注解控制访问
- **外部调用**（第三方 HTTP）：`X-Api-Key` 请求头，Router 通过 HTTP 调用 admin 的 `POST /api/internal/api-keys/validate` 校验
- **鉴权模型**：仅区分 internal/external，不做 scope 粒度控制
- **限流**：外部 API Key 支持滑动窗口限流（`RateLimitInterceptor`）

### skip-paths 配置

`harnax.auth.skip-paths` 定义 `UnifiedAuthFilter` 跳过的路径前缀。被跳过的路径不会设置 `AuthContext`，因此 `@InternalOnly` 接口会因 `InternalAuthorizationInterceptor` 检查不到 AuthContext 而返回 401。

当前配置：

```yaml
skip-paths:
  - /ui                    # 监控 UI 静态页面
  - /ui/
  - /index.html
  - /static/
  - /favicon.ico
  - /style.css
  - /app.js
```

跳过的只有页面本身要加载的文件；`/api/router/monitor/*` 已从该列表移出，因为实例列表和调用日志是
集群级信息，匿名可读等于把拓扑和会话轨迹摆在公网上。`UnifiedAuthFilter` 另外内置跳过 `/health` 和
`/actuator` 前缀，无需在此声明。

**注意：不要把 `/api/router/` 整体加入 skip-paths**，否则 heartbeat / register 等 `@InternalOnly` 接口的 JWT 不会被解析，`InternalAuthorizationInterceptor` 会因 AuthContext 缺失返回 401。

详见 [harnax-auth AUTH-DESIGN.md](../harnax-auth/AUTH-DESIGN.md)。

## 熔断器

每个 agent-service 实例维护独立的熔断器状态，采用经典的三态模型：

```
        recordFailure() × N        openDurationMs 过期
CLOSED ──────────────────> OPEN ────────────────────────> HALF_OPEN
  ↑                         │                              │
  │                         │ (isOpen=true, 拒绝路由)       │ recordSuccess() → CLOSED
  │                         │                              │ recordFailure() → OPEN
  └─────────────────────────┘                              └───── recordSuccess() ──────┘
```

| 状态 | 行为 |
|------|------|
| **CLOSED** | 正常路由，累计失败次数。达到 `failure-threshold` 后转 OPEN |
| **OPEN** | 拒绝路由到该实例，故障转移自动跳过熔断中的节点。`open-duration-ms` 过期后转 HALF_OPEN |
| **HALF_OPEN** | 放行一次探测请求，探测槽由 `probe-lease-ms` 租约持有，避免多节点同时探测。成功则转 CLOSED（恢复）；失败则转 OPEN（重新计时） |

**多节点一致性**：`CACHE_TYPE=redis` 模式下，熔断状态存在 Redis 的单个 hash（`router:circuit:{instanceId}`），所有状态迁移只由 Lua 脚本改写，因此节点 A 熔断的实例，节点 B/C 也不会路由，且不会出现两个节点交错读改写导致的误恢复。

## 实例放置

一个请求该发到哪台 agent-service，规则只有三条：

1. **粘性优先**：会话已绑定的实例只要 `isHealthy(heartbeatTimeoutMs)` 且非 DRAINING，就继续使用；
2. **新放置排除熔断实例**：需要重新放置时，候选集先减去本次已失败的实例，再减去 `trippedInstances()` 命中的实例；
3. **已绑定的实例不因熔断被搬走**：熔断只决定**新放置**。会话已绑定的实例恰好熔断时绑定保持不变——
   否则一次熔断会把该实例上所有会话同时改绑，把一个慢 agent 变成全集群的重绑风暴，而且下个请求这个
   绑定还会原样回来。

## 故障转移策略

当健康检查发现实例心跳超时（默认 30s）时，`HeartbeatHealthChecker` 执行故障转移：

1. **冷却期保护**（10s）：同一实例短时间内不重复触发转移，防止风暴
2. **CAS 标记**：`markInstanceDown()` 使用原子操作，多 Router 节点竞争时仅一个执行转移；已被别的节点标记为 DOWN 的实例直接跳过
3. **候选筛选**：只在 UP 实例中选择，并先排除熔断中的节点；若全部候选都在熔断，则照旧迁移（会话卡死在死实例上比可能再搬一次更糟）
4. **单目标 + 过载避让**：一台下线实例的所有 session 整体迁往**同一台**目标实例（会话上下文与沙箱不拆分）；若该目标负载已超过平均值的 2 倍，则改选负载低于 1.5 倍平均值的替代节点
5. **批量迁移**：`rebindAllSessions()` 分批改写绑定（上限 200 批），避免在调度线程上无界阻塞
6. **通知旧实例**：迁移后向下线实例发送驱逐（`router.migration.evict-old-instance`），令其以 `STOP_SANDBOX` 方式释放沙箱——工作区快照保留，历史与计划不动；该通知尽力而为、不占用请求线程

## 调用日志

`ApiCallLogFilter` 采用**白名单策略**：仅记录 `/api/router/agent/` 前缀下的请求。其他端点（health、metrics、instance heartbeat、monitor API/UI 等）不记录。

SSE 与 `suspend` 端点在过滤器链返回时**响应还没结束**：那一刻读状态码只会得到 200 和接近 0 的耗时，
恰恰是把失败的调用记成成功。因此这两类端点：

- **不用** `ContentCachingResponseWrapper`（它会缓冲整个响应体，破坏 SSE 事件流）；
- 在**服务线程上**一次性解析好调用方、sessionId、agent/model 富化信息和落点实例；
- 只把**状态码与耗时**推迟到 `AsyncListener` 的 `onComplete` / `onTimeout` / `onError` 再读，三路汇合
  到一个 `AtomicBoolean`，保证一次调用只落一行。

| 路径分类 | 判定依据 | response 包装 | 落行时机 |
|---------|---------|--------------|---------|
| SSE 流 | 以 `/stream` 结尾，或 `/api/router/agent/confirm` | 不包装 | 异步完成后，直读原始 status |
| suspend 批处理 | `/chat`、`/command`、`/session`、`/chat/history`、`/workspace` 前缀 | 不包装 | 异步完成后 |
| 其余同步端点 | — | `ContentCachingResponseWrapper` | 链返回即写 |
| 前缀之外（health / metrics / heartbeat / monitor…） | — | 不记录 | — |

代理控制器目前**全部**是 `suspend` 方法，因此实际都走异步分支；包装分支保留给将来非挂起的同步端点。

落点实例（`instanceId`）不能从 `MDC` 读：MDC 属于发起路由的线程，不属于写完响应的那个线程；路由成功
后会把实例 ID 挂在 request attribute（`SessionRouterService.ROUTED_INSTANCE_ATTR`）上，日志从这里取。

日志通过 `ApiCallLogService` 异步批量写入 `api_call_log` 表（MyBatis XML 动态查询）。

## 安全防护

### SSRF 防护

实例注册时校验 host 和 port：

- 仅允许 IPv4 格式（拒绝域名，防止 DNS 重绑定）
- 阻止回环地址（`127.x`、`::1`）
- 阻止链路本地地址（`169.254.x`，防止云元数据端点攻击）
- 阻止已知云元数据主机名（`metadata.google.internal` 等）
- 端口限制在 8000-9999
- 内网私有 IP（`10.x`、`172.16-31.x`、`192.168.x`）允许注册

### 请求验证

- `instanceId` ≤ 64、`host` ≤ 128，注册时由 `InstanceRegistrationValidationFilter` 拒绝（并强制字符集）
- `sessionId` ≤ 128 且非空，由会话绑定服务在读写绑定前校验

## 配置参考

```yaml
router:
  cache:
    type: ${CACHE_TYPE:local}     # local | redis（唯一开关，无 TTL 旋钮）
  health:
    heartbeat-timeout-ms: 30000   # 心跳超时判定
    check-interval-ms: 5000       # 健康检查间隔
  proxy:
    connect-timeout-ms: 5000          # 连接 agent-service 超时
    read-timeout-ms: 600000           # 读超时（10min，仅 JSON 调用；流自带超时约束）
    max-in-memory-size-mb: 16         # 响应体内存缓冲上限（大 payload 场景）
    max-connections-per-instance: 50  # 单个 agent 实例的连接上限（池按 host:port 分，天然隔离）
    pending-acquire-timeout-ms: 10000 # 实例连接池打满时的排队上限
    pending-acquire-max-count: 100    # 排队人数超过此值直接快速拒绝，避免一起超时
    pool-max-idle-seconds: 60         # 空闲连接保留时长
    pool-max-lifetime-minutes: 5      # 连接按年龄回收，避免黏在已重新均衡的实例上
    stream-idle-timeout-seconds: 120  # 流静默超过此长度即视为结束
    stream-max-duration-minutes: 30   # 单条流的墙钟上限，无论多活跃
    failover-max-retries: 2           # 代理重试次数
  migration:
    evict-old-instance: true      # 迁移后通知旧实例停沙箱（尽力而为，不占请求线程）
    max-pending: 200              # 驱逐排队上限，超出即丢弃新的
  idempotency:
    ttl-seconds: 60               # 请求去重窗口
  circuit-breaker:
    failure-threshold: 3          # 连续可重试失败数触发熔断
    open-duration-ms: 30000       # 熔断维持时长
    probe-lease-ms: 30000         # 半开态为唯一探测请求保留槽位的时长
  reconcile:
    interval-ms: 300000           # 反向索引对账间隔（仅 redis 模式，代码内默认值）
    initial-delay-ms: 120000      # 首次对账延迟（代码内默认值）

spring:
  mvc:
    async:
      request-timeout: 1800000    # 30min，必须不短于 stream-max-duration-minutes
  task:
    scheduling:
      pool:
        size: 4                   # 默认单线程，一个阻塞的任务会拖垮其余 @Scheduled

harnax:
  auth:
    enabled: true
    service-id: ${SERVICE_ID:router-0}
    internal:
      shared-secret: ${HARNAX_AUTH_SECRET:change-me-in-production-min-32-chars!!}  # 与其他服务共享的 JWT 密钥
      token-ttl-seconds: 300              # 内部服务间 JWT 有效期
    external:
      enabled: true            # 启用 API Key 认证
    skip-paths:                # UnifiedAuthFilter 跳过路径（详见上方说明）
      - /ui
      - /app.js

admin:
  service:
    url: ${ADMIN_SERVICE_URL:http://172.20.10.5:8080}  # 出厂默认是开发内网地址，部署时必须覆盖
  internal-api:
    secret: ${ADMIN_INTERNAL_API_SECRET:change-me-in-production-min-32-chars!!}
    timeout-connect-ms: 2000        # 代码内默认值
    timeout-response-ms: 3000       # 代码内默认值
```

> 仓库里 `HARNAX_AUTH_SECRET`、`ADMIN_INTERNAL_API_SECRET` 的兜底值和 admin / MySQL 的 `172.20.10.5`
> 都是开发环境的占位值。任何非本机部署都必须显式覆盖，否则等于用公开在仓库里的密钥签发和校验凭证。
>
> `redis` 模式下这个「必须」由代码兜住：`PlaceholderSecretCheck` 启动时发现两个密钥还是占位值就直接失败退出，
> 而不是带着一个任何人都能伪造的内部凭证起来跑。`local` 模式不校验——单机自测丢掉启动只会逼人关掉认证。

### 环境变量

按部署模式列出的环境变量见下文「部署 → 环境变量」，此处不再重复一份会漂移的副本。

## 源码结构

```
src/main/kotlin/com/agnetix/harnax/router/
├── SessionRouterApplication.kt          # Spring Boot 入口
├── controller/
│   ├── AgentProxyController.kt         # 对话/工作区代理（14 个端点，无 @InternalOnly；12 suspend + 2 SSE Flux）
│   ├── InstanceRegistryController.kt   # 实例注册管理（5 个端点 + 2 个运维端点，类级 @InternalOnly）
│   ├── RouterMonitorController.kt      # 监控面板 API（2 个端点，只读，需凭证）
│   └── RootController.kt               # 监控 UI 入口（GET /ui → 返回静态 HTML）
├── config/
│   ├── RouterConfig.kt                  # WebClient + 连接池 + Bean 装配（local/redis 双模式）
│   ├── RedisConfig.kt                   # RedisTemplate 配置
│   ├── ApiCallLogFilter.kt              # 调用日志拦截（白名单 + 异步完成落行）
│   ├── InstanceRegistrationValidationFilter.kt  # 注册参数校验 + SSRF 防护
│   ├── GlobalExceptionHandler.kt        # 非流端点兜底：异常写进 body 的 code，HTTP 仍 200
│   ├── SqliteInitConfig.kt              # local 模式建表（执行 db/sqlite-init.sql）
│   ├── SqliteDirectoryInitializer.kt    # SQLite 父目录准备
│   ├── PlaceholderSecretCheck.kt        # redis 模式拒绝用仓库占位密钥启动
│   └── StartupUrlPrinter.kt            # 启动时打印监控 URL（含凭证用法）
├── dto/
│   ├── ApiCallLogQuery.kt              # 调用日志查询 + 分页 DTO
│   └── RouterResponses.kt             # 响应 DTO
├── entity/
│   ├── AgentInstance.kt                 # 实例实体（含 SSRF 校验逻辑）
│   └── ApiCallLog.kt                    # 调用日志实体
├── health/
│   └── HeartbeatHealthChecker.kt        # 定时健康检查 + 故障转移 + 驱逐通知 + 健康实例数 gauge
├── mapper/
│   └── ApiCallLogMapper.kt              # 调用日志 MyBatis Mapper
├── proxy/
│   └── SessionRouterService.kt          # 核心代理逻辑（路由 + 重试 + 熔断 + 落点记录）
└── service/
    ├── InstanceRegistry.kt              # 实例注册接口
    ├── SessionMappingService.kt         # 会话绑定接口
    ├── IdempotencyService.kt            # 幂等性接口
    ├── InstanceCircuitBreaker.kt        # 熔断器接口
    ├── RateLimiter.kt                   # 滑动窗口限流（实现 RateLimitChecker SPI）
    ├── RemoteApiKeyStore.kt             # HTTP 调用 admin 校验 API Key
    ├── SessionInfoClient.kt             # HTTP 调用 admin 获取 session 信息
    ├── AdminClientService.kt            # admin 内部接口调用的公共客户端（含超时）
    ├── AgentServiceClient.kt            # 调用 agent-service（注册心跳对端 / 驱逐通知）
    ├── SessionEvictor.kt                # 迁移后通知旧实例停沙箱
    ├── ApiCallLogService.kt             # 异步批量写入调用日志
    └── impl/
        ├── LocalInstanceRegistry.kt     # 纯内存实例注册
        ├── LocalSessionMappingService.kt     # 纯内存会话绑定（含反向索引 + 绑定过期）
        ├── CaffeineIdempotencyService.kt     # 本地幂等性
        ├── LocalInstanceCircuitBreaker.kt    # 本节点熔断器
        ├── RedisInstanceRegistry.kt     # Redis 实例注册
        ├── RedisSessionMappingService.kt     # Redis 会话绑定（Lua 原子改写 + 24h TTL）
        ├── RedisIdempotencyService.kt        # Redis 幂等性
        ├── RedisCircuitBreaker.kt       # Redis 熔断器（Lua 状态机）
        ├── SessionIndexReconciler.kt    # 反向索引对账（仅 redis 模式）
        └── ThrottledWarn.kt             # 降级日志限流，避免故障时刷屏

src/main/resources/
├── application.yml                      # 默认配置（local 模式：SQLite + 进程内存状态）
├── application-cluster.yml              # 集群模式（MySQL + Redis，profile 激活）
├── db/migration/
│   └── V1__create_session_router_tables.sql  # Flyway: api_call_log 表
├── db/sqlite-init.sql                   # local 模式建表脚本
├── mapper/
│   └── ApiCallLogMapper.xml             # MyBatis XML 动态查询
└── static/
    ├── index.html                       # 监控面板前端页面
    ├── style.css                        # 样式
    └── app.js                           # 前端逻辑
```

## 部署

### JVM 参数

```bash
java -Xms2g -Xmx4g \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -jar harnax-session-router.jar
```

### 环境变量

#### 通用（local + cluster）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SERVICE_ID` | 本 Router 实例标识 | `router-0` |
| `HARNAX_AUTH_SECRET` | JWT 共享密钥（最少 32 字符） | 仓库内占位值，**必须覆盖** |
| `ADMIN_SERVICE_URL` | admin 服务地址 | `http://172.20.10.5:8080`（开发内网，**必须覆盖**） |
| `ADMIN_INTERNAL_API_SECRET` | admin 内部 API 密钥 | 仓库内占位值，**必须覆盖** |
| `ROUTER_CORS_ALLOWED_ORIGINS` | 允许的浏览器 Origin 列表 | localhost / 127.0.0.1 的常见组合 |
| `ROUTER_SCHEDULER_POOL_SIZE` | `@Scheduled` 线程数 | `4` |

#### 仅 local 模式

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `ROUTER_SQLITE_PATH` | SQLite 数据库文件路径 | `tmp/harnax-router/call-log.db` |
| `ROUTER_DB_URL` | 直接覆盖数据源 URL（换库时用） | `jdbc:sqlite:${ROUTER_SQLITE_PATH}` |
| `ROUTER_DB_DRIVER` | JDBC 驱动 | `org.sqlite.JDBC` |
| `CACHE_TYPE` | 缓存模式 | `local` |

#### 仅 cluster 模式（`--spring.profiles.active=cluster`）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DB_URL` | MySQL 连接串 | `jdbc:mysql://172.20.10.5:3306/harnax_router?...`（**必须覆盖**） |
| `DB_USERNAME` | MySQL 用户名 | `root`（默认口令 `123456` 仅开发环境） |
| `DB_PASSWORD` | MySQL 密码 | `123456`（**必须覆盖**） |
| `CACHE_TYPE` | 缓存模式（cluster profile 已固定为 redis） | `redis` |
| `REDIS_HOST` | Redis 地址 | `172.20.10.5`（**必须覆盖**） |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | 空 |
| `REDIS_DATABASE` | Redis 数据库索引 | `0` |
| `REDIS_CLUSTER_NODES` | Redis Cluster 节点（非空即按 cluster 连接） | 空（standalone） |
| `ROUTER_RECONCILE_INTERVAL_MS` | 反向索引对账间隔 | `300000` |
| `ROUTER_RECONCILE_BATCH_SIZE` | 对账单批条数 | `500` |
| `ROUTER_RECONCILE_MAX_BATCHES` | 单个索引最多扫几批 | `40` |

### Kubernetes

```yaml
resources:
  requests: { memory: "2Gi", cpu: "1000m" }
  limits:   { memory: "4Gi", cpu: "2000m" }
livenessProbe:
  # 不要用 /api/router/health：那是"有几个健康 agent"的容量报表，
  # 最后一个 agent 掉线时它会 DOWN，而进程本身仍然健康。
  httpGet: { path: /actuator/health/liveness, port: 8081 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  # cluster 模式的就绪包含 Redis：共享状态不可达时这个节点无法路由，应当摘出负载均衡
  httpGet: { path: /actuator/health/readiness, port: 8081 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## 监控

### 内置监控面板

启动后访问 `http://<host>:8081/ui?token=<jwt or api key>`，可查看：

- 已注册实例列表（IP、端口、状态、session 数、心跳延迟）
- API 调用明细（支持按 sessionId / instanceId / agentName 查询，含耗时）

页面自身是匿名可取的静态文件，面板 API 需要凭证，详见上方「监控面板」。
启动日志中会自动打印面板地址（`StartupUrlPrinter`）。

### Actuator 端点

- `/actuator/health` — 服务健康状态
- `/actuator/health/liveness` — 存活探针（进程在不在）
- `/actuator/health/readiness` — 就绪探针（cluster 模式含 Redis：共享状态不可达即不该留在 LB 里）
- `/actuator/prometheus` — Prometheus 指标
- `/actuator/metrics` — 全部指标列表
- `/api/router/metrics/cache` — 当前生效的注册表 / 会话映射实现类名（内部 JWT）

### 关键指标

四个自定义 meter（Micrometer → Prometheus 会把点换成下划线）：

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `router_proxy_duration_seconds` | Timer | `endpoint=chat` | 对话请求延迟 |
| `router_proxy_requests_total` | Counter | `endpoint`=chat / stream，`status`=ok / error | 请求总数 |
| `router_failover_count_total` | Counter | `endpoint`、`attempt` | 代理重试/故障转移次数 |
| `router_healthy_instances` | Gauge | — | 本副本可放置会话的实例数 |

其余为 JVM / HTTP / HikariCP 内置指标。

`router_healthy_instances` 由 `HeartbeatHealthChecker` 注册，抓取时现场读一次
`getHealthyInstances()`（排除 DOWN / DRAINING / 心跳超时），所以它是实时视图而非事件累加。
注意它是**每个 Router 副本各报各的**：多副本部署时某个副本的 Redis 视图落后，就会比其他副本偏低，
`min()` 比 `avg()` 更适合做告警条件。

### 告警建议

```yaml
# Prometheus alerting rules
- alert: HighErrorRate
  expr: rate(router_proxy_requests_total{status="error"}[5m]) > 0.1
  for: 5m
- alert: FailoverStorm
  expr: rate(router_failover_count_total[5m]) > 1
  for: 5m
# 某个副本看不到任何可放置的实例：min() 而非 avg()，避免副本间视图不一致时被平均值掩盖
- alert: LowHealthyInstances
  expr: min(router_healthy_instances) < 1
  for: 2m
```

## 数据库迁移

Flyway 管理，位于 `src/main/resources/db/migration/`：

| 版本 | 说明 |
|------|------|
| V1 | 建表 `api_call_log`（调用日志） |

**local 模式**：Flyway 禁用，启动时由 `SqliteInitConfig` 执行 `db/sqlite-init.sql` 建表。  
**cluster 模式**：Flyway 启用，启动时自动执行 `db/migration` 下的迁移脚本。  

实例注册、会话绑定等状态全部存储在内存（local）或 Redis（cluster）中，不依赖 MySQL。

## 实例生命周期

agent-service 实例在 Router 中的状态流转：

| 状态 | 触发 | 说明 |
|------|------|------|
| `UP` | `register` / `heartbeat` | 正常接收路由请求 |
| `DOWN` | 心跳超时（30s）/ 健康检查 | 自动触发故障转移，session 迁走 |
| `DRAINING` | `drain` 接口 | 优雅停机：不再接收新 session，但继续处理已有请求 |

```
agent-service 启动 → register → UP
                  ↓ 定期 heartbeat（建议 10s 间隔）
                  ↓ 心跳超时 → DOWN → session 自动迁移
                  ↓ 主动 drain → DRAINING → 等待请求完成 → unregister
```
