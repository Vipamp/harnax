# Harnax Session Router

Session Router 是 harnax 分布式 Agent 平台的核心网关层，负责将外部请求路由到正确的 agent-service 实例，并提供会话粘性、健康检查、故障转移和请求幂等性保障。

## 架构概览

### 单机部署（local 模式，默认）

适合开发、测试、小规模生产环境。单 Router 实例，纯内存状态，无需 Redis。

```
Channel / 外部 HTTP ──JWT/API Key──> Router :8081 ──JWT──> Agent-service :8082
                                     │ (Caffeine 内存缓存)
                                     │   ├ 实例注册表
                                     │   ├ session 绑定
                                     │   ├ 幂等性去重
                                     │   └ 熔断器状态
                                     ├── admin :8080 (API Key 校验 + Session 信息查询)
                                     └── MySQL (仅 api_call_log 调用日志)
```

**特点：**
- 零外部依赖（Redis），启动即用
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
- Redis 故障降级：Redis 不可用时实例注册、会话绑定、熔断均失效，但 Router 进程本身不崩

### 部署模式对比

| 能力 | local（单机） | redis（分布式） |
|------|------------|----------------|
| 外部依赖 | 仅 MySQL | MySQL + Redis |
| 水平扩展 | 不支持 | 支持 |
| 状态持久化 | 进程内存，重启丢失 | Redis，重启不丢失 |
| 熔断一致性 | 仅本节点 | 全节点共享 |
| 故障转移 | 本节点执行 | 任意节点均可执行（CAS 竞争） |
| 会话粘性 | 仅本节点可见 | 全节点可见，任意节点可路由 |
| 适用场景 | 开发 / 测试 / 个人版 | 生产 / 多节点集群 |

通过 `CACHE_TYPE` 环境变量切换：

```bash
# 单机模式（默认）
CACHE_TYPE=local java -jar harnax-session-router.jar

# 分布式模式
CACHE_TYPE=redis REDIS_HOST=redis.internal java -jar harnax-session-router.jar
```

**MySQL 仅用于调用日志**（`api_call_log` 表），实例注册、会话绑定、API Key 校验均不依赖 MySQL。

### Redis Key 设计

仅 `redis` 模式使用：

```
router:instance:{instanceId}           Hash   实例信息 (host, port, status, lastHeartbeat)
router:instances:healthy               Set    健康实例 ID 集合
router:instances:all                   Set    所有活跃实例 ID 集合
router:session:{sessionId}             String 绑定的 instanceId（TTL 300s）
router:session:instance:{instanceId}   Set    反向索引：实例绑定的 session 集合
router:idempotency:{requestId}         String 幂等性标记（TTL 60s）
router:circuit:{instanceId}:state      String 熔断器状态 (CLOSED/OPEN/HALF_OPEN, TTL = openDurationMs)
router:circuit:{instanceId}:failures   Int    连续失败计数
router:circuit:{instanceId}:last_failure Long  最后一次失败的时间戳 (ms)
```

## 核心职责

- **会话路由**：同一 session 的请求始终路由到同一 agent-service 实例（会话粘性）
- **健康检查**：5 秒间隔心跳检测，自动发现并剔除故障实例
- **故障转移**：实例宕机时自动将绑定的 session 迁移到健康实例（详见下方故障转移策略）
- **请求幂等**：基于 requestId 的去重，防止网络重试导致重复调用
- **熔断保护**：基于三态状态机的熔断模式，防止级联故障（详见下方熔断器章节）
- **调用日志**：异步批量记录 `/api/router/agent/` 下每次 API 调用的耗时、状态、agent/model 信息

## 快速开始

### 本地开发

```bash
# 1. 确保 MySQL 和 Redis 已启动（redis 仅 CACHE_TYPE=redis 时需要）

# 2. 编译
mvn clean package -pl harnax-session-router -am -DskipTests

# 3. 启动（local 缓存模式，无需 Redis）
java -jar harnax-session-router/target/harnax-session-router.jar

# 4. 打开监控面板
open http://localhost:8081/ui
```

### Docker 部署

```bash
# 一键构建并启动（personal 版）
cd docker && bash build.sh personal

# 或手动启动
docker compose -f docker/docker-compose.personal.yml up -d
```

Docker 编排包含：MySQL、Redis、admin backend、router、nginx frontend 五个服务，详见 [docker/README.md](../docker/README.md)。

### agent-service 接入

agent-service 启动后向 Router 注册即可自动接入：

```
POST /api/router/instance/register?instanceId=agent-1&host=10.0.1.5&port=8082
POST /api/router/instance/heartbeat?instanceId=agent-1    # 每 10s 心跳
POST /api/router/instance/drain?instanceId=agent-1        # 优雅停机
POST /api/router/instance/unregister?instanceId=agent-1   # 注销
```

## API 端点

### 对话代理（AgentProxyController）

内部服务和外部 API Key 均可访问，无 `@InternalOnly` 限制。所有请求会被 `ApiCallLogFilter` 记录调用日志。

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

只读的可观测性接口，**无** `@InternalOnly` 限制，设计为由浏览器直接访问。访问控制依赖上游网络边界（nginx / VPC），不在应用层做鉴权。

`/api/router/monitor/` 前缀已加入 `skip-paths`，`UnifiedAuthFilter` 不会拦截。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/router/monitor/instances` | 实例列表（含 session 数、心跳延迟） |
| GET | `/api/router/monitor/call-logs` | 调用日志分页查询（支持 sessionId / instanceId / agentName / statusCode 等过滤） |

### 监控 UI

访问 `http://<host>:8081/ui` 打开内置的静态监控面板，展示：

- 已注册的 agent-service 实例列表（IP、端口、状态、session 数、心跳延迟）
- API 调用明细（按 sessionId / instanceId / agentName 查询，含耗时）

### 运维

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/router/health` | 健康检查（无需认证） |
| GET | `/api/router/metrics/cache` | 缓存统计（无需认证） |

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
  - /api/router/monitor/   # 监控面板 API
```

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
| **HALF_OPEN** | 放行一次请求。成功则转 CLOSED（恢复）；失败则转 OPEN（重新计时） |

**多节点一致性**：`CACHE_TYPE=redis` 模式下，熔断状态存储在 Redis（`router:circuit:*`），所有 Router 节点共享同一熔断视图，避免某节点已熔断但其他节点仍在路由。

## 故障转移策略

当健康检查发现实例心跳超时（默认 30s）时，`HeartbeatHealthChecker` 执行智能故障转移：

1. **冷却期保护**（10s）：同一实例短时间内不重复触发转移，防止风暴
2. **CAS 标记**：`markInstanceDown()` 使用原子操作，多 Router 节点竞争时仅一个执行转移
3. **最少负载选择**：从健康实例中选 session 数最少的作为目标，避免集中
4. **过载避让**：如果目标实例的 session 数超过平均值的 2 倍，自动寻找负载更低的替代节点（< 平均值 1.5 倍）
5. **批量迁移**：将下线实例的所有 session 绑定一次性迁移到目标实例

## 调用日志

`ApiCallLogFilter` 采用**白名单策略**：仅记录 `/api/router/agent/` 前缀下的请求。其他端点（health、metrics、instance heartbeat、monitor UI 等）不记录。

SSE 端点（`/stream` 后缀、`/confirm`）**同样记录**，但响应不使用 `ContentCachingResponseWrapper`（会缓冲整个响应体，破坏 SSE 事件流），而是直接从原始 response 读取状态码。

| 端点 | 记录 | response 处理 |
|------|------|---------------|
| `/api/router/agent/chat/{id}` | 记录 | `ContentCachingResponseWrapper` |
| `/api/router/agent/chat/{id}/stream` | 记录 | 不包装，直接读原始 status |
| `/api/router/agent/confirm` | 记录 | 不包装，直接读原始 status |
| 其他所有路径 | 不记录 | 不包装 |

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

- `sessionId` 和 `instanceId` 长度限制（128 / 64 字符）

## 配置参考

```yaml
router:
  cache:
    type: local              # local | redis
    instance-ttl-seconds: 3  # local 模式实例缓存 TTL
    session-ttl-seconds: 300 # session 绑定缓存 TTL（5 分钟）
  health:
    heartbeat-timeout-ms: 30000  # 心跳超时判定
    check-interval-ms: 5000      # 健康检查间隔
  proxy:
    connect-timeout-ms: 5000     # 连接超时
    read-timeout-ms: 60000       # 读超时（AI 推理较长）
    stream-timeout-minutes: 15   # SSE 流总超时
    failover-max-retries: 2      # 故障转移重试次数
    max-connections: 200         # WebClient 连接池上限
    max-in-memory-size-mb: 16    # 响应体内存缓冲上限（大 payload 场景）
    pending-acquire-timeout-ms: 10000  # 连接池等待获取超时
    write-timeout-seconds: 30    # 写超时
  idempotency:
    ttl-seconds: 60              # 请求去重窗口
  circuit-breaker:
    failure-threshold: 3         # 熔断触发失败数
    open-duration-ms: 30000      # 熔断持续时间

harnax:
  auth:
    enabled: true
    service-id: ${SERVICE_ID:router-0}
    internal:
      shared-secret: ${HARNAX_AUTH_SECRET}  # 与其他服务共享的 JWT 密钥
    external:
      enabled: true            # 启用 API Key 认证
    skip-paths:                # UnifiedAuthFilter 跳过路径（详见上方说明）
      - /ui
      - /api/router/monitor/

admin:
  service:
    url: ${ADMIN_SERVICE_URL:http://localhost:8080}  # admin 内部接口地址
  internal-api:
    secret: ${ADMIN_INTERNAL_API_SECRET}             # admin 内部 API 密钥
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SERVICE_ID` | 本 Router 实例标识 | `router-0` |
| `HARNAX_AUTH_SECRET` | JWT 共享密钥（最少 32 字符） | - |
| `ADMIN_SERVICE_URL` | admin 服务地址 | `http://localhost:8080` |
| `ADMIN_INTERNAL_API_SECRET` | admin 内部 API 密钥 | - |
| `DB_URL` | MySQL 连接串（仅调用日志） | `jdbc:mysql://localhost:3306/harnax_router` |
| `DB_USERNAME` | MySQL 用户名 | `root` |
| `DB_PASSWORD` | MySQL 密码 | - |
| `CACHE_TYPE` | 缓存模式 | `local` |
| `REDIS_HOST` | Redis 地址（redis 模式需要） | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | 空 |
| `REDIS_DATABASE` | Redis 数据库索引 | `0` |

## 源码结构

```
src/main/kotlin/com/agnetix/harnax/router/
├── SessionRouterApplication.kt          # Spring Boot 入口
├── controller/
│   ├── AgentProxyController.kt         # AI 对话代理（8 个端点，无 @InternalOnly）
│   ├── InstanceRegistryController.kt   # 实例注册管理（5 个端点，@InternalOnly）
│   ├── RouterMonitorController.kt      # 监控面板 API（2 个端点，只读）
│   └── RootController.kt               # 监控 UI 入口（GET /ui → 返回静态 HTML）
├── config/
│   ├── RouterConfig.kt                  # WebClient + Bean 装配（local/redis 双模式）
│   ├── RedisConfig.kt                   # RedisTemplate 配置
│   ├── ApiCallLogFilter.kt              # 调用日志拦截（白名单：仅 /api/router/agent/）
│   ├── InstanceRegistrationValidationFilter.kt  # 注册参数校验 + SSRF 防护
│   └── StartupUrlPrinter.kt            # 启动时打印监控 URL 到日志
├── dto/
│   ├── ApiCallLogQuery.kt              # 调用日志查询 + 分页 DTO
│   └── RouterResponses.kt             # 响应 DTO
├── entity/
│   ├── AgentInstance.kt                 # 实例实体（含 SSRF 校验逻辑）
│   └── ApiCallLog.kt                    # 调用日志实体
├── health/
│   └── HeartbeatHealthChecker.kt        # 定时健康检查 + 故障转移
├── mapper/
│   └── ApiCallLogMapper.kt              # 调用日志 MyBatis Mapper
├── proxy/
│   └── SessionRouterService.kt          # 核心代理逻辑（路由 + 重试 + 熔断）
└── service/
    ├── InstanceRegistry.kt              # 实例注册接口
    ├── SessionMappingService.kt         # 会话绑定接口
    ├── IdempotencyService.kt            # 幂等性接口
    ├── InstanceCircuitBreaker.kt        # 熔断器
    ├── RateLimiter.kt                   # 滑动窗口限流（实现 RateLimitChecker SPI）
    ├── RemoteApiKeyStore.kt             # HTTP 调用 admin 校验 API Key
    ├── SessionInfoClient.kt             # HTTP 调用 admin 获取 session 信息
    ├── ApiCallLogService.kt             # 异步批量写入调用日志
    └── impl/
        ├── LocalInstanceRegistry.kt     # 纯内存实例注册
        ├── CaffeineSessionMappingService.kt  # 纯内存会话绑定
        ├── CaffeineIdempotencyService.kt     # 本地幂等性
        ├── RedisInstanceRegistry.kt     # Redis 实例注册
        ├── RedisSessionMappingService.kt     # Redis 会话绑定
        ├── RedisIdempotencyService.kt        # Redis 幂等性
        └── RedisCircuitBreaker.kt       # Redis 熔断器（可选）

src/main/resources/
├── application.yml                      # 主配置
├── db/migration/
│   └── V1__create_session_router_tables.sql  # Flyway: api_call_log 表
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

### Kubernetes

```yaml
resources:
  requests: { memory: "2Gi", cpu: "1000m" }
  limits:   { memory: "4Gi", cpu: "2000m" }
livenessProbe:
  httpGet: { path: /api/router/health, port: 8081 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /api/router/health, port: 8081 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## 监控

### 内置监控面板

启动后访问 `http://<host>:8081/ui`，可查看：

- 已注册实例列表（IP、端口、状态、session 数、心跳延迟）
- API 调用明细（支持按 sessionId / instanceId / agentName 查询，含耗时）

启动日志中会自动打印面板地址（`StartupUrlPrinter`）。

### Actuator 端点

- `/actuator/health` — 服务健康状态
- `/actuator/prometheus` — Prometheus 指标
- `/actuator/metrics` — 全部指标列表
- `/api/router/metrics/cache` — 缓存统计（实例数、命中率）

### 关键指标

| 指标 | 说明 |
|------|------|
| `router_proxy_duration_seconds` | 代理请求延迟 |
| `router_proxy_requests_total` | 请求总数（按 status / endpoint） |
| `router_failover_count_total` | 故障转移次数 |
| `hikaricp_connections_active` | 数据库连接数 |

### 告警建议

```yaml
# Prometheus alerting rules
- alert: HighErrorRate
  expr: rate(router_proxy_requests_total{status="error"}[5m]) > 0.1
  for: 5m
- alert: LowHealthyInstances
  expr: router_healthy_instances < 2
  for: 2m
```

## 数据库迁移

Flyway 管理，位于 `src/main/resources/db/migration/`：

| 版本 | 说明 |
|------|------|
| V1 | 建表 `api_call_log`（调用日志） |

实例注册、会话绑定等状态全部存储在内存或 Redis 中，不依赖 MySQL。

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
