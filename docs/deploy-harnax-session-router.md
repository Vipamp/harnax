# harnax-session-router 部署文档

## 服务概述

harnax-session-router 是会话路由器，负责：
- 接收客户端/渠道请求，按 session 亲和性路由到对应的 agent-service 实例
- 管理 agent-service 实例注册、心跳、健康检查
- 代理 SSE 流式请求和同步批处理请求
- 实例故障时自动 failover（session 迁移）
- API 调用日志记录

- **端口**: 8081
- **数据库**: harnax_router (MySQL, 仅用于 api_call_log)
- **缓存模式**: `local`（单机）或 `redis`（集群），由 `CACHE_TYPE` 控制
- **认证方式**: UnifiedAuthFilter (内部 JWT)

---

## 环境依赖

| 组件 | 本地模式 | 集群模式 | 说明 |
|------|---------|---------|------|
| JDK 21 | 必须 | 必须 | 运行时 |
| MySQL 8.0 | 必须 | 必须 | api_call_log 存储 |
| Redis 7 | 不需要 | 必须 | 共享实例注册表 + 会话映射 |
| nginx | 不需要 | 必须 | 多 Router 实例负载均衡 |
| harnax-admin | 必须 | 必须 | API Key 校验、会话信息查询 |

---

## 核心概念：local 与 redis 模式

Router 通过 `CACHE_TYPE` 环境变量切换两种存储后端：

| 存储 | `local` 模式 (默认) | `redis` 模式 |
|------|---------------------|--------------|
| 实例注册表 (InstanceRegistry) | 内存 ConcurrentHashMap | Redis Hash (`router:instance:{id}`) |
| 会话映射 (SessionMappingService) | 内存 ConcurrentHashMap | Redis Key (`router:session:{id}`) |
| 幂等服务 (IdempotencyService) | Caffeine 本地缓存 | Redis SET NX |
| 熔断器 (CircuitBreaker) | 内存 ConcurrentHashMap | Redis Key (`router:circuit:{id}:*`) |

> **重要**: 部署多个 Router 时必须使用 `redis` 模式。`local` 模式仅适用于单实例部署，多实例部署使用 `local` 模式会导致各 Router 状态不共享，session 路由完全失效。

---

## 数据库初始化

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
| `DB_URL` | `jdbc:mysql://172.20.10.5:3306/harnax_router?...` | MySQL 连接串 |
| `DB_USERNAME` | `root` | 数据库用户名 |
| `DB_PASSWORD` | `123456` | 数据库密码 |
| `CACHE_TYPE` | `local` | 缓存模式: `local` 或 `redis` |
| `SERVICE_ID` | `router-0` | 本实例在 UnifiedAuth 中的标识 |

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

### 路由参数 (application.yml 内置，一般无需调整)

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `router.health.heartbeat-timeout-ms` | `30000` | 实例心跳超时 (30s 无心跳视为下线) |
| `router.health.check-interval-ms` | `5000` | 健康检查周期 |
| `router.proxy.read-timeout-ms` | `600000` | 代理读超时 (10min) |
| `router.proxy.stream-timeout-minutes` | `10` | SSE 流超时 |
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

          MySQL (api_call_log)
```

### 单机启动

```bash
# 构建
cd /path/to/harnax
mvn clean package -DskipTests -pl harnax-session-router -am

# 启动
export CACHE_TYPE=local
export DB_URL="jdbc:mysql://YOUR_DB_HOST:3306/harnax_router?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export DB_USERNAME="your_db_user"
export DB_PASSWORD="your_db_password"
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"
export ADMIN_SERVICE_URL="http://YOUR_ADMIN_HOST:8080"
export ADMIN_INTERNAL_API_SECRET="your-admin-secret-at-least-32-chars"

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
                   ├── MySQL (api_call_log)
                   └── Redis (共享状态)
```

### nginx 配置

```nginx
upstream router_cluster {
    ip_hash;  # sticky session: 保证同一客户端命中同一 Router
              # 这是 SSE 长连接必须的，否则流会在 Router 切换时断开
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
        proxy_read_timeout 900s;  # 15 min
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

> **ip_hash 说明**: `ip_hash` 基于客户端 IP 做 sticky session。SSE 流式连接是长连接，不能跨 Router 迁移，必须保证同一客户端的所有请求（包括 SSE 流和心跳）都路由到同一个 Router。如果使用 `round-robin`，SSE 流会在 Router 切换时断开。

### 多实例启动

```bash
# Router-1 (机器 A)
export CACHE_TYPE=redis
export DB_URL="jdbc:mysql://DB_HOST:3306/harnax_router?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export REDIS_HOST=REDIS_HOST
export REDIS_PORT=6379
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"
export ADMIN_SERVICE_URL="http://ADMIN_HOST:8080"
export ADMIN_INTERNAL_API_SECRET="your-admin-secret-at-least-32-chars"
export SERVICE_ID=router-1

java -Xms512m -Xmx1024m -jar harnax-session-router-*.jar

# Router-2 (机器 B) — 配置相同，仅 SERVICE_ID 不同
export CACHE_TYPE=redis
export DB_URL="jdbc:mysql://DB_HOST:3306/harnax_router?..."
export REDIS_HOST=REDIS_HOST
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"  # 必须一致
export SERVICE_ID=router-2

java -Xms512m -Xmx1024m -jar harnax-session-router-*.jar
```

> **Redis 数据说明**: Router 使用以下 Redis Key 前缀，不要与其他应用冲突:
> - `router:instance:{id}` — 实例注册信息 (Hash, 24h TTL)
> - `router:instances:healthy` / `router:instances:all` — 实例集合 (Set)
> - `router:session:{sessionId}` — 会话映射 (String, 24h TTL)
> - `router:instance_sessions:{instanceId}` — 实例会话反向索引 (Set)
> - `router:lock:session:{sessionId}` — reroute 分布式锁 (String, 5s TTL)
> - `router:circuit:{instanceId}:*` — 熔断器状态

---

## Agent Service 注册方式

Agent Service 通过 `router.service.url` 配置 Router 地址，启动时 POST 注册，之后定时发心跳。

- **本地模式**: `router.service.url` 直连 Router 地址
- **集群模式**: `router.service.url` 指向 nginx 地址

注册后，Router 通过健康检查 (每 5s) 发现不健康的实例，自动将 session 迁移到健康的实例上。

---

## 健康检查

```bash
curl http://localhost:8081/api/router/health
```

返回内容包含当前健康实例数量。HTTP 200 表示 Router 本身正常（不保证有健康的 Agent 实例）。

---

## 端口与防火墙

| 端口 | 用途 | 对外暴露 |
|------|------|---------|
| 8081 | HTTP API + SSE 代理 | 本地模式可直连；集群模式下仅 nginx 可访问 |

---

## 集群模式下的已知限制

| 项目 | 说明 | 影响 |
|------|------|------|
| RateLimiter 是本地的 | 每个 Router 独立限流，N 个 Router 限流阈值放大 N 倍 | 配合 nginx ip_hash 后影响可控 |
| API Key 缓存 5 分钟 | 撤销 Key 后最多 5 分钟生效 | 安全敏感场景需注意 |
| SSE 不可跨 Router 恢复 | Router 宕机时客户端需重连 | 客户端需实现重连逻辑 |
