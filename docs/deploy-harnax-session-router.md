# harnax-session-router 部署文档

## 服务概述

harnax-session-router 是会话路由器，负责：
- 接收客户端/渠道请求，按 session 亲和性路由到对应的 agent-service 实例
- 管理 agent-service 实例注册、心跳、健康检查
- 代理 SSE 流式请求和同步批处理请求
- 实例故障时自动 failover（session 迁移）
- API 调用日志记录

- **端口**: 8081
- **数据库**: 
  - 本地模式：SQLite（嵌入式，无需外部数据库）
  - 集群模式：MySQL（harnax_router 库，用于 api_call_log）
- **缓存模式**: `local`（单机）或 `redis`（集群），由 `CACHE_TYPE` 控制
- **认证方式**: UnifiedAuthFilter (内部 JWT)

---

## 系统部署总览

| 服务 | 本地模式外部依赖 | 集群模式外部依赖 | 多实例就绪 |
|------|----------------|----------------|-----------|
| harnax-admin | MySQL | MySQL | 否（有状态组件） |
| harnax-session-router | 无（SQLite + 内存） | MySQL + Redis | 是 |
| harnax-agent-service | MySQL + Docker | MySQL + Docker + MinIO | 是 |
| harnax-webui | 无（静态文件） | 无 | 是 |

---

## 环境依赖

| 组件 | 本地模式 | 集群模式 | 说明 |
|------|---------|---------|------|
| JDK 21 | 必须 | 必须 | 运行时 |
| SQLite | 内置 | 不需要 | 本地模式使用嵌入式 SQLite，无需额外安装 |
| MySQL 8.0 | 不需要 | 必须 | 集群模式的 api_call_log 存储 |
| Redis 7 | 不需要 | 必须 | 共享实例注册表 + 会话映射 |
| nginx | 不需要 | 必须 | 多 Router 实例负载均衡 |
| harnax-admin | 必须 | 必须 | API Key 校验、会话信息查询 |

---

## 核心概念：local 与 redis 模式

Router 通过 `CACHE_TYPE` 环境变量切换两种存储后端：

| 存储 | `local` 模式 (默认) | `redis` 模式 |
|------|---------------------|--------------|
| 实例注册表 (InstanceRegistry) | 内存 ConcurrentHashMap | Redis Hash (`router:instance:{id}`，24h TTL) |
| 会话映射 (SessionMappingService) | `LocalSessionMappingService`，ConcurrentHashMap + 反向索引 | Redis Key (`router:session:{id}`，24h TTL，每次请求续期) |
| 幂等服务 (IdempotencyService) | Caffeine 本地缓存 | Redis SET NX |
| 熔断器 (CircuitBreaker) | 内存 ConcurrentHashMap | 单实例单 Hash (`router:circuit:{id}`)，状态迁移只由 Lua 改写 |

> 会话绑定生命周期两种模式共用同一个常量 `RedisSessionMappingService.SESSION_TTL`（24h）：local 模式同样
> 有定时清理任务丢弃超期绑定，因此一个会话在开发环境和在集群里保持其 agent 的时长完全一致。

> **重要**: 部署多个 Router 时必须使用 `redis` 模式。`local` 模式仅适用于单实例部署，多实例部署使用 `local` 模式会导致各 Router 状态不共享，session 路由完全失效。

---

## 数据库说明

### 本地模式（SQLite）

本地模式使用嵌入式 SQLite，**无需安装或配置外部数据库**：

- 数据库文件位置：yml 默认 `tmp/harnax-router/call-log.db`（相对路径），服务器部署建议用 `ROUTER_SQLITE_PATH` 指到 `/var/lib/harnax-router/call-log.db`
- 可通过环境变量 `ROUTER_SQLITE_PATH` 自定义路径
- 应用启动时自动创建数据库和表结构
- 监控面板的调用日志查询功能完全可用

```bash
# 自定义 SQLite 文件路径
export ROUTER_SQLITE_PATH="/data/harnax-router/call-log.db"
```

### 集群模式（MySQL）

集群模式使用 MySQL 存储调用日志：

```sql
CREATE DATABASE IF NOT EXISTS harnax_router
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

Flyway 会在启动时自动创建 `api_call_log` 表。

---

## 环境变量说明

### 基础配置

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `CACHE_TYPE` | `local` | 缓存模式: `local` 或 `redis` |
| `SERVICE_ID` | `router-0` | 本实例在 UnifiedAuth 中的标识 |

### 数据库配置（本地模式 - SQLite）

本地模式默认使用 SQLite，无需外部数据库：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `ROUTER_SQLITE_PATH` | `tmp/harnax-router/call-log.db` | SQLite 数据库文件路径（yml 默认是相对路径，容器部署应显式指到挂载卷） |
| `ROUTER_DB_URL` | `jdbc:sqlite:${ROUTER_SQLITE_PATH}` | 需要整体替换数据源 URL 时使用 |
| `ROUTER_DB_DRIVER` | `org.sqlite.JDBC` | JDBC 驱动 |
| `ROUTER_DB_POOL_SIZE` | `10` | 连接池大小（SQLite 并发有限，通常无需调大） |

> **注意**：确保 SQLite 文件所在目录存在且有写权限。首次启动前需创建目录：
> ```bash
> mkdir -p /var/lib/harnax-router
> export ROUTER_SQLITE_PATH=/var/lib/harnax-router/call-log.db
> ```

### 数据库配置（集群模式 - MySQL）

集群模式使用 MySQL，需要激活 `cluster` profile：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `DB_URL` | `jdbc:mysql://172.20.10.5:3306/harnax_router?...` | MySQL 连接串 |
| `DB_USERNAME` | `root` | 数据库用户名 |
| `DB_PASSWORD` | `123456` | 数据库密码 |

### Redis 配置 (仅 `redis` 模式)

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `REDIS_HOST` | `172.20.10.5` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | (空) | Redis 密码 (无密码留空) |
| `REDIS_DATABASE` | `0` | Redis DB 编号 |

### 认证配置

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `HARNAX_AUTH_SECRET` | `change-me-in-production-min-32-chars!!` | 内部服务认证密钥 (>=32字符) |
| `ADMIN_SERVICE_URL` | `http://172.20.10.5:8080` | Admin 服务地址 |
| `ADMIN_INTERNAL_API_SECRET` | `change-me-in-production-min-32-chars!!` | Admin 内部 API 密钥 |

> **密钥一致性**: `HARNAX_AUTH_SECRET` 需与 admin、agent-service、channel-service 保持一致。`ADMIN_INTERNAL_API_SECRET` 需与 admin 的配置一致。

> **启动校验**：集群模式（`router.cache.type=redis`）下，上表两个密钥只要还等于仓库里的占位值，
> `PlaceholderSecretCheck` 就会在启动阶段抛错退出——带着人人可伪造的内部凭证起来，等同于没有认证。
> 单机 local 模式不做这个校验。

### 路由参数 (application.yml 内置，一般无需调整)

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `router.health.heartbeat-timeout-ms` | `30000` | 实例心跳超时 (30s 无心跳视为下线) |
| `router.health.check-interval-ms` | `5000` | 健康检查周期 |
| `router.proxy.read-timeout-ms` | `600000` | 代理读超时 (10min，仅 JSON 调用) |
| `router.proxy.stream-idle-timeout-seconds` | `120` | SSE 流静默上限（无事件即结束） |
| `router.proxy.stream-max-duration-minutes` | `30` | SSE 流墙钟上限 |
| `router.proxy.failover-max-retries` | `2` | failover 最大重试次数 |
| `router.circuit-breaker.failure-threshold` | `3` | 连续失败次数触发熔断 |
| `router.circuit-breaker.open-duration-ms` | `30000` | 熔断恢复间隔 |
| `router.idempotency.ttl-seconds` | `60` | 请求去重 TTL |

---

## 本地模式部署

### 架构

```
客户端 / 渠道
    │
    ▼
harnax-session-router (×1)    CACHE_TYPE=local
    │
    ├──► agent-service-1
    └──► agent-service-2

    SQLite (call-log.db)      ← 嵌入式，无需外部数据库
```

### 特点

- **零外部数据库依赖**：使用内置 SQLite 存储调用日志
- **启动即用**：自动创建数据库文件和表结构
- **监控面板完整可用**：调用日志查询功能与 MySQL 模式一致

### 单机启动

```bash
# 1. 创建 SQLite 文件目录
mkdir -p /var/lib/harnax-router

# 2. 构建
cd /path/to/harnax
mvn clean package -DskipTests -pl harnax-session-router -am

# 3. 启动（默认使用 SQLite）
export CACHE_TYPE=local
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"
export ADMIN_SERVICE_URL="http://YOUR_ADMIN_HOST:8080"
export ADMIN_INTERNAL_API_SECRET="your-admin-secret-at-least-32-chars"

# 可选：自定义 SQLite 文件路径
# export ROUTER_SQLITE_PATH="/data/harnax-router/call-log.db"

java -Xms256m -Xmx512m -jar harnax-session-router/target/harnax-session-router-*.jar
```

本地模式下，agent-service 的 `router.service.url` 直接指向此 Router 的地址。

---

## 集群模式部署

### 架构

```
客户端 / 渠道
    │
    ▼
nginx (ip_hash)
    │
    ├──► Router-1 ─┐
    └──► Router-2 ─┤  CACHE_TYPE=redis
                   │
                   ├──► agent-service-1
                   └──► agent-service-2
                   │
                   ├── MySQL (api_call_log)    ← 集群模式使用 MySQL
                   └── Redis (共享状态)
```

### 前置条件

集群模式需要预先创建 MySQL 数据库：

```sql
CREATE DATABASE IF NOT EXISTS harnax_router
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

Flyway 会在启动时自动创建 `api_call_log` 表。

### nginx 配置

```nginx
upstream router_cluster {
    ip_hash;  # 客户端粘在同一 Router：不是正确性要求（会话绑定在 Redis 里，任意节点都能路由
              # 任意 session），但省掉跨节点重连的抖动，也让面板的轮询落在同一台节点上
    server router-1:8081;
    server router-2:8081;
    # server router-3:8081;
}

server {
    listen 80;
    server_name router.your-domain.com;

    # SSE 流式端点 — 必须关闭所有缓冲
    location ~ ^/api/router/agent/(chat/stream|confirm) {
        proxy_pass http://router_cluster;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_request_buffering off;
        proxy_set_header Cache-Control "no-cache";
        proxy_set_header X-Accel-Buffering "no";
        gzip off;
        proxy_set_header Accept-Encoding "";
        chunked_transfer_encoding on;

        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 900s;   # 15min，只保证不早于 Router 的 120s 静默判定切断
        proxy_next_upstream error timeout;
        proxy_next_upstream_tries 1;
    }

    # 其他 Router API (health, instance, monitor)
    location /api/router/ {
        proxy_pass http://router_cluster;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # Router 监控 UI
    location = /ui {
        proxy_pass http://router_cluster;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

> **ip_hash 说明**: 会话粘性由 Redis 里的 session→instance 绑定保证，与客户端落在哪个 Router 无关，
> 因此 `ip_hash` 不是正确性要求。已建立的 SSE 流是一条具体的 TCP 连接，负载均衡无法在中途把它挪到另
> 一台 Router；真正会断流的是 Router 进程本身重启，那种情况下无论用什么算法都得由客户端重连。用
> `ip_hash` 的收益是减少重连与跨节点漂移，代价是节点增减时哈希环变化会重排部分客户端。

### 多实例启动

集群模式需要激活 `cluster` profile 以使用 MySQL：

```bash
# Router-1 (机器 A)
export CACHE_TYPE=redis
export DB_URL="jdbc:mysql://DB_HOST:3306/harnax_router?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export DB_USERNAME="harnax"
export DB_PASSWORD="your_password"
export REDIS_HOST=REDIS_HOST
export REDIS_PORT=6379
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"
export ADMIN_SERVICE_URL="http://ADMIN_HOST:8080"
export ADMIN_INTERNAL_API_SECRET="your-admin-secret-at-least-32-chars"
export SERVICE_ID=router-1

# 激活 cluster profile 使用 MySQL
java -Xms512m -Xmx1024m \
  -jar harnax-session-router-*.jar \
  --spring.profiles.active=cluster

# Router-2 (机器 B) — 配置相同，仅 SERVICE_ID 不同
export CACHE_TYPE=redis
export DB_URL="jdbc:mysql://DB_HOST:3306/harnax_router?..."
export DB_USERNAME="harnax"
export DB_PASSWORD="your_password"
export REDIS_HOST=REDIS_HOST
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"  # 必须一致
export SERVICE_ID=router-2

java -Xms512m -Xmx1024m \
  -jar harnax-session-router-*.jar \
  --spring.profiles.active=cluster
```

> **Redis 数据说明**: Router 使用以下 Redis Key 前缀，不要与其他应用冲突:
> - `router:instance:{id}` — 实例注册信息 (Hash, 24h TTL)
> - `router:instances:healthy` / `router:instances:all` — 实例集合 (Set)
> - `router:session:{sessionId}` — 会话映射 (String, 24h TTL)
> - `router:instance_sessions:{instanceId}` — 实例会话反向索引 (Set)
> - `router:lock:session:{sessionId}` — reroute 分布式锁 (String, 5s TTL)
> - `router:circuit:{instanceId}` — 熔断器状态 (Hash，Lua 原子改写)
> - `router:lock:index_reconcile` — 反向索引对账锁 (String, 4min TTL，仅一个节点执行)
>
> `router.reconcile.*`（`ROUTER_RECONCILE_INTERVAL_MS` 等）只在 cluster profile 中定义。

---

## Agent Service 注册方式

Agent Service 通过 `router.service.url` 配置 Router 地址，启动时 POST 注册，之后定时发心跳。

- **本地模式**: `router.service.url` 直连 Router 地址
- **集群模式**: `router.service.url` 指向 nginx 地址

注册后，Router 通过健康检查 (每 5s) 发现不健康的实例，把它绑定的 session **整体迁移到同一台**目标实例
（不拆分会话，熔断中的实例先被排除；目标过载时改选负载更低的节点），并通知旧实例释放沙箱。

---

## 健康检查

```bash
# 容器 / 负载均衡探针（无需凭证，UnifiedAuthFilter 内置放行 /actuator）
curl http://localhost:8081/actuator/health/liveness
curl http://localhost:8081/actuator/health/readiness

# 容量报表：当前有几个可路由的健康实例（需内部 JWT）
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/router/health
```

`/api/router/health` 返回当前健康实例数量，它是**容量报表**而不是探针：最后一个 agent 停止心跳时它会
报 DOWN，而那正是 Router 自身仍然健康、只是拒绝接活的时候，用它做存活探针会导致无意义的重启。
cluster 模式的 `/actuator/health/readiness` 包含 Redis 检查——共享状态不可达时该节点已无法正确路由，
应当摘出负载均衡；调用日志库（MySQL）不参与就绪判定，写日志失败不该让路由下线。

---

## 端口与防火墙

| 端口 | 用途 | 对外暴露 |
|------|------|---------|
| 8081 | HTTP API + SSE 代理 | 本地模式可直连；集群模式下仅 nginx 可访问 |

> 容器编排里 8081 直接映射为宿主机的 28081，供服务间与联调直连（绕过 nginx）。这不代表它可以匿名：
> 除 `/actuator`、`/health` 和面板静态文件外，Router 自己的每个 API 都要凭证，绕过 nginx 少的是传输层
> 边界，不是认证。

---

## 代理调优参数

以下参数在 `application.yml` 中配置，一般无需调整，但在高并发场景下可能需要关注：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `router.proxy.connect-timeout-ms` | `5000` | 连接 agent-service 超时（5 秒） |
| `router.proxy.read-timeout-ms` | `600000` | 代理读超时（10 分钟，只作用于 JSON 调用；流自带超时约束） |
| `router.proxy.stream-idle-timeout-seconds` | `120` | 流静默多久即判定结束 |
| `router.proxy.stream-max-duration-minutes` | `30` | 单条流的墙钟上限，无论多活跃 |
| `router.proxy.failover-max-retries` | `2` | 故障转移重试次数 |
| `router.proxy.max-connections-per-instance` | `50` | **单个** agent 实例的连接上限（Reactor Netty 按 host:port 分池，一台卡住的实例吃不掉整个 Router） |
| `router.proxy.max-in-memory-size-mb` | `16` | 响应体内存缓冲上限 |
| `router.proxy.pending-acquire-timeout-ms` | `10000` | 实例池打满时调用方的排队上限 |
| `router.proxy.pending-acquire-max-count` | `100` | 排队人数超过此值直接快速拒绝，避免一起超时 |
| `router.proxy.pool-max-idle-seconds` | `60` | 空闲连接保留时长 |
| `router.proxy.pool-max-lifetime-minutes` | `5` | 连接按年龄回收，避免黏在已重新均衡的实例上 |
| `spring.mvc.async.request-timeout` | `1800000` | Spring MVC 异步请求超时（30 分钟，必须不短于 `stream-max-duration-minutes`，否则容器会抢在 Router 之前掐断流） |
| `spring.task.scheduling.pool.size` | `4` | `@Scheduled` 线程数。默认单线程，一个阻塞任务（健康检查等 Redis）会拖住日志刷盘等其余任务 |
| `spring.codec.max-in-memory-size` | `16MB` | WebFlux 编解码器内存上限 |
| `server.forward-headers-strategy` | `native` | 转发头策略，nginx 后置时必须为 `native` |

### Admin 客户端超时

Router 调用 admin 内部 API（API Key 校验、Session 信息查询）的超时参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `admin.internal-api.timeout-connect-ms` | `2000` | HTTP 连接超时（2 秒） |
| `admin.internal-api.timeout-response-ms` | `3000` | HTTP 响应超时（3 秒） |

---

## 认证与安全

### Auth Skip Paths

以下路径前缀被 `UnifiedAuthFilter` 跳过，不需要认证：

```yaml
harnax:
  auth:
    skip-paths:
      - /ui                    # 监控 UI 静态页面
      - /ui/
      - /index.html
      - /static/
      - /favicon.ico
      - /style.css
      - /app.js
```

跳过的只有渲染面板所需的静态文件。`/api/router/monitor/*` 曾经在这里，现已移出：`instances` 是整
个集群的内部地址拓扑，`call-logs` 是 Router 见过的每一次调用（含 sessionId 与错误文本），匿名可读等
于把这两样摆在公网上。现在两者都要 `Authorization: Bearer <jwt>` 或 `X-Api-Key`。

`UnifiedAuthFilter` 另外内置跳过 `/health` 与 `/actuator` 前缀，无需在 skip-paths 里声明。

> **安全警告**：不要把 `/api/router/` 整体加入 skip-paths，否则 heartbeat / register 等 `@InternalOnly` 接口的 JWT 不会被解析，导致所有内部接口返回 401。

### 外部 API Key 认证

```yaml
harnax:
  auth:
    external:
      enabled: true            # 启用外部 API Key 认证（默认开启）
```

外部调用通过 `X-Api-Key` 请求头认证，Router 通过 HTTP 调用 admin 的 `POST /api/internal/api-keys/validate` 校验。支持滑动窗口限流（`RateLimitInterceptor`）。

---

## 监控与可观测性

### 内置监控面板

访问 `http://<host>:8081/ui?token=<jwt or api key>` 查看：
- 已注册实例列表（IP、端口、状态、session 数、心跳延迟）
- API 调用明细（支持按 sessionId / instanceId / agentName 查询，含耗时）

页面把 token 存进 localStorage，一次性带上即可持续轮询；地址栏里的 `?token=` 会被抹掉，避免留在浏览
器历史中。凭证缺失或失效时面板停止轮询并提示需要凭证。启动日志中的地址已带上该参数形式。

### 监控 API 端点

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/router/monitor/instances` | JWT / API Key | 实例列表（含 session 数、心跳延迟） |
| GET | `/api/router/monitor/call-logs` | JWT / API Key | 调用日志分页查询 |
| GET | `/api/router/metrics/cache` | 内部 JWT | 当前生效的注册表 / 会话映射实现类名 |

### Spring Boot Actuator

Router 暴露了以下 Actuator 端点：

| 端点 | 说明 |
|------|------|
| `/actuator/health` | 服务健康状态 |
| `/actuator/info` | 应用信息 |
| `/actuator/prometheus` | Prometheus 指标 |
| `/actuator/metrics` | 全部指标列表 |

> **安全提示**：`UnifiedAuthFilter` 内置跳过 `/actuator` 前缀，这些端点是匿名可读的（暴露线程、堆、
> 数据源等运行时信息）。生产环境建议通过 nginx 限制来源，或收窄
> `management.endpoints.web.exposure.include`（当前为 `health,info,prometheus,metrics`）。

### 关键 Prometheus 指标

| 指标 | 说明 |
|------|------|
| `router_proxy_duration_seconds` | 代理请求延迟 |
| `router_proxy_requests_total` | 请求总数（按 status / endpoint） |
| `router_failover_count_total` | 故障转移次数 |
| `router_healthy_instances` | 该副本可放置会话的实例数（排除 DOWN / DRAINING / 心跳超时） |
| `hikaricp_connections_active` | 数据库连接数 |

> `router_healthy_instances` 每个 Router 副本各报各的，抓取时现场读一次注册表。多副本时某副本的 Redis
> 视图落后会比其他副本偏低，因此告警用 `min(router_healthy_instances) < 1` 而不是 `avg()`。

---

## 集群模式下的已知限制

| 项目 | 说明 | 影响 |
|------|------|------|
| RateLimiter 是本地的 | 每个 Router 独立限流，N 个 Router 限流阈值放大 N 倍 | 配合 nginx ip_hash 后影响可控 |
| API Key 缓存 5 分钟 | 撤销 Key 后最多 5 分钟生效 | 安全敏感场景需注意 |
| SSE 不可跨 Router 恢复 | Router 宕机时客户端需重连 | 客户端需实现重连逻辑 |
| Redis 不可达期间的降级 | 会话绑定退到本节点影子缓存，实例表退到最后一次快照；快照按心跳超时老化后不再路由 | 该窗口内跨节点粘性无法保证，Redis 恢复后仍以 Redis 为准 |
| 熔断只影响新放置 | 已绑定实例恰好熔断时，该会话仍会先试原实例，失败后再走代理故障转移 | 换取的是"一次熔断不会把整台实例的会话同时改绑"，代价是首跳可能白等一次超时 |
