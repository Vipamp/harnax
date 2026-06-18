# Harnax Session Router

Session Router 是 harnax 分布式 Agent 平台的核心网关层，负责将外部请求路由到正确的 agent-service 实例，并提供会话粘性、健康检查、故障转移和请求幂等性保障。

## 架构概览

```
Channel / 外部 HTTP ──JWT/API Key──> Router :8081 ──JWT──> Agent-service :8082
                                        │
                                        ├── admin :8080 (HTTP, API Key 校验 + Session 信息查询)
                                        └── MySQL (仅 api_call_log 调用日志)
```

**核心职责：**

- **会话路由**：同一 session 的请求始终路由到同一 agent-service 实例（会话粘性）
- **健康检查**：5 秒间隔心跳检测，自动发现并剔除故障实例
- **故障转移**：实例宕机时自动将绑定的 session 迁移到健康实例，带冷却期防止风暴
- **请求幂等**：基于 requestId 的去重，防止网络重试导致重复调用
- **熔断保护**：单实例连续 3 次失败后熔断 30 秒，防止级联故障
- **调用日志**：异步批量记录每次 API 调用的耗时、状态、agent/model 信息

## 缓存模式

Router 支持两种缓存模式，通过 `router.cache.type` 配置切换：

| 模式 | 适用场景 | 实现 |
|------|---------|------|
| `local`（默认） | 单节点部署 | ConcurrentHashMap，纯内存 |
| `redis` | 多节点部署 | Redis Hash + Set，跨节点共享状态 |

**MySQL 仅用于调用日志**（`api_call_log` 表），实例注册、会话绑定、API Key 校验均不依赖 MySQL。

### Redis Key 设计

```
router:instance:{instanceId}           Hash   实例信息 (host, port, status, lastHeartbeat)
router:instances:healthy               Set    健康实例 ID 集合
router:instances:all                   Set    所有活跃实例 ID 集合
router:session:{sessionId}             String 绑定的 instanceId（TTL 300s）
router:session:instance:{instanceId}   Set    反向索引：实例绑定的 session 集合
router:idempotency:{requestId}         String 幂等性标记（TTL 60s）
```

## API 端点

### 代理转发（scope: `router:invoke`）

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

### 实例管理（scope: `router:register`，internalOnly）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/router/instance/register` | 注册实例（含 SSRF 防护） |
| POST | `/api/router/instance/heartbeat` | 心跳刷新 |
| POST | `/api/router/instance/unregister` | 注销实例 |
| POST | `/api/router/instance/drain` | 优雅停机（drain 模式） |
| GET | `/api/router/instance/list` | 列出所有活跃实例 |

### 运维

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/router/health` | 健康检查（无需认证） |
| GET | `/api/router/metrics/cache` | 缓存统计（无需认证） |

## 认证鉴权

Router 通过 `harnax-auth` 共享库实现统一认证：

- **内部调用**（channel → router → agent）：JWT Bearer Token，scope 控制
- **外部调用**（第三方 HTTP）：`X-Api-Key` 请求头，Router 通过 HTTP 调用 admin 的 `POST /api/internal/api-keys/validate` 校验
- **限流**：外部 API Key 支持滑动窗口限流（`RateLimitInterceptor`）

认证白名单：`/health`、`/actuator/**`、`/ai/**`（临时保留）

详见 [harnax-auth AUTH-DESIGN.md](../harnax-auth/AUTH-DESIGN.md)。

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
- `ApiCallLogFilter` 拦截每个请求记录调用日志（排除 `/health`、`/actuator`）

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

admin:
  service:
    url: ${ADMIN_URL:http://localhost:8080}  # admin 内部接口地址
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SERVICE_ID` | 本 Router 实例标识 | `router-0` |
| `HARNAX_AUTH_SECRET` | JWT 共享密钥（最少 32 字符） | - |
| `ADMIN_URL` | admin 服务地址 | `http://localhost:8080` |
| `DB_URL` | MySQL 连接串（仅调用日志） | `jdbc:mysql://localhost:3306/harnax_router` |
| `REDIS_HOST` | Redis 地址（redis 模式需要） | `localhost` |
| `CACHE_TYPE` | 缓存模式 | `local` |

## 源码结构

```
src/main/kotlin/com/agnetix/harnax/router/
├── SessionRouterApplication.kt          # Spring Boot 入口
├── controller/
│   └── SessionRouterController.kt       # 15 个 API 端点
├── config/
│   ├── RouterConfig.kt                  # WebClient + Bean 装配（local/redis 双模式）
│   ├── RedisConfig.kt                   # RedisTemplate 配置
│   ├── ApiCallLogFilter.kt              # 请求拦截 → 调用日志
│   └── InstanceRegistrationValidationFilter.kt  # 注册参数校验 + SSRF 防护
├── dto/
│   └── RouterResponses.kt               # 响应 DTO
├── entity/
│   ├── AgentInstance.kt                 # 实例实体（含 SSRF 校验逻辑）
│   └── ApiCallLog.kt                    # 调用日志实体
├── health/
│   └── HeartbeatHealthChecker.kt        # 定时健康检查 + 故障转移
├── mapper/
│   └── ApiCallLogMapper.kt              # 调用日志 MyBatis Mapper（唯一的 Mapper）
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
  httpGet: { path: /actuator/health, port: 8081 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /actuator/health, port: 8081 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## 监控

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
| V1 | 建表：agent_instance、session_mapping |
| V2 | 索引优化 |
| V3 | 增加 version、draining 字段 |
| V4 | api_key 表（已弃用，改为 HTTP 调用 admin） |
| V5 | api_call_log 调用日志表 |
